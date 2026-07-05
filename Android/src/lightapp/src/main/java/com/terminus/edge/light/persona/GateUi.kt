package com.terminus.edge.light.persona

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
fun GateWarningDialog(
  capabilityName: String,
  onAllow: () -> Unit,
  onDeny: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDeny,
    title = { 
      Text(
        text = "Operator Approval Required",
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Text("The active Persona is attempting to use the capability '$capabilityName', which requires your explicit approval. Do you want to allow this action?")
    },
    confirmButton = {
      TextButton(onClick = onAllow) {
        Text("Allow Once")
      }
    },
    dismissButton = {
      TextButton(onClick = onDeny) {
        Text("Deny")
      }
    }
  )
}
