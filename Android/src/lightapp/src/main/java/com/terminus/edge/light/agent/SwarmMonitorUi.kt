package com.terminus.edge.light.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.inference.AgentRole
import com.terminus.edge.light.inference.AgentState
import com.terminus.edge.light.inference.AgentStatus
import kotlinx.coroutines.flow.StateFlow
import java.text.DecimalFormat

@Composable
fun SwarmMonitorUi(
  agentStatesFlow: StateFlow<Map<AgentRole, AgentState>>,
  onDismiss: () -> Unit
) {
  val agentStates by agentStatesFlow.collectAsState()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = "Swarm Monitor",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary
    )
    
    val totalMemoryBytes = agentStates.values.sumOf { it.sizeBytes }
    Text(
      text = "Total Active VRAM: ${formatBytes(totalMemoryBytes)}",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    LazyColumn(
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(AgentRole.values()) { role ->
        val state = agentStates[role] ?: AgentState(role, AgentStatus.OFFLINE)
        SwarmNodeCard(state)
      }
    }
    
    Button(
      onClick = onDismiss,
      modifier = Modifier.align(Alignment.End)
    ) {
      Text("Close")
    }
  }
}

@Composable
private fun SwarmNodeCard(state: AgentState) {
  val statusColor = when (state.status) {
    AgentStatus.IDLE -> Color(0xFF4CAF50) // Green
    AgentStatus.GENERATING -> Color(0xFF2196F3) // Blue
    AgentStatus.LOADING -> Color(0xFFFF9800) // Orange
    AgentStatus.ERROR -> Color(0xFFF44336) // Red
    AgentStatus.OFFLINE -> Color.Gray
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    )
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = state.role.name.replace("_", " "),
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium
        )
        if (state.status != AgentStatus.OFFLINE) {
          Text(
            text = state.modelName ?: "Unknown Model",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "RAM: ${formatBytes(state.sizeBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Box(
          modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(statusColor)
        )
        Text(
          text = state.status.name,
          style = MaterialTheme.typography.labelMedium,
          color = statusColor,
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
  return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}
