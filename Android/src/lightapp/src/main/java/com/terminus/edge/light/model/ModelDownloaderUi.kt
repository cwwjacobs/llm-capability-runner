package com.terminus.edge.light.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DecimalFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class DownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long?,
)

data class DownloadedModel(
  val modelFile: File,
  val projectorFile: File?,
  val repositoryId: String,
  val revision: String = "main",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloaderDialog(
  onDismiss: () -> Unit,
  onModelDownloaded: (DownloadedModel) -> Unit,
  downloadDir: File,
  hfToken: String?,
) {
  var query by remember { mutableStateOf("gemma") }
  var models by remember { mutableStateOf<List<HfModelInfo>>(emptyList()) }
  var selectedRepoId by remember { mutableStateOf<String?>(null) }
  var expandedRepoId by remember { mutableStateOf<String?>(null) }
  var expandedFilePath by remember { mutableStateOf<String?>(null) }
  var modelFiles by remember { mutableStateOf<List<HfModelFile>>(emptyList()) }
  var isSearching by remember { mutableStateOf(false) }
  var isLoadingFiles by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
  var downloadJob by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()
  val savedToken = hfToken?.trim().orEmpty()

  fun search() {
    if (query.isBlank() || isSearching) return
    scope.launch {
      isSearching = true
      errorMessage = null
      when (val result = HuggingFaceClient.listModels(query, savedToken)) {
        is HfResult.Success -> {
          models = result.value
          if (models.isEmpty()) errorMessage = "No compatible GGUF or LiteRT-LM repositories found."
        }
        is HfResult.Failure -> errorMessage = result.message
      }
      isSearching = false
    }
  }

  ModalBottomSheet(
    onDismissRequest = { if (downloadJob == null) onDismiss() },
    dragHandle = { BottomSheetDefaults.DragHandle() },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth().heightIn(max = 760.dp).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Hugging Face models", style = MaterialTheme.typography.titleLarge)
      Text(
        if (savedToken.isEmpty()) {
          "Public repositories only. Save a read token in Settings for gated models."
        } else {
          "Using the encrypted read token saved in Edge Light."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )

      downloadProgress?.let { progress ->
        val fraction =
          progress.totalBytes
            ?.takeIf { it > 0L }
            ?.let { (progress.downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
        Text(
          if (progress.totalBytes == null) {
            "Downloaded ${formatBytes(progress.downloadedBytes)}"
          } else {
            "${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes)}"
          }
        )
        if (fraction == null) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
          LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
          "Keep Edge Light open until the model is downloaded and validated.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = { downloadJob?.cancel() }) { Text("Cancel download") }
      }

      errorMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      when {
        downloadProgress != null -> Unit
        selectedRepoId != null -> {
          Text(selectedRepoId.orEmpty(), fontWeight = FontWeight.SemiBold)
          if (isLoadingFiles) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(modelFiles.filter(HfModelFile::isPrimaryChoice), key = HfModelFile::path) { modelFile ->
                val requiredModelFiles = HuggingFaceClient.requiredFiles(modelFile, modelFiles)
                val projector = HuggingFaceClient.companionProjector(modelFile, modelFiles)
                val bundleSize =
                  (requiredModelFiles + listOfNotNull(projector))
                    .map(HfModelFile::sizeBytes)
                    .takeIf { sizes -> sizes.all { it != null } }
                    ?.sumOf { it ?: 0L }
                val fileExpanded = expandedFilePath == modelFile.path
                Card(
                  colors =
                    CardDefaults.cardColors(
                      containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(
                      modelFile.path.substringAfterLast('/'),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      fontWeight = FontWeight.SemiBold,
                      style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                      buildString {
                        append(bundleSize?.let(::formatBytes) ?: "Size unavailable")
                        if (requiredModelFiles.size > 1) append(" · ${requiredModelFiles.size} shards")
                        if (projector != null) append(" · vision projector")
                      },
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.labelSmall,
                    )
                    if (fileExpanded) {
                      Text(
                        modelFile.path,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                      )
                      Text(
                        if (modelFile.isHardwareSpecific) "Hardware-specific build" else "General build",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                      )
                    }
                    TextButton(
                      onClick = {
                        expandedFilePath = if (fileExpanded) null else modelFile.path
                      }
                    ) {
                      Text(if (fileExpanded) "Less" else "Full name")
                    }
                    Button(
                      onClick = {
                        val repoId = selectedRepoId ?: return@Button
                        val downloadFiles = requiredModelFiles + listOfNotNull(projector)
                        val repoDir =
                          File(downloadDir, repoId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                        val requiredBytes =
                          downloadFiles.map(HfModelFile::sizeBytes).takeIf { sizes ->
                            sizes.all { it != null }
                          }?.sumOf { it ?: 0L }
                        if (
                          requiredBytes != null &&
                            downloadDir.usableSpace < requiredBytes + 128L * 1024L * 1024L
                        ) {
                          errorMessage =
                            "Not enough free app storage. This model needs about ${formatBytes(requiredBytes)}."
                          return@Button
                        }
                        downloadJob =
                          scope.launch {
                            errorMessage = null
                            downloadProgress = DownloadProgress(0L, requiredBytes?.takeIf { it > 0L })
                            try {
                              var completedBytes = 0L
                              val downloaded = mutableMapOf<String, File>()
                              for (fileToDownload in downloadFiles) {
                                val destination =
                                  File(repoDir, HuggingFaceClient.safeFileName(fileToDownload.path))
                                when (
                                  val result =
                                    HuggingFaceClient.downloadFile(
                                      repoId = repoId,
                                      modelFile = fileToDownload,
                                      destination = destination,
                                      token = savedToken,
                                    ) { current, _ ->
                                      scope.launch {
                                        downloadProgress =
                                          DownloadProgress(
                                            completedBytes + current,
                                            requiredBytes?.takeIf { it > 0L },
                                          )
                                      }
                                    }
                                ) {
                                  is HfResult.Success -> {
                                    downloaded[fileToDownload.path] = result.value
                                    completedBytes += result.value.length()
                                  }
                                  is HfResult.Failure -> error(result.message)
                                }
                              }
                              onModelDownloaded(
                                DownloadedModel(
                                  modelFile = requireNotNull(downloaded[requiredModelFiles.first().path]),
                                  projectorFile = projector?.let { downloaded[it.path] },
                                  repositoryId = repoId,
                                )
                              )
                            } catch (_: CancellationException) {
                              errorMessage = "Download cancelled. The partial file was retained."
                            } catch (error: Throwable) {
                              errorMessage = error.message ?: "Model download failed."
                            } finally {
                              downloadProgress = null
                              downloadJob = null
                            }
                          }
                      },
                      modifier = Modifier.align(Alignment.End),
                    ) {
                      Text("Download and load")
                    }
                  }
                }
              }
            }
          }
          TextButton(
            onClick = {
              selectedRepoId = null
              modelFiles = emptyList()
              errorMessage = null
            }
          ) {
            Text("Back to repositories")
          }
        }
        else -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              label = { Text("Search compatible models") },
              modifier = Modifier.weight(1f),
              singleLine = true,
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
              keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            Button(onClick = ::search, enabled = query.isNotBlank() && !isSearching) {
              Text("Search")
            }
          }
          if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(models, key = HfModelInfo::id) { model ->
                val expanded = expandedRepoId == model.id
                Card(
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Text(
                      displayModelName(model.id),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      model.id,
                      maxLines = if (expanded) 4 else 1,
                      overflow = TextOverflow.Ellipsis,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                      Text(
                        "Parameters  ${model.parameterLabel ?: "Unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                      )
                      Text(
                        "Size  ${formatModelSize(model)}",
                        style = MaterialTheme.typography.labelSmall,
                      )
                    }
                    if (expanded) {
                      Text(
                        "${model.downloads} downloads · GGUF or LiteRT-LM builds",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                      )
                    }
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      TextButton(
                        onClick = { expandedRepoId = if (expanded) null else model.id }
                      ) {
                        Text(if (expanded) "Less" else "Details")
                      }
                      Button(
                        onClick = {
                          selectedRepoId = model.id
                          modelFiles = emptyList()
                          expandedFilePath = null
                          isLoadingFiles = true
                          errorMessage = null
                          scope.launch {
                            when (val result = HuggingFaceClient.listModelFiles(model.id, savedToken)) {
                              is HfResult.Success -> modelFiles = result.value
                              is HfResult.Failure -> errorMessage = result.message
                            }
                            isLoadingFiles = false
                          }
                        }
                      ) {
                        Text("View builds")
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      if (downloadJob == null) {
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
      }
    }
  }
}

private fun displayModelName(repoId: String): String =
  repoId
    .substringAfter('/')
    .replace(Regex("(?i)[_-](gguf|litert[-_]?lm)$"), "")
    .replace('_', ' ')

private fun formatModelSize(model: HfModelInfo): String {
  val minimum = model.minimumDownloadBytes ?: return "Unknown"
  val maximum = model.maximumDownloadBytes
  return if (maximum == null || maximum == minimum) {
    formatBytes(minimum)
  } else {
    "from ${formatBytes(minimum)}"
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB")
  var value = bytes.toDouble()
  var index = -1
  while (value >= 1024.0 && index < units.lastIndex) {
    value /= 1024.0
    index += 1
  }
  return "${DecimalFormat("0.0").format(value)} ${units[index]}"
}
