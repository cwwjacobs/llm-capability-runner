package com.terminus.edge.light.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PersonaSelector(
  personas: List<Persona>,
  activePersonaId: String?,
  onSelect: (String?) -> Unit,
  modifier: Modifier = Modifier
) {
  var expanded by remember { mutableStateOf(false) }
  val activePersona = personas.firstOrNull { it.id == activePersonaId }

  Box(modifier = modifier.wrapContentSize(Alignment.TopEnd)) {
    Text(
      text = "Personas",
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier
        .clickable { expanded = true }
        .padding(8.dp)
    )

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false }
    ) {
      DropdownMenuItem(
        text = { Text("No Persona", fontWeight = if (activePersonaId == null) FontWeight.Bold else null) },
        onClick = {
          onSelect(null)
          expanded = false
        }
      )
      personas.forEach { persona ->
        DropdownMenuItem(
          text = { Text(persona.name, fontWeight = if (persona.id == activePersonaId) FontWeight.Bold else null) },
          onClick = {
            onSelect(persona.id)
            expanded = false
          }
        )
      }
    }
  }
}
