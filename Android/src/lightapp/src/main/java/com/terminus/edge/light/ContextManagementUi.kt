package com.terminus.edge.light

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.context.ContextEntryView
import com.terminus.edge.light.context.ContextSnapshot
import com.terminus.edge.light.context.RetentionPolicy
import com.terminus.edge.light.context.ContextMode
import com.terminus.edge.light.context.ContextSettings
import com.terminus.edge.light.inference.GenerationSettings
import java.text.DecimalFormat

@Composable
internal fun ContextMeter(
  snapshot: ContextSnapshot,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pressureColor = contextPressureColor(snapshot.usage.percentage)
  val borderColor =
    if (snapshot.usage.percentage >= 90) pressureColor else EdgeLightPalette.Gold
  val fraction =
    (snapshot.usage.estimatedTokens.toFloat() / snapshot.usage.totalTokens.coerceAtLeast(1))
      .coerceIn(0f, 1f)
  Column(
    modifier =
      modifier
        .border(1.dp, borderColor, RoundedCornerShape(5.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Context Usage",
        color = borderColor,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        text = "${snapshot.usage.percentage}%",
        color = pressureColor,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
      )
    }
    LinearProgressIndicator(
      progress = { fraction },
      modifier = Modifier.fillMaxWidth().height(8.dp),
      color = pressureColor,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = "${compactNumber(snapshot.usage.estimatedTokens)} Used",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        text = "${compactNumber(snapshot.usage.totalTokens)} Max",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
internal fun ContextPressureNotice(
  snapshot: ContextSnapshot,
  onManage: () -> Unit,
  onCompress: () -> Unit,
) {
  if (snapshot.usage.percentage < 90) return
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(9.dp))
        .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(9.dp))
        .padding(horizontal = 9.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Context ${snapshot.usage.percentage}% · response reserve at risk",
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall,
    )
    TextButton(onClick = onCompress) { Text("Compress") }
    TextButton(onClick = onManage) { Text("Review") }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextManagerSheet(
  snapshot: ContextSnapshot,
  settings: GenerationSettings,
  contextSettings: ContextSettings,
  systemPrompt: String,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onPolicyChange: (String, RetentionPolicy) -> Unit,
  onCompress: () -> Unit,
  onRestore: () -> Unit,
  onClearTemporary: () -> Unit,
  onNewConversation: () -> Unit,
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
  var contextThreshold by remember(contextSettings) { mutableStateOf(contextSettings.compressionThresholdPercent.toString()) }
  var contextReserve by remember(contextSettings) { mutableStateOf(contextSettings.reservedOutputTokens.toString()) }

  val parsedSettings =
    GenerationSettings(
      maxTokens = maxTokens.toIntOrNull() ?: -1,
      topK = topK.toIntOrNull() ?: -1,
      topP = topP.toDoubleOrNull() ?: -1.0,
      temperature = temperature.toDoubleOrNull() ?: -1.0,
      imageInputEnabled = imageInputEnabled,
    )
  val parsedContextSettings =
    ContextSettings(
      mode = contextMode,
      compressionThresholdPercent = contextThreshold.toIntOrNull() ?: -1,
      reservedOutputTokens = contextReserve.toIntOrNull() ?: -1,
    )

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = {
      onApplyModelSettings(parsedSettings, prompt)
      onApplyContextSettings(parsedContextSettings)
      onDismiss()
    },
    sheetState = sheetState,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text(
          "Context Manager",
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleLarge,
        )
      }
      
      item {
        AccordionMenu("Customize Model Settings") {
          SliderSettingField("Max Tokens", maxTokens, 256f..32768f) { maxTokens = it }
          SliderSettingField("Top K", topK, 1f..1024f) { topK = it }
          SliderSettingField("Top P", topP, 0.0f..1.0f, decimal = true) { topP = it }
          SliderSettingField("Temperature", temperature, 0.0f..2.0f, decimal = true) { temperature = it }
          
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Enable Image Input")
            TraceRecordingSwitch(
              checked = imageInputEnabled,
              onCheckedChange = { imageInputEnabled = it },
              enabled = !isBusy,
            )
          }

          NumericSettingField(
            label = "System Prompt",
            value = prompt,
            onValueChange = { prompt = it },
            decimal = false
          )
        }
      }

      item {
        AccordionMenu("Context Management") {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContextMode.entries.forEach { mode ->
              if (mode == contextMode) {
                GradientPillButton(text = mode.name.lowercase().replaceFirstChar(Char::uppercase), onClick = {})
              } else {
                OutlinedButton(onClick = { contextMode = mode }) {
                  Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
              }
            }
          }
          if (contextMode == ContextMode.AUTOMATIC) {
            SliderSettingField("Compression Threshold (%)", contextThreshold, 50f..90f) { contextThreshold = it }
          }
          SliderSettingField("Reserved Output Tokens", contextReserve, 128f..16384f) { contextReserve = it }

          Spacer(Modifier.height(8.dp))
          Text(
            "${snapshot.usage.characters} characters · ~${snapshot.usage.estimatedTokens} / " +
              "${snapshot.usage.totalTokens} tokens · ${snapshot.usage.percentage}%",
            color = contextPressureColor(snapshot.usage.percentage),
            style = MaterialTheme.typography.bodyMedium,
          )
          LinearProgressIndicator(
            progress = {
              (snapshot.usage.estimatedTokens.toFloat() /
                  snapshot.usage.totalTokens.coerceAtLeast(1))
                .coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            color = contextPressureColor(snapshot.usage.percentage),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
          )
          
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            GradientPillButton(text = "Compress", onClick = onCompress)
            OutlinedButton(onClick = onRestore, enabled = snapshot.compressedEntryIds.isNotEmpty()) { Text("Restore") }
            OutlinedButton(onClick = onClearTemporary) { Text("Clear temp") }
            OutlinedButton(
              onClick = {
                onApplyModelSettings(parsedSettings, prompt)
                onApplyContextSettings(parsedContextSettings)
                onNewConversation()
                onDismiss()
              }
            ) {
              Text("New chat")
            }
          }
        }
      }
      
      item {
        Text(
          "Pinned and retained entries are protected from automatic compression. Originals remain " +
            "visible and recoverable after density compression.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      
      items(snapshot.entries, key = ContextEntryView::id) { entry ->
        ContextEntryRow(entry = entry, onPolicyChange = onPolicyChange)
      }
      item { Spacer(Modifier.height(20.dp)) }
    }
  }
}

