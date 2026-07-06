package com.terminus.edge.light

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.ledger.ConversationEntity
import com.terminus.edge.light.spine.SpineReadResult
import com.terminus.edge.light.spine.SpineRecordType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class WorkspaceDestination {
  CHAT,
  CONVERSATIONS,
  RUNTIME_SPINE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDrawer(
  destination: WorkspaceDestination,
  onDestination: (WorkspaceDestination) -> Unit,
  onNewConversation: () -> Unit,
  scope: CoroutineScope,
  content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  fun select(next: WorkspaceDestination) {
    onDestination(next)
    scope.launch { drawerState.close() }
  }
  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(
        drawerContainerColor = EdgeLightPalette.SurfaceBlack,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 22.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            "Workspace",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
          )
          NavigationDrawerItem(
            label = { Text("New conversation") },
            selected = false,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            onClick = {
              onNewConversation()
              select(WorkspaceDestination.CHAT)
            },
          )
          NavigationDrawerItem(
            label = { Text("Conversations") },
            selected = destination == WorkspaceDestination.CONVERSATIONS,
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
            onClick = { select(WorkspaceDestination.CONVERSATIONS) },
          )
          NavigationDrawerItem(
            label = { Text("Runtime Spine") },
            selected = destination == WorkspaceDestination.RUNTIME_SPINE,
            icon = { Icon(Icons.Rounded.AccountTree, contentDescription = null) },
            onClick = { select(WorkspaceDestination.RUNTIME_SPINE) },
          )
          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
          Text(
            "Runtime Spine stores local traces, failures, corrections, continuity, and provenance. Replay Packs are explicit exports.",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
  ) {
    content { scope.launch { drawerState.open() } }
  }
}

@Composable
fun WorkspaceMenuButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(Icons.Rounded.Menu, contentDescription = "Open conversations and Runtime Spine")
  }
}

@Composable
fun ConversationsScreen(
  conversations: List<ConversationEntity>,
  activeSessionId: String?,
  onOpen: (String) -> Unit,
  onArchive: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("Conversations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
      "Saved locally on this device.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    if (conversations.isEmpty()) {
      Text(
        "No saved conversations yet.",
        modifier = Modifier.padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(conversations, key = ConversationEntity::id) { conversation ->
          Card(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(conversation.id) },
            shape = RoundedCornerShape(16.dp),
            colors =
              CardDefaults.cardColors(
                containerColor =
                  if (conversation.id == activeSessionId) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surface
                  }
              ),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  conversation.title,
                  modifier = Modifier.weight(1f),
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onArchive(conversation.id) }) { Text("Archive") }
              }
              Text(
                conversation.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
              )
              Text(
                "${conversation.runtimeType.replace('_', ' ')} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conversation.updatedAt))}",
                color = EdgeLightPalette.Cyan,
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun RuntimeSpineScreen(
  result: SpineReadResult,
  onRefresh: () -> Unit,
  onArchive: () -> Unit,
) {
  var filter by remember { mutableStateOf<SpineRecordType?>(null) }
  var expandedId by remember { mutableStateOf<String?>(null) }
  val records = result.records.filter { filter == null || it.type == filter }
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text("Runtime Spine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "${result.records.size} records · ${result.legacyRecords} legacy · ${if (result.integrityValid) "integrity verified" else "integrity warning"}",
          color = if (result.integrityValid) EdgeLightPalette.Cyan else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      TextButton(onClick = onRefresh) { Text("Refresh") }
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
          FilterChip(
            selected = filter == SpineRecordType.TRACE,
            onClick = { filter = SpineRecordType.TRACE },
            label = { Text("Traces") },
          )
          FilterChip(
            selected = filter == SpineRecordType.FAILURE_TRACE,
            onClick = { filter = SpineRecordType.FAILURE_TRACE },
            label = { Text("Failures") },
          )
          FilterChip(
            selected = filter == SpineRecordType.CORRECTION_TRACE,
            onClick = { filter = SpineRecordType.CORRECTION_TRACE },
            label = { Text("Corrections") },
          )
          FilterChip(
            selected = filter == SpineRecordType.CONTINUITY_LOG,
            onClick = { filter = SpineRecordType.CONTINUITY_LOG },
            label = { Text("Continuity") },
          )
        }
      }
      items(records, key = { "${it.eventId}:${it.raw.hashCode()}" }) { record ->
        Card(
          modifier = Modifier.fillMaxWidth().clickable {
            expandedId = if (expandedId == record.eventId) null else record.eventId
          },
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(record.type.wireValue.replace('_', ' '), color = MaterialTheme.colorScheme.primary)
              Text(
                if (record.integrityValid) "verified" else "check",
                color = if (record.integrityValid) EdgeLightPalette.Cyan else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
              )
            }
            Text(
              record.payload.toString(),
              maxLines = if (expandedId == record.eventId) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
            if (expandedId == record.eventId) {
              Text(
                record.raw,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      }
      item {
        OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
          Text("Archive current Runtime Spine")
        }
      }
    }
  }
}
