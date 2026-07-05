package com.terminus.edge.light

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.trace.TraceStats

@Composable
fun SettingsTabUi(
  themeMode: EdgeThemeMode,
  modelLabel: String,
  isBusy: Boolean,
  traceEnabled: Boolean,
  traceStats: TraceStats,
  bubbleModeEnabled: Boolean,
  hfToken: String,
  geminiApiKey: String,
  onThemeChange: (EdgeThemeMode) -> Unit,
  onImportModel: () -> Unit,
  onDownloadModel: () -> Unit,
  onScanModels: () -> Unit,
  onTraceChange: (Boolean) -> Unit,
  onExportRaw: () -> Unit,
  onExportCurated: () -> Unit,
  onExportReplay: () -> Unit,
  onDeleteTraces: () -> Unit,
  onShowStorage: () -> Unit,
  onToggleBubbleMode: (Boolean) -> Unit,
  onUpdateHfToken: (String) -> Unit,
  onUpdateGeminiKey: (String) -> Unit,
  onOpenSwarmMonitor: () -> Unit,
  onLoginHuggingFace: () -> Unit,
  controller: EdgeController,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    
    AccordionMenu("Swarm Personas", initiallyExpanded = true) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onOpenSwarmMonitor) { Text("Swarm Monitor") }
      }
      com.terminus.edge.light.inference.AgentRole.entries.forEach { role ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(role.name, style = MaterialTheme.typography.bodyMedium)
          com.terminus.edge.light.persona.PersonaSelector(
            personas = controller.personas,
            activePersonaId = controller.activePersonaIds[role],
            onSelect = { id -> controller.setActivePersona(role, id) }
          )
        }
      }
    }

    AccordionMenu("API Keys & Providers") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = hfToken,
          onValueChange = onUpdateHfToken,
          label = { Text("Hugging Face Access Token") },
          modifier = Modifier.weight(1f).padding(vertical = 4.dp),
          visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
          singleLine = true
        )
        OutlinedButton(onClick = onLoginHuggingFace) {
          Text("Login")
        }
      }
      OutlinedTextField(
        value = geminiApiKey,
        onValueChange = onUpdateGeminiKey,
        label = { Text("Gemini API Key") },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        singleLine = true
      )
    }

    AccordionMenu("Model") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(onClick = onImportModel, enabled = !isBusy) { Text("Import") }
        OutlinedButton(onClick = onDownloadModel, enabled = !isBusy) { Text("Download") }
      }
      OutlinedButton(onClick = onScanModels, enabled = !isBusy) { Text("Scan Local Storage") }
      Text(modelLabel, style = MaterialTheme.typography.bodySmall)
    }

    AccordionMenu("App Preferences") {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EdgeThemeMode.entries.forEach { mode ->
          if (mode == themeMode) {
            GradientPillButton(
              text = mode.name.lowercase().replaceFirstChar(Char::uppercase),
              onClick = {},
            )
          } else {
            OutlinedButton(onClick = { onThemeChange(mode) }) {
              Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
            }
          }
        }
      }
      Spacer(Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Screen Share (Bubble)")
          Text("Enable media projection overlay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TraceRecordingSwitch(
          checked = bubbleModeEnabled,
          onCheckedChange = onToggleBubbleMode,
          enabled = true,
        )
      }
    }

    AccordionMenu("Local Traces") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Trace recording")
          Text(
            "${traceStats.eventCount} events | ${traceStats.completed} complete | ${traceStats.unreviewed} unreviewed",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "Integrity: ${traceStats.integrityStatus.replace('_', ' ')}",
            color =
              if (traceStats.integrityStatus == "failed") {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            style = MaterialTheme.typography.labelSmall,
          )
        }
        TraceRecordingSwitch(
          checked = traceEnabled,
          onCheckedChange = onTraceChange,
          enabled = !isBusy,
        )
      }
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onShowStorage) { Text("Archives") }
        TextButton(onClick = onExportRaw, enabled = traceStats.eventCount > 0) { Text("Raw") }
        TextButton(onClick = onExportCurated, enabled = traceStats.completed > 0) { Text("Curated") }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onExportReplay, enabled = traceStats.inferenceAttempts > 0) { Text("Replay ZIP") }
        TextButton(onClick = onDeleteTraces, enabled = traceStats.eventCount > 0) { Text("Delete") }
      }
      Text(
        "Exports contain plaintext prompts and responses. Replay also includes context artifacts and the exact model snapshot.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
internal fun TraceRecordingSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean,
) {
  val branded = LocalEdgeThemeMode.current == EdgeThemeMode.DEFAULT
  val thumbOffset by
    animateDpAsState(
      targetValue = if (checked) 20.dp else 0.dp,
      label = "trace-switch-thumb",
    )
  val trackColor =
    when {
      branded && checked -> EdgeLightPalette.DeepPurple
      branded -> EdgeLightPalette.RaisedBlack
      checked -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceVariant
    }

  Box(
    modifier =
      Modifier.alpha(if (enabled) 1f else 0.45f)
        .clip(RoundedCornerShape(50))
        .background(trackColor)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Switch,
          onValueChange = onCheckedChange,
        )
        .width(52.dp)
        .height(32.dp)
        .padding(4.dp),
  ) {
    Box(
      modifier =
        Modifier.offset { IntOffset(thumbOffset.roundToPx(), 0) }
          .clip(CircleShape)
          .then(
            if (branded) {
              Modifier.background(EdgeLightPalette.Gradient)
            } else {
              Modifier.background(
                if (checked) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.outline
                }
              )
            }
          )
          .size(24.dp)
    )
  }
}

@Composable
fun SliderSettingField(
  label: String,
  value: String,
  valueRange: ClosedFloatingPointRange<Float>,
  decimal: Boolean = false,
  onValueChange: (String) -> Unit
) {
  val floatValue = value.toFloatOrNull() ?: valueRange.start
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.width(100.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
      )
    }
    androidx.compose.material3.Slider(
      value = floatValue,
      onValueChange = { 
        if (decimal) {
          onValueChange(String.format("%.2f", it))
        } else {
          onValueChange(it.toInt().toString())
        }
      },
      valueRange = valueRange
    )
  }
}

@Composable
internal fun NumericSettingField(
  label: String,
  value: String,
  decimal: Boolean = false,
  onValueChange: (String) -> Unit,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    keyboardOptions =
      KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    singleLine = true,
  )
}
