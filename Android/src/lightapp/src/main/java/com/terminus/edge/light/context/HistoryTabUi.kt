package com.terminus.edge.light.context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment

@Composable
fun HistoryTabUi(
  sessionIds: List<String>,
  activeSessionId: String?,
  onLoadConversation: (String) -> Unit,
  onNewConversation: () -> Unit,
  onManageMemories: () -> Unit,
  onManageSkills: () -> Unit,
  onViewReceipts: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text("History & Context", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    
    // Top 30% for Receipts and Memories
    Column(modifier = Modifier.fillMaxWidth().weight(0.3f)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onManageMemories, modifier = Modifier.weight(1f)) {
          Text("Memories")
        }
        Button(onClick = onViewReceipts, modifier = Modifier.weight(1f)) {
          Text("Receipts")
        }
      }
      Spacer(Modifier.height(8.dp))
      Button(onClick = onManageSkills, modifier = Modifier.fillMaxWidth()) {
        Text("Skills Library")
      }
    }
    
    HorizontalDivider()
    
    // Bottom 70% for Conversations
    Column(modifier = Modifier.fillMaxWidth().weight(0.7f)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Conversations", style = MaterialTheme.typography.titleMedium)
        androidx.compose.material3.TextButton(onClick = onNewConversation) { Text("New") }
      }
      
      LazyColumn(modifier = Modifier.weight(1f)) {
        items(sessionIds, key = { it }) { sessionId ->
          androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().clickable { onLoadConversation(sessionId) }.padding(vertical = 4.dp),
            color = if (sessionId == activeSessionId) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
          ) {
            Text(
              "Session: ${sessionId.take(8)}...",
              modifier = Modifier.padding(12.dp),
              color = if (sessionId == activeSessionId) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
          }
        }
      }
    }
  }
}
