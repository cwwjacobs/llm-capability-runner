package com.terminus.edge.light

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.inference.ApiProviderConfiguration
import com.terminus.edge.light.inference.InferenceBackend

@Composable
internal fun ApiProvidersDialog(
  configuration: ApiProviderConfiguration,
  storedGeminiKey: String,
  storedDeepSeekKey: String,
  onDismiss: () -> Unit,
  onSave: (ApiProviderConfiguration, String, String) -> Boolean,
) {
  var backend by remember(configuration) { mutableStateOf(configuration.backend) }
  var geminiModel by remember(configuration) { mutableStateOf(configuration.geminiModel) }
  var deepSeekModel by remember(configuration) { mutableStateOf(configuration.deepSeekModel) }
  var geminiKey by remember(storedGeminiKey) { mutableStateOf(storedGeminiKey) }
  var deepSeekKey by remember(storedDeepSeekKey) { mutableStateOf(storedDeepSeekKey) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("API providers") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "API requests send the selected conversation context and attachments to the provider. Provider billing and data policies apply.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text("Active inference", color = MaterialTheme.colorScheme.primary)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          InferenceBackend.entries.forEach { option ->
            FilterChip(
              selected = backend == option,
              onClick = { backend = option },
              label = { Text(option.label) },
            )
          }
        }

        HorizontalDivider()
        Text("Gemini API", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
          value = geminiKey,
          onValueChange = { geminiKey = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Gemini API key") },
          placeholder = { Text("Encrypted on this device") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
        )
        Text(
          if (storedGeminiKey.isBlank()) "No Gemini key saved." else "A Gemini key is saved.",
          style = MaterialTheme.typography.labelSmall,
        )
        ProviderModelPicker(
          label = "Gemini model",
          selected = geminiModel,
          options = GEMINI_MODELS,
          onSelected = { geminiModel = it },
        )

        HorizontalDivider()
        Text("DeepSeek API", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
          value = deepSeekKey,
          onValueChange = { deepSeekKey = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("DeepSeek API key") },
          placeholder = { Text("Encrypted on this device") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
        )
        Text(
          if (storedDeepSeekKey.isBlank()) "No DeepSeek key saved." else "A DeepSeek key is saved.",
          style = MaterialTheme.typography.labelSmall,
        )
        ProviderModelPicker(
          label = "DeepSeek model",
          selected = deepSeekModel,
          options = DEEPSEEK_MODELS,
          onSelected = { deepSeekModel = it },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val saved =
            onSave(
              ApiProviderConfiguration(
                backend = backend,
                geminiModel = geminiModel,
                deepSeekModel = deepSeekModel,
              ),
              geminiKey,
              deepSeekKey,
            )
          if (saved) onDismiss()
        }
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun ProviderModelPicker(
  label: String,
  selected: String,
  options: List<String>,
  onSelected: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(selected)
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { model ->
        DropdownMenuItem(
          text = { Text(model) },
          onClick = {
            onSelected(model)
            expanded = false
          },
        )
      }
    }
  }
}

private val GEMINI_MODELS = listOf("gemini-3.5-flash")
private val DEEPSEEK_MODELS = listOf("deepseek-v4-flash", "deepseek-v4-pro")
