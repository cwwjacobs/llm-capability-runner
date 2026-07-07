package com.terminus.edge.light.model

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed interface HfResult<out T> {
  data class Success<T>(val value: T) : HfResult<T>

  data class Failure(val message: String) : HfResult<Nothing>
}

data class HfModelInfo(
  val id: String,
  val downloads: Int,
  val parameterLabel: String? = null,
  val minimumDownloadBytes: Long? = null,
  val maximumDownloadBytes: Long? = null,
)

data class HfModelFile(
  val path: String,
  val sizeBytes: Long?,
) {
  private val fileName: String
    get() = path.substringAfterLast('/')

  val isProjector: Boolean
    get() =
      fileName.startsWith("mmproj", ignoreCase = true) ||
        fileName.contains("projector", ignoreCase = true)

  val isSplitShard: Boolean
    get() = SPLIT_GGUF.containsMatchIn(path)

  val isAuxiliaryArtifact: Boolean
    get() = isProjector || AUXILIARY_ARTIFACT.containsMatchIn(fileName)

  val isPrimaryChoice: Boolean
    get() =
      !isAuxiliaryArtifact &&
        (sizeBytes == null || sizeBytes >= MIN_RUNNABLE_MODEL_BYTES) &&
        (!isSplitShard || path.contains("-00001-of-", ignoreCase = true))

  val isHardwareSpecific: Boolean
    get() =
      HARDWARE_SUFFIXES.any { suffix -> path.contains(suffix, ignoreCase = true) }

  private companion object {
    val HARDWARE_SUFFIXES = listOf("_sm", "_mt", "tensor_g")
    val SPLIT_GGUF = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
    val AUXILIARY_ARTIFACT =
      Regex(
        "(^|[-_.])(vision|clip|siglip|encoder|embed|embedding|adapter|lora|tokenizer)([-_.]|$)",
        RegexOption.IGNORE_CASE,
      )
  }
}

object HuggingFaceClient {
  private const val API_BASE_URL = "https://huggingface.co/api"
  private const val HUB_BASE_URL = "https://huggingface.co"
  private const val USER_AGENT = "Edge-Light/0.7 (Android; LiteRT-LM; llama.cpp)"

  suspend fun listModels(query: String, token: String? = null): HfResult<List<HfModelInfo>> =
    withContext(Dispatchers.IO) {
      val term = query.trim().ifEmpty { "gemma" }
      val urls =
        listOf(
          "$API_BASE_URL/models?filter=gguf&search=${encodeQuery(term)}&sort=downloads&direction=-1&limit=30",
          "$API_BASE_URL/models?filter=litert-lm&search=${encodeQuery(term)}&sort=downloads&direction=-1&limit=30",
        )
      val models = linkedMapOf<String, HfModelInfo>()
      var firstFailure: String? = null
      urls.forEach { url ->
        when (val response = getText(url, token)) {
          is HfResult.Failure -> if (firstFailure == null) firstFailure = response.message
          is HfResult.Success ->
            runCatching {
                val values = JSONArray(response.value)
                for (index in 0 until values.length()) {
                  val item = values.getJSONObject(index)
                  val model = HfModelInfo(item.getString("id"), item.optInt("downloads", 0))
                  models[model.id] = model
                }
              }
              .onFailure { if (firstFailure == null) firstFailure = "Hugging Face returned an unreadable model list." }
        }
      }
      if (models.isEmpty() && firstFailure != null) {
        HfResult.Failure(firstFailure!!)
      } else {
        val ranked = models.values.sortedByDescending(HfModelInfo::downloads).take(18)
        val enriched =
          coroutineScope {
            ranked
              .map { model ->
                async {
                  when (val files = listModelFiles(model.id, token)) {
                    is HfResult.Failure -> null
                    is HfResult.Success -> {
                      val sizeRange = downloadableSizeRange(files.value) ?: return@async null
                      model.copy(
                        parameterLabel = parameterLabel(model.id),
                        minimumDownloadBytes = sizeRange.first,
                        maximumDownloadBytes = sizeRange.second,
                      )
                    }
                  }
                }
              }
              .awaitAll()
              .filterNotNull()
              .take(12)
          }
        HfResult.Success(enriched)
      }
    }

