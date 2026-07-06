package com.terminus.edge.light.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import com.terminus.edge.light.inference.AgentRole

enum class ModelRuntimeType {
  LITERT_LM,
  GGUF,
}

data class ModelDescriptor(
  val displayName: String,
  val path: String,
  val sha256: String,
  val sizeBytes: Long,
  val importedAtMs: Long,
  val runtimeType: ModelRuntimeType =
    if (path.endsWith(".gguf", ignoreCase = true)) ModelRuntimeType.GGUF else ModelRuntimeType.LITERT_LM,
  val architecture: String? = null,
  val quantization: String? = null,
  val supportedContextTokens: Int? = null,
  val visionProjectorPath: String? = null,
  val visionProjectorSha256: String? = null,
  val sourceRepository: String? = null,
  val sourceRevision: String? = null,
)

data class StagedModel(
  val descriptor: ModelDescriptor,
  val file: File,
)

class ModelStore(private val context: Context) {
  private val preferences = context.getSharedPreferences("edge_light_model", Context.MODE_PRIVATE)
  private val modelDirectory = File(context.filesDir, "models")
  private val modelFile = File(modelDirectory, "model.litertlm")

  fun current(role: AgentRole = AgentRole.ORCHESTRATOR): ModelDescriptor? {
    val suffix = if (role == AgentRole.ORCHESTRATOR) "" else "_${role.name}"
    val defaultPath = if (role == AgentRole.ORCHESTRATOR) modelFile.absolutePath else null
    val path = preferences.getString(KEY_PATH + suffix, defaultPath) ?: return null
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    val name = preferences.getString(KEY_NAME + suffix, null) ?: return null
    val sha256 = preferences.getString(KEY_SHA256 + suffix, null) ?: return null
    return ModelDescriptor(
      displayName = name,
      path = file.absolutePath,
      sha256 = sha256,
      sizeBytes = file.length(),
      importedAtMs = preferences.getLong(KEY_IMPORTED_AT + suffix, 0L),
      runtimeType =
        preferences.getString(KEY_RUNTIME + suffix, null)
          ?.let { runCatching { ModelRuntimeType.valueOf(it) }.getOrNull() }
          ?: if (file.extension.equals("gguf", true)) ModelRuntimeType.GGUF else ModelRuntimeType.LITERT_LM,
      architecture = preferences.getString(KEY_ARCHITECTURE + suffix, null),
      quantization = preferences.getString(KEY_QUANTIZATION + suffix, null),
      supportedContextTokens =
        preferences.getInt(KEY_CONTEXT_TOKENS + suffix, 0).takeIf { it > 0 },
      visionProjectorPath = preferences.getString(KEY_PROJECTOR_PATH + suffix, null),
      visionProjectorSha256 = preferences.getString(KEY_PROJECTOR_SHA + suffix, null),
      sourceRepository = preferences.getString(KEY_SOURCE_REPOSITORY + suffix, null),
      sourceRevision = preferences.getString(KEY_SOURCE_REVISION + suffix, null),
    )
  }

  fun scanStorage(): List<ModelDescriptor> {
    if (!modelDirectory.exists()) return emptyList()
    val active = current()
    return modelDirectory
      .walkTopDown()
      .maxDepth(4)
      .filter { file ->
        file.isFile &&
          file.length() > 0L &&
          isRunnableModelFile(file)
      }
      .map { file ->
        active?.takeIf { it.path == file.absolutePath }
          ?: run {
            val gguf = if (file.extension.equals("gguf", true)) GgufMetadataProbe.read(file) else null
            ModelDescriptor(
              displayName = file.name,
              path = file.absolutePath,
              sha256 = "",
              sizeBytes = file.length(),
              importedAtMs = file.lastModified(),
              architecture = gguf?.architecture,
              quantization = gguf?.quantization,
              supportedContextTokens = gguf?.contextTokens,
            )
          }
      }
      .distinctBy(ModelDescriptor::path)
      .sortedWith(
        compareByDescending<ModelDescriptor> { it.path == active?.path }
          .thenBy { it.displayName.lowercase() }
      )
      .toList()
  }

  fun commitExternal(
    file: File,
    role: AgentRole = AgentRole.ORCHESTRATOR,
    projector: File? = null,
    sourceRepository: String? = null,
    sourceRevision: String? = null,
    persist: Boolean = true,
  ): ModelDescriptor {
    require(file.isFile && file.length() > 0L) { "Selected file is invalid or empty." }
    
    // Compute SHA-256 lazily upon selection
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    file.inputStream().use { input ->
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    val sha256Str = digest.digest().joinToString("") { byte -> "%02x".format(byte) }

    val projectorSha =
      projector?.takeIf(File::isFile)?.let { projectorFile ->
        val projectorDigest = MessageDigest.getInstance("SHA-256")
        projectorFile.inputStream().use { input ->
          val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            if (count > 0) projectorDigest.update(bytes, 0, count)
          }
        }
        projectorDigest.digest().joinToString("") { byte -> "%02x".format(byte) }
      }
    val gguf = if (file.extension.equals("gguf", true)) GgufMetadataProbe.read(file) else null
    val descriptor = ModelDescriptor(
      displayName = file.name,
      path = file.absolutePath,
      sha256 = sha256Str,
      sizeBytes = file.length(),
      importedAtMs = System.currentTimeMillis(),
      visionProjectorPath = projector?.absolutePath,
      visionProjectorSha256 = projectorSha,
      sourceRepository = sourceRepository,
      sourceRevision = sourceRevision,
      architecture = gguf?.architecture,
      quantization = gguf?.quantization,
      supportedContextTokens = gguf?.contextTokens,
    )

    if (persist) persistExternal(descriptor, role)
    return descriptor
  }

