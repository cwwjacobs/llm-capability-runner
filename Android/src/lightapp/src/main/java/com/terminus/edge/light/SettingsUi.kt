package com.terminus.edge.light

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.context.ContextMode
import com.terminus.edge.light.context.ContextSettings
import com.terminus.edge.light.inference.GenerationSettings
import com.terminus.edge.light.trace.TraceStats

@Composable
internal fun SettingsDialog(
  themeMode: EdgeThemeMode,
  settings: GenerationSettings,
  contextSettings: ContextSettings,
  systemPrompt: String,
  modelLabel: String,
  isBusy: Boolean,
  traceEnabled: Boolean,
  traceStats: TraceStats,
  onDismiss: () -> Unit,
  onThemeChange: (EdgeThemeMode) -> Unit,
  onOpenDeviceModels: () -> Unit,
  onOpenApiProviders: () -> Unit,
  onImportModel: () -> Unit,
  onTraceChange: (Boolean) -> Unit,
  onExportRaw: () -> Unit,
  onExportCurated: () -> Unit,
  onExportReplay: () -> Unit,
  onArchiveTraces: () -> Unit,
  onOpenNotes: () -> Unit,
  onOpenWorkflows: () -> Unit,
  onOpenHuggingFaceAccess: () -> Unit,
  onDownloadModel: () -> Unit,
  onApplyModelSettings: (GenerationSettings, String) -> Unit,
  onApplyContextSettings: (ContextSettings) -> Unit,
) {
  var maxTokens by remember(settings) { mutableStateOf(settings.maxTokens.toString()) }
  var topK by remember(settings) { mutableStateOf(settings.topK.toString()) }
  var topP by remember(settings) { mutableStateOf(settings.topP.toString()) }
  var temperature by remember(settings) { mutableStateOf(settings.temperature.toString()) }
  var imageInputEnabled by remember(settings) { mutableStateOf(settings.imageInputEnabled) }
  var prompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }
  var contextMode by remember(contextSettings) { mutableStateOf(contextSettings.mode) }
  var contextThreshold by
    remember(contextSettings) {
      mutableStateOf(contextSettings.compressionThresholdPercent.toString())
    }
  var contextReserve by
    remember(contextSettings) { mutableStateOf(contextSettings.reservedOutputTokens.toString()) }
  val parsedSettings =
    GenerationSettings(
      maxTokens = maxTokens.toIntOrNull() ?: -1,
      topK = topK.toIntOrNull() ?: -1,
      topP = topP.toDoubleOrNull() ?: -1.0,
      temperature = temperature.toDoubleOrNull() ?: -1.0,
      imageInputEnabled = imageInputEnabled,
    )
  val isValid =
    parsedSettings.maxTokens in 256..32768 &&
      parsedSettings.topK in 1..1024 &&
      parsedSettings.topP in 0.0..1.0 &&
      parsedSettings.temperature in 0.0..2.0 &&
      contextThreshold.toIntOrNull() in 50..90 &&
      contextReserve.toIntOrNull() in
        128..(parsedSettings.maxTokens / 2).coerceAtLeast(128) &&
      prompt.isNotBlank()
  val parsedContextSettings =
    ContextSettings(
      mode = contextMode,
      compressionThresholdPercent = contextThreshold.toIntOrNull() ?: -1,
      reservedOutputTokens = contextReserve.toIntOrNull() ?: -1,
    )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Settings") },
    text = {
      Column(
        modifier =
          Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "App settings",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
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
        HorizontalDivider()
        Text(
          "Models and APIs",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(modelLabel, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
          onClick = onOpenDeviceModels,
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Models on this device")
        }
        OutlinedButton(
          onClick = onImportModel,
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Import model")
        }
        GradientPillButton(
          text = "Browse Hugging Face models",
          onClick = onDownloadModel,
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
          onClick = onOpenApiProviders,
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("API providers")
        }

        HorizontalDivider()
        Text(
          "Workspace and access",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = onOpenNotes, modifier = Modifier.fillMaxWidth()) {
          Text("Human notes")
        }
        OutlinedButton(onClick = onOpenWorkflows, modifier = Modifier.fillMaxWidth()) {
          Text("Workflow drafts")
        }
        OutlinedButton(onClick = onOpenHuggingFaceAccess, modifier = Modifier.fillMaxWidth()) {
          Text("Hugging Face access")
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text("Runtime Spine capture")
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TextButton(onClick = onExportRaw, enabled = traceStats.eventCount > 0) { Text("Raw") }
          TextButton(onClick = onExportCurated, enabled = traceStats.completed > 0) {
            Text("Curated")
          }
          TextButton(onClick = onExportReplay, enabled = traceStats.inferenceAttempts > 0) {
            Text("Replay Pack")
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TextButton(onClick = onArchiveTraces, enabled = traceStats.eventCount > 0) {
            Text("Archive")
          }
        }
        Text(
          "Full local capture is enabled by default. Exports contain plaintext prompts and responses; a Replay Pack also includes context artifacts and exact model snapshots.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )

        HorizontalDivider()
        Text(
          "Context management",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          "The meter uses exact characters and an estimated four characters per token.",
          style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ContextMode.entries.forEach { mode ->
            if (mode == contextMode) {
              GradientPillButton(text = mode.label, onClick = {})
            } else {
              OutlinedButton(onClick = { contextMode = mode }) { Text(mode.label) }
            }
          }
        }
        NumericSettingField("Compression threshold %", contextThreshold) {
          contextThreshold = it
        }
        NumericSettingField("Reserved output tokens", contextReserve) { contextReserve = it }
        Text(
          when (contextMode) {
            ContextMode.MANUAL -> "Only operator-requested compression is applied."
            ContextMode.ASSISTED -> "Edge Light warns when compression is recommended."
            ContextMode.AUTOMATIC ->
              "Older compressible entries are summarized automatically at the threshold."
          },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )

        HorizontalDivider()
        Text(
          "Customize model settings",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          "Changes reload the current model and begin a fresh conversation.",
          style = MaterialTheme.typography.bodySmall,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Image input")
            Text(
              "Enable only for multimodal models. Vision uses the GPU backend.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
            )
          }
          TraceRecordingSwitch(
            checked = imageInputEnabled,
            onCheckedChange = { imageInputEnabled = it },
            enabled = !isBusy,
          )
        }
        NumericSettingField("Context size / max tokens", maxTokens) { maxTokens = it }
        NumericSettingField("Top K", topK) { topK = it }
        NumericSettingField("Top P", topP, decimal = true) { topP = it }
        NumericSettingField("Temperature", temperature, decimal = true) { temperature = it }
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("System Prompt") },
          supportingText = { Text("Persistent instructions applied to every response.") },
          minLines = 4,
        )
        if (!isValid) {
          Text(
            "Use context 256-32768, Top K 1-1024, Top P 0-1, temperature 0-2, " +
              "threshold 50-90, a reserve no larger than half the context size, and a non-empty prompt.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onApplyContextSettings(parsedContextSettings)
          onApplyModelSettings(parsedSettings, prompt.trim())
          onDismiss()
        },
        enabled = isValid && !isBusy,
      ) {
        Text("Apply")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun TraceRecordingSwitch(
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
private fun NumericSettingField(
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