  suspend fun listModelFiles(
    repoId: String,
    token: String? = null,
  ): HfResult<List<HfModelFile>> =
    withContext(Dispatchers.IO) {
      val encodedRepo = repoId.split('/').joinToString("/") { encodePathSegment(it) }
      val url = "$API_BASE_URL/models/$encodedRepo/tree/main?recursive=true&expand=false"
      when (val response = getText(url, token)) {
        is HfResult.Failure -> response
        is HfResult.Success ->
          runCatching {
              val values = JSONArray(response.value)
              buildList {
                  for (index in 0 until values.length()) {
                    val item = values.getJSONObject(index)
                    if (item.optString("type") != "file") continue
                    val path = item.optString("path")
                    if (!isCompatibleModelFile(path)) continue
                    val directSize = item.optLong("size", -1L)
                    val lfsSize = item.optJSONObject("lfs")?.optLong("size", -1L) ?: -1L
                    add(
                      HfModelFile(
                        path = path,
                        sizeBytes = listOf(directSize, lfsSize).firstOrNull { it >= 0L },
                      )
                    )
                  }
                }
                .sortedWith(
                  compareBy<HfModelFile> { it.isHardwareSpecific }
                    .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
                    .thenBy { it.path.lowercase() }
                )
            }
            .fold(
              onSuccess = { files ->
                if (files.none(HfModelFile::isPrimaryChoice)) {
                  HfResult.Failure("This repository has no runnable primary GGUF or LiteRT-LM model builds.")
                } else {
                  HfResult.Success(files)
                }
              },
              onFailure = { HfResult.Failure("Hugging Face returned an unreadable file list.") },
            )
      }
    }