  fun persistExternal(
    descriptor: ModelDescriptor,
    role: AgentRole = AgentRole.ORCHESTRATOR,
  ) {
    val suffix = if (role == AgentRole.ORCHESTRATOR) "" else "_${role.name}"
    val saved =
      preferences
        .edit()
        .putString(KEY_NAME + suffix, descriptor.displayName)
        .putString(KEY_PATH + suffix, descriptor.path)
        .putString(KEY_SHA256 + suffix, descriptor.sha256)
        .putLong(KEY_IMPORTED_AT + suffix, descriptor.importedAtMs)
        .putString(KEY_RUNTIME + suffix, descriptor.runtimeType.name)
        .putString(KEY_PROJECTOR_PATH + suffix, descriptor.visionProjectorPath)
        .putString(KEY_PROJECTOR_SHA + suffix, descriptor.visionProjectorSha256)
        .putString(KEY_SOURCE_REPOSITORY + suffix, descriptor.sourceRepository)
        .putString(KEY_SOURCE_REVISION + suffix, descriptor.sourceRevision)
        .putString(KEY_ARCHITECTURE + suffix, descriptor.architecture)
        .putString(KEY_QUANTIZATION + suffix, descriptor.quantization)
        .putInt(KEY_CONTEXT_TOKENS + suffix, descriptor.supportedContextTokens ?: 0)
        .commit()
    require(saved) { "The model metadata could not be saved." }
  }

  fun stage(uri: Uri): StagedModel {
    modelDirectory.mkdirs()
    val displayName = queryDisplayName(uri) ?: "Imported model"
    val extension =
      displayName.substringAfterLast('.', "").lowercase().takeIf { it in setOf("gguf", "litertlm") }
        ?: throw IllegalArgumentException("Choose a .gguf or .litertlm model file.")
    val tempFile = File(modelDirectory, "model.importing.$extension")
    tempFile.delete()
    val digest = MessageDigest.getInstance("SHA-256")

    try {
      context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to open the selected model." }
        FileOutputStream(tempFile, false).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
          }
          output.fd.sync()
        }
      }

      require(tempFile.length() > 0L) { "The selected model is empty." }
      val gguf = if (extension == "gguf") GgufMetadataProbe.read(tempFile) else null
      return StagedModel(
        descriptor =
          ModelDescriptor(
            displayName = displayName,
            path = tempFile.absolutePath,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            sizeBytes = tempFile.length(),
            importedAtMs = System.currentTimeMillis(),
            architecture = gguf?.architecture,
            quantization = gguf?.quantization,
            supportedContextTokens = gguf?.contextTokens,
          ),
        file = tempFile,
      )
    } catch (error: Throwable) {
      tempFile.delete()
      throw error
    }
  }

  fun commit(staged: StagedModel, role: AgentRole = AgentRole.ORCHESTRATOR): ModelDescriptor {
    require(staged.file.parentFile?.canonicalFile == modelDirectory.canonicalFile &&
      staged.file.name.startsWith("model.importing.")) {
      "Invalid staged model path."
    }
    require(staged.file.isFile && staged.file.length() == staged.descriptor.sizeBytes) {
      "The staged model is unavailable or changed."
    }
    
    val extension = staged.descriptor.displayName.substringAfterLast('.', "litertlm").lowercase()
    val targetFile =
      if (role == AgentRole.ORCHESTRATOR) {
        File(modelDirectory, "model.$extension")
      } else {
        File(modelDirectory, "model_${role.name}.$extension")
      }
    moveIntoPlace(staged.file, targetFile)
    val descriptor =
      staged.descriptor.copy(path = targetFile.absolutePath, sizeBytes = targetFile.length())
      
    persistExternal(descriptor, role)
    return descriptor
  }

  fun discard(staged: StagedModel) {
    if (staged.file.name.startsWith("model.importing.")) staged.file.delete()
  }

  private fun queryDisplayName(uri: Uri): String? {
    return context.contentResolver
      .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)?.take(160)
      }
  }

  private fun moveIntoPlace(source: File, target: File) {
    try {
      Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: Exception) {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private companion object {
    const val KEY_NAME = "name"
    const val KEY_PATH = "path"
    const val KEY_SHA256 = "sha256"
    const val KEY_IMPORTED_AT = "imported_at"
    const val KEY_RUNTIME = "runtime"
    const val KEY_ARCHITECTURE = "architecture"
    const val KEY_QUANTIZATION = "quantization"
    const val KEY_CONTEXT_TOKENS = "context_tokens"
    const val KEY_PROJECTOR_PATH = "projector_path"
    const val KEY_PROJECTOR_SHA = "projector_sha"
    const val KEY_SOURCE_REPOSITORY = "source_repository"
    const val KEY_SOURCE_REVISION = "source_revision"
  }
}

internal fun isRunnableModelFile(file: File): Boolean =
  !file.name.startsWith("mmproj", ignoreCase = true) &&
    (file.extension.equals("gguf", true) || file.extension.equals("litertlm", true))
