package com.terminus.edge.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat

@Composable
fun StorageUiDialog(
  archiveSize: Long,
  blobStoreSize: Long,
  onClearArchives: () -> Unit,
  onClearBlobs: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Storage & Traces") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Column {
          Text(
            text = "Conversation Archives",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "Size: ${formatBytes(archiveSize)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          TextButton(onClick = onClearArchives, modifier = Modifier.padding(top = 4.dp)) {
            Text("Clear Archives")
          }
        }
        
        Column {
          Text(
            text = "Media Blobs (Images)",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "Size: ${formatBytes(blobStoreSize)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          TextButton(onClick = onClearBlobs, modifier = Modifier.padding(top = 4.dp)) {
            Text("Clear Media")
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done")
      }
    }
  )
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB")
  var value = bytes.toDouble()
  var index = -1
  while (value >= 1024 && index < units.lastIndex) {
    value /= 1024
    index += 1
  }
  return "${DecimalFormat("0.0").format(value)} ${units[index]}"
}