  suspend fun downloadFile(
    repoId: String,
    modelFile: HfModelFile,
    destination: File,
    token: String? = null,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
  ): HfResult<File> =
    withContext(Dispatchers.IO) {
      destination.parentFile?.mkdirs()
      val partial = File(destination.parentFile, "${destination.name}.part")
      val encodedRepo = repoId.split('/').joinToString("/") { encodePathSegment(it) }
      val encodedPath = modelFile.path.split('/').joinToString("/") { encodePathSegment(it) }
      val existingBytes = partial.length().coerceAtLeast(0L)
      val connection =
        (URL("$HUB_BASE_URL/$encodedRepo/resolve/main/$encodedPath?download=true").openConnection()
          as HttpURLConnection)
          .configured(token, download = true)
          .apply {
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
          }
      try {
        val status = connection.responseCode
        if (status !in 200..299) return@withContext HfResult.Failure(statusMessage(status))
        val resumed = status == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
        val responseBytes = connection.contentLengthLong.takeIf { it > 0L }
        val expectedBytes =
          modelFile.sizeBytes
            ?: responseBytes?.let { if (resumed) existingBytes + it else it }
        var downloadedBytes = if (resumed) existingBytes else 0L
        var lastProgressAt = 0L
        connection.inputStream.use { input ->
          FileOutputStream(partial, resumed).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              if (count == 0) continue
              output.write(buffer, 0, count)
              downloadedBytes += count
              val now = System.currentTimeMillis()
              if (now - lastProgressAt >= 250L) {
                onProgress(downloadedBytes, expectedBytes)
                lastProgressAt = now
              }
            }
            output.fd.sync()
          }
        }
        if (downloadedBytes <= 0L) {
          return@withContext HfResult.Failure("The downloaded model was empty.")
        }
        if (expectedBytes != null && downloadedBytes != expectedBytes) {
          return@withContext HfResult.Failure(
            "Download stopped early at ${formatBytes(downloadedBytes)} of ${formatBytes(expectedBytes)}."
          )
        }
        try {
          Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
          )
        } catch (_: Exception) {
          Files.move(
            partial.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
          )
        }
        onProgress(downloadedBytes, expectedBytes)
        HfResult.Success(destination)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        HfResult.Failure(error.message?.take(180) ?: "Model download failed.")
      } finally {
        connection.disconnect()
      }
    }

  internal fun isCompatibleModelFile(path: String): Boolean =
    path.endsWith(".litertlm", ignoreCase = true) ||
      path.endsWith(".gguf", ignoreCase = true)

  internal fun requiredFiles(
    selected: HfModelFile,
    allFiles: List<HfModelFile>,
  ): List<HfModelFile> {
    if (!selected.isSplitShard) return listOf(selected)
    val prefix = selected.path.substringBefore("-00001-of-", selected.path)
    val splitSuffix = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
    return allFiles
      .filter { it.isSplitShard && splitSuffix.replace(it.path, "") == prefix }
      .sortedBy(HfModelFile::path)
  }

  internal fun companionProjector(
    selected: HfModelFile,
    allFiles: List<HfModelFile>,
  ): HfModelFile? {
    if (!selected.path.endsWith(".gguf", true)) return null
    val quantization =
      Regex("(Q\\d[^._-]*(?:_[KMSL]+)?)", RegexOption.IGNORE_CASE)
        .find(selected.path.substringAfterLast('/'))
        ?.value
    return allFiles
      .filter { it.isProjector }
      .sortedByDescending { projector ->
        quantization != null && projector.path.contains(quantization, ignoreCase = true)
      }
      .firstOrNull()
  }

  internal fun safeFileName(path: String): String =
    path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)

  internal fun parameterLabel(repoId: String): String? =
    PARAMETER_COUNT
      .findAll(repoId.substringAfter('/'))
      .map { match -> "${match.groupValues[1]}${match.groupValues[2].uppercase()}" }
      .firstOrNull()
      ?: EFFECTIVE_PARAMETER_COUNT
        .find(repoId.substringAfter('/'))
        ?.groupValues
        ?.get(1)
        ?.uppercase()

  internal fun downloadableSizeRange(files: List<HfModelFile>): Pair<Long, Long>? {
    val sizes =
      files
        .filter(HfModelFile::isPrimaryChoice)
        .mapNotNull { choice ->
          val bundle = requiredFiles(choice, files) + listOfNotNull(companionProjector(choice, files))
          bundle
            .map(HfModelFile::sizeBytes)
            .takeIf { values -> values.isNotEmpty() && values.all { it != null } }
            ?.sumOf { it ?: 0L }
        }
        .distinct()
    return if (sizes.isEmpty()) null else sizes.minOrNull()!! to sizes.maxOrNull()!!
  }

  private fun getText(url: String, token: String?): HfResult<String> {
    val connection = (URL(url).openConnection() as HttpURLConnection).configured(token)
    return try {
      val status = connection.responseCode
      if (status in 200..299) {
        HfResult.Success(connection.inputStream.bufferedReader().use { it.readText() })
      } else {
        HfResult.Failure(statusMessage(status))
      }
    } catch (error: Throwable) {
      HfResult.Failure(error.message?.take(180) ?: "Could not reach Hugging Face.")
    } finally {
      connection.disconnect()
    }
  }

  private fun HttpURLConnection.configured(
    token: String?,
    download: Boolean = false,
  ): HttpURLConnection {
    requestMethod = "GET"
    instanceFollowRedirects = true
    connectTimeout = 15_000
    readTimeout = if (download) 60_000 else 30_000
    setRequestProperty("User-Agent", USER_AGENT)
    setRequestProperty("Accept", if (download) "application/octet-stream" else "application/json")
    token?.trim()?.takeIf(String::isNotEmpty)?.let {
      setRequestProperty("Authorization", "Bearer $it")
    }
    return this
  }

  private fun statusMessage(status: Int): String =
    when (status) {
      401 -> "The saved Hugging Face token was rejected."
      403 -> "Access denied. Accept the model terms on Hugging Face and try again."
      404 -> "The model repository or file was not found."
      429 -> "Hugging Face rate-limited this request. Try again shortly."
      else -> "Hugging Face request failed with HTTP $status."
    }

  private fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

  private fun encodePathSegment(value: String): String = encodeQuery(value)

  private val PARAMETER_COUNT =
    Regex("(?:^|[-_.])([0-9]+(?:\\.[0-9]+)?)([bm])(?:[-_.]|$)", RegexOption.IGNORE_CASE)
  private val EFFECTIVE_PARAMETER_COUNT =
    Regex("(?:^|[-_.])([ae][0-9]+(?:\\.[0-9]+)?[bm])(?:[-_.]|$)", RegexOption.IGNORE_CASE)

  private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
      value /= 1024.0
      index += 1
    }
    return "%.1f %s".format(value, units[index])
  }
}
