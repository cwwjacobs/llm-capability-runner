package com.terminus.edge.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.model.ModelDescriptor
import java.io.File
import java.text.DecimalFormat

@Composable
internal fun DeviceModelsDialog(
  models: List<ModelDescriptor>,
  activeModelPath: String?,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onRefresh: () -> Unit,
  onImport: () -> Unit,
  onSelect: (File) -> Unit,
) {
  AlertDialog(
    onDismissRequest = { if (!isBusy) onDismiss() },
    title = { Text("Models on this device") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          "App-managed GGUF and LiteRT-LM files are shown here. External files appear after Import; every selection is validated before it replaces the active model.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (isBusy && models.isEmpty()) {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (models.isEmpty()) {
          Text("No usable app-managed models found.")
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(models, key = ModelDescriptor::path) { model ->
              val active = model.path == activeModelPath
              Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors =
                  CardDefaults.cardColors(
                    containerColor =
                      if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.surfaceVariant
                      }
                  ),
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      model.displayName,
                      modifier = Modifier.weight(1f).padding(end = 8.dp),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      fontWeight = FontWeight.SemiBold,
                    )
                    if (active) {
                      Text(
                        "Active",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                      )
                    }
                  }
                  Text(
                    buildString {
                      append(model.runtimeType.name.replace('_', '-'))
                      model.architecture?.let { append(" · $it") }
                      model.quantization?.let { append(" · $it") }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                  Text(
                    formatDeviceModelBytes(model.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                  )
                  if (!active) {
                    OutlinedButton(
                      onClick = { onSelect(File(model.path)) },
                      enabled = !isBusy,
                      modifier = Modifier.align(Alignment.End),
                    ) {
                      Text("Use model")
                    }
                  }
                }
              }
            }
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TextButton(onClick = onRefresh, enabled = !isBusy) { Text("Refresh") }
          TextButton(onClick = onImport, enabled = !isBusy) { Text("Import") }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Close") }
    },
  )
}

private fun formatDeviceModelBytes(bytes: Long): String {
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
