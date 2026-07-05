package com.terminus.edge.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.skill.SkillRecord

@Composable
internal fun SkillLibraryDialog(
  skills: List<SkillRecord>,
  selectedIds: Set<String>,
  onDismiss: () -> Unit,
  onToggle: (String) -> Unit,
  onImport: () -> Unit,
  onAdd: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var detail by remember { mutableStateOf<SkillRecord?>(null) }
  val filtered =
    remember(skills, query) {
      val term = query.trim().lowercase()
      if (term.isEmpty()) skills
      else
        skills.filter {
          "${it.name} ${it.description} ${it.instructions}".lowercase().contains(term)
        }
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Skills") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Local SKILL.md guidance. Skills do not execute tools or grant permissions.",
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Search Skills") },
          singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onImport) { Text("Import SKILL.md") }
          GradientPillButton(text = "Make a Skill", onClick = onAdd)
        }
        Text(
          "${selectedIds.size} attached | ${skills.size} local",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelMedium,
        )
        if (filtered.isEmpty()) {
          Text("No Skills match.")
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filtered, key = SkillRecord::id) { record ->
              Card(modifier = Modifier.fillMaxWidth(), border = edgeLightBorder()) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Checkbox(
                    checked = record.id in selectedIds,
                    onCheckedChange = { onToggle(record.id) },
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(record.name, style = MaterialTheme.typography.titleSmall)
                    Text(record.description, style = MaterialTheme.typography.bodySmall)
                  }
                  TextButton(onClick = { detail = record }) { Text("View") }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )

  detail?.let { record ->
    AlertDialog(
      onDismissRequest = { detail = null },
      title = { Text(record.name) },
      text = {
        Column(
          modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(record.description, color = MaterialTheme.colorScheme.primary)
          Text(record.instructions)
        }
      },
      confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
    )
  }
}

@Composable
internal fun AddSkillDialog(
  onDismiss: () -> Unit,
  onCreate: (name: String, description: String, instructions: String) -> Boolean,
) {
  var name by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var instructions by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Make a Skill") },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "Create reusable local guidance. A Skill shapes responses but cannot execute tools or grant permission.",
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Name") },
          singleLine = true,
        )
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Description") },
        )
        OutlinedTextField(
          value = instructions,
          onValueChange = { instructions = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Instructions") },
          minLines = 10,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (onCreate(name, description, instructions)) onDismiss()
        },
        enabled = name.isNotBlank() && description.isNotBlank() && instructions.isNotBlank(),
      ) {
        Text("Create Skill")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
