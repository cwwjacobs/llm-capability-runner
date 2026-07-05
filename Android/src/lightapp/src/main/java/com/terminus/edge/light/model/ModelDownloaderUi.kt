package com.terminus.edge.light.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults

@Composable
fun ModelDownloaderDialog(
  onDismiss: () -> Unit,
  onModelDownloaded: (File) -> Unit,
  downloadDir: File,
  hfToken: String?,
  onTokenChanged: (String) -> Unit,
  onSignInClicked: () -> Unit
) {
  var query by remember { mutableStateOf("gemma") }
  var models by remember { mutableStateOf<List<HfModelInfo>>(emptyList()) }
  var selectedRepoId by remember { mutableStateOf<String?>(null) }
  var modelFiles by remember { mutableStateOf<List<String>>(emptyList()) }
  var isSearching by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf<Float?>(null) }
  val scope = rememberCoroutineScope()

  @OptIn(ExperimentalMaterial3Api::class)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    modifier = Modifier.fillMaxWidth()
  ) {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text("Download Model from Hugging Face", style = MaterialTheme.typography.titleLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = hfToken ?: "",
            onValueChange = onTokenChanged,
            label = { Text("HF Access Token (Optional)") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
          )
          Button(onClick = onSignInClicked) {
            Text("Sign In")
          }
        }
        if (downloadProgress != null) {
          Text("Downloading...")
          LinearProgressIndicator(
            progress = { downloadProgress ?: 0f },
            modifier = Modifier.fillMaxWidth()
          )
        } else if (selectedRepoId != null) {
          Text("Files for ${selectedRepoId}", fontWeight = FontWeight.SemiBold)
          if (modelFiles.isEmpty()) {
            Text("Loading files...")
            LaunchedEffect(selectedRepoId) {
              modelFiles = HuggingFaceClient.listModelFiles(selectedRepoId!!, hfToken)
            }
          } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              items(modelFiles) { file ->
                Card(
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(file, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Button(onClick = {
                      val destFile = File(downloadDir, file.substringAfterLast('/'))
                      scope.launch {
                        downloadProgress = 0f
                        val success = HuggingFaceClient.downloadFile(selectedRepoId!!, file, destFile, hfToken) { progress ->
                          downloadProgress = progress
                        }
                        if (success) {
                          onModelDownloaded(destFile)
                        } else {
                          downloadProgress = null
                        }
                      }
                    }) {
                      Text("Download")
                    }
                  }
                }
              }
            }
          }
          TextButton(onClick = { selectedRepoId = null; modelFiles = emptyList() }) {
            Text("Back")
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              label = { Text("Search repo") },
              modifier = Modifier.weight(1f),
              singleLine = true
            )
            Button(onClick = {
              scope.launch {
                isSearching = true
                models = HuggingFaceClient.listModels(query, hfToken)
                isSearching = false
              }
            }, enabled = !isSearching) {
              Text("Search")
            }
          }
          if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
          } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              items(models) { model ->
                Card(
                  modifier = Modifier.fillMaxWidth(),
                  onClick = { selectedRepoId = model.id }
                ) {
                  Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(model.id, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Text("${model.downloads} dl", style = MaterialTheme.typography.labelMedium)
                  }
                }
              }
            }
          }
        }
        if (downloadProgress == null) {
          TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
      }
  }
}