@Composable
private fun ContextEntryRow(
  entry: ContextEntryView,
  onPolicyChange: (String, RetentionPolicy) -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        .padding(9.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        entry.label,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge,
      )
      Text(
        "${entry.characters} chars · ~${entry.estimatedTokens} tok",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      entry.preview,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        when {
          !entry.included -> "Excluded"
          entry.compressed -> "Density-compressed"
          else -> entry.policy.label
        },
        color =
          when {
            !entry.included -> MaterialTheme.colorScheme.error
            entry.policy == RetentionPolicy.PINNED ||
              entry.policy == RetentionPolicy.SAFE_RETENTION -> EdgeLightPalette.Gold
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
        style = MaterialTheme.typography.labelSmall,
      )
      if (entry.editable) {
        Spacer(Modifier.width(8.dp))
        Row(
          modifier = Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          RetentionPolicy.entries.forEach { policy ->
            TextButton(onClick = { onPolicyChange(entry.id, policy) }) {
              Text(
                policy.label,
                color =
                  if (policy == entry.policy) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
      }
    }
  }
}

private fun contextPressureColor(percentage: Int): Color =
  when {
    percentage >= 80 -> Color(0xFFFF4D5E)
    percentage >= 50 -> EdgeLightPalette.Gold
    else -> EdgeLightPalette.Cyan
  }

private fun compactNumber(number: Int): String {
  if (number < 1000) return number.toString()
  val exp = (Math.log10(number.toDouble()) / 3).toInt()
  return String.format("%.1f%c", number / Math.pow(1000.0, exp.toDouble()), "kMGTPE"[exp - 1])
}
