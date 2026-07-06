package com.terminus.edge.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.workflow.WorkflowDraft

@Composable
internal fun EdgeLightMenu(
  expanded: Boolean,
  onDismiss: () -> Unit,
  onNewConversation: () -> Unit,
  onNotes: () -> Unit,
  onWorkflows: () -> Unit,
  onHuggingFaceAccess: () -> Unit,
  onContext: () -> Unit,
  onSkills: () -> Unit,
  onMemories: () -> Unit,
  onReceipts: () -> Unit,
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    MenuLabel("Conversation")
    MenuAction("New conversation", onDismiss, onNewConversation)
    HorizontalDivider()
    MenuLabel("Workspace")
    MenuAction("Human notes", onDismiss, onNotes)
    MenuAction("Workflow drafts", onDismiss, onWorkflows)
    HorizontalDivider()
    MenuLabel("Model tools")
    MenuAction("Hugging Face access", onDismiss, onHuggingFaceAccess)
    MenuAction("Context & model settings", onDismiss, onContext)
    MenuAction("Skills", onDismiss, onSkills)
    MenuAction("Memories", onDismiss, onMemories)
    MenuAction("Receipts", onDismiss, onReceipts)
  }
}

@Composable
private fun MenuLabel(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
}

@Composable
private fun MenuAction(label: String, onDismiss: () -> Unit, action: () -> Unit) {
  DropdownMenuItem(
    text = { Text(label) },
    onClick = {
      onDismiss()
      action()
    },
  )
}

@Composable
internal fun HuggingFaceAccessDialog(
  storedToken: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var token by remember(storedToken) { mutableStateOf(storedToken) }
  val changed = token.trim() != storedToken
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Hugging Face access") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "The access token is encrypted and stored only in this app on this device.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Access token") },
          placeholder = { Text("hf_...") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
        )
        Text(
          if (storedToken.isBlank()) "No token saved." else "A token is currently saved.",
          style = MaterialTheme.typography.labelSmall,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(token)
          onDismiss()
        },
        enabled = changed,
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
internal fun HumanNotesDialog(
  notes: List<HumanNote>,
  onDismiss: () -> Unit,
  onSave: (String?, String, String) -> Unit,
  onArchive: (String) -> Unit,
) {
  var editing by remember { mutableStateOf<HumanNote?>(null) }
  var creating by remember { mutableStateOf(false) }

  if (creating || editing != null) {
    NoteEditorDialog(
      note = editing,
      onDismiss = {
        editing = null
        creating = false
      },
      onSave = { id, title, body ->
        onSave(id, title, body)
        editing = null
        creating = false
      },
    )
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Human notes") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Private notes for you. They are never added to model prompts.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (notes.isEmpty()) {
          Text("No notes yet.")
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(notes, key = HumanNote::id) { note ->
              Column(modifier = Modifier.fillMaxWidth()) {
                Text(note.title, fontWeight = FontWeight.SemiBold)
                Text(
                  note.body,
                  maxLines = 3,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  TextButton(onClick = { editing = note }) { Text("Edit") }
                  TextButton(onClick = { onArchive(note.id) }) { Text("Archive") }
                }
                HorizontalDivider()
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { creating = true }) { Text("New note") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun NoteEditorDialog(
  note: HumanNote?,
  onDismiss: () -> Unit,
  onSave: (String?, String, String) -> Unit,
) {
  var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
  var body by remember(note?.id) { mutableStateOf(note?.body.orEmpty()) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (note == null) "New note" else "Edit note") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Title") },
          singleLine = true,
        )
        OutlinedTextField(
          value = body,
          onValueChange = { body = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Note") },
          minLines = 6,
          maxLines = 14,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(note?.id, title, body) },
        enabled = body.isNotBlank(),
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
internal fun WorkflowDraftsDialog(
  drafts: List<WorkflowDraft>,
  onDismiss: () -> Unit,
  onSave: (String?, String, String, List<String>) -> Unit,
  onArchive: (String) -> Unit,
) {
  var editing by remember { mutableStateOf<WorkflowDraft?>(null) }
  var creating by remember { mutableStateOf(false) }

  if (creating || editing != null) {
    WorkflowEditorDialog(
      draft = editing,
      onDismiss = {
        editing = null
        creating = false
      },
      onSave = { id, name, goal, steps ->
        onSave(id, name, goal, steps)
        editing = null
        creating = false
      },
    )
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Workflow drafts") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Builder seam only: drafts stay local and do not execute automatically.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (drafts.isEmpty()) {
          Text("No workflow drafts yet.")
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(drafts, key = WorkflowDraft::id) { draft ->
              Column(modifier = Modifier.fillMaxWidth()) {
                Text(draft.name, fontWeight = FontWeight.SemiBold)
                Text(
                  draft.goal,
                  maxLines = 2,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
                )
                Text("${draft.steps.size} steps", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  TextButton(onClick = { editing = draft }) { Text("Edit") }
                  TextButton(onClick = { onArchive(draft.id) }) { Text("Archive") }
                }
                HorizontalDivider()
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { creating = true }) { Text("New workflow") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun WorkflowEditorDialog(
  draft: WorkflowDraft?,
  onDismiss: () -> Unit,
  onSave: (String?, String, String, List<String>) -> Unit,
) {
  var name by remember(draft?.id) { mutableStateOf(draft?.name.orEmpty()) }
  var goal by remember(draft?.id) { mutableStateOf(draft?.goal.orEmpty()) }
  var steps by remember(draft?.id) { mutableStateOf(draft?.steps?.joinToString("\n").orEmpty()) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (draft == null) "New workflow" else "Edit workflow") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Name") },
          singleLine = true,
        )
        OutlinedTextField(
          value = goal,
          onValueChange = { goal = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Goal") },
          minLines = 2,
        )
        OutlinedTextField(
          value = steps,
          onValueChange = { steps = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Steps, one per line") },
          minLines = 6,
          maxLines = 12,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(draft?.id, name, goal, steps.lineSequence().toList())
        },
        enabled = goal.isNotBlank() && steps.lineSequence().any { it.isNotBlank() },
      ) {
        Text("Save draft")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
