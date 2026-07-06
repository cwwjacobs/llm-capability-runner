package archived.legacy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.trace.TraceStats

@Composable
internal fun ReceiptsDialog(
  traceEnabled: Boolean,
  traceStats: TraceStats,
  modelLabel: String,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onTraceChange: (Boolean) -> Unit,
  onExportRaw: () -> Unit,
  onExportCurated: () -> Unit,
  onExportReplay: () -> Unit,
  onDeleteTraces: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Receipts") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ReceiptStatus(
          label = "Capture",
          value = if (traceEnabled) "On" else "Off",
          emphasized = traceEnabled,
        )
        ReceiptStatus(
          label = "Integrity",
          value = traceStats.integrityStatus.replace('_', ' '),
          isError = traceStats.integrityStatus == "failed",
        )
        Text(
          "${traceStats.eventCount} events | ${traceStats.completed} complete | ${traceStats.unreviewed} unreviewed",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          "${traceStats.failed} failed | ${traceStats.cancelled} cancelled | ${traceStats.reviewed} reviewed",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          modelLabel,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )

        GradientPillButton(
          text = if (traceEnabled) "Pause capture" else "Enable capture",
          onClick = { onTraceChange(!traceEnabled) },
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        Text(
          "Export",
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          TextButton(onClick = onExportRaw, enabled = !isBusy && traceStats.eventCount > 0) {
            Text("Raw")
          }
          TextButton(onClick = onExportCurated, enabled = !isBusy && traceStats.completed > 0) {
            Text("Curated")
          }
          TextButton(
            onClick = onExportReplay,
            enabled = !isBusy && traceStats.inferenceAttempts > 0,
          ) {
            Text("Replay ZIP")
          }
        }
        Text(
          "Replay includes referenced context artifacts and exact model snapshots. Exported files contain plaintext prompts and responses.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        TextButton(
          onClick = onDeleteTraces,
          enabled = !isBusy && traceStats.eventCount > 0,
          modifier = Modifier.padding(start = 0.dp),
        ) {
          Text("Delete local receipts", color = MaterialTheme.colorScheme.error)
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun ReceiptStatus(
  label: String,
  value: String,
  emphasized: Boolean = false,
  isError: Boolean = false,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(
      value,
      color =
        when {
          isError -> MaterialTheme.colorScheme.error
          emphasized -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
      fontWeight = if (emphasized || isError) FontWeight.SemiBold else FontWeight.Normal,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}
