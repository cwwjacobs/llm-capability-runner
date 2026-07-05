package com.terminus.edge.light.memory

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MemoryDeckDialog(
  memories: List<MemoryRecord>,
  selectedIds: Set<String>,
  onDismiss: () -> Unit,
  onToggle: (String) -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Memory Deck") },
    text = {
      if (memories.isEmpty()) {
        Text(
          "No memories found in the vault.",
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(memories, key = { it.id }) { memory ->
            val isSelected = selectedIds.contains(memory.id)
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(memory.id) },
              shape = RoundedCornerShape(8.dp),
              colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
              )
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Checkbox(
                  checked = isSelected,
                  onCheckedChange = { onToggle(memory.id) }
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                  Text(
                    text = memory.name,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                  )
                  if (memory.tags.isNotEmpty()) {
                    Text(
                      text = memory.tags.joinToString(", "),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }
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
