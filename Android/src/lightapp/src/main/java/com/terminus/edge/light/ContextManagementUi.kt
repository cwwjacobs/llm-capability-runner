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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.context.ContextEntryView
import com.terminus.edge.light.context.ContextSnapshot
import com.terminus.edge.light.context.RetentionPolicy
import java.text.DecimalFormat

@Composable
internal fun ContextMeter(
  snapshot: ContextSnapshot,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pressureColor = contextPressureColor(snapshot.usage.percentage)
  val borderColor =
    when {
      snapshot.usage.percentage >= 90 -> pressureColor
      snapshot.usage.percentage >= 80 -> EdgeLightPalette.Gold
      else -> EdgeLightPalette.DeepPurple
    }
  val fraction =
    (snapshot.usage.estimatedTokens.toFloat() / snapshot.usage.totalTokens.coerceAtLeast(1))
      .coerceIn(0f, 1f)
  Column(
    modifier =
      modifier
        .background(EdgeLightPalette.SurfaceBlack, RoundedCornerShape(14.dp))
        .border(1.dp, borderColor.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Context",
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        text = "${snapshot.usage.percentage}%",
        color = if (snapshot.usage.percentage >= 80) borderColor else EdgeLightPalette.Cyan,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Box(
      modifier =
        Modifier.fillMaxWidth().height(5.dp)
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(fraction).height(5.dp)
            .background(
              if (snapshot.usage.percentage >= 90) {
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                  listOf(EdgeLightPalette.Gold, pressureColor)
                )
              } else {
                EdgeLightPalette.Gradient
              },
              RoundedCornerShape(50),
            ),
      )
    }
    Text(
      text =
        "~${compactNumber(snapshot.usage.estimatedTokens)} / " +
          "${compactNumber(snapshot.usage.totalTokens)} tokens · " +
          "${compactNumber(snapshot.usage.characters)} chars",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      style = MaterialTheme.typography.labelSmall,
    )
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
  onDismiss: () -> Unit,
  onPolicyChange: (String, RetentionPolicy) -> Unit,
  onCompress: () -> Unit,
  onRestore: () -> Unit,
  onClearTemporary: () -> Unit,
  onNewConversation: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        "Context Manager",
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        "${snapshot.usage.characters} characters · ~${snapshot.usage.estimatedTokens} / " +
          "${snapshot.usage.totalTokens} tokens · ${snapshot.usage.percentage}%",
        color = contextPressureColor(snapshot.usage.percentage),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        "${snapshot.settings.mode.label} mode · ${snapshot.usage.reservedOutputTokens} tokens " +
          "reserved for output · estimates use 4 characters per token",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
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
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        GradientPillButton(text = "Compress", onClick = onCompress)
        OutlinedButton(onClick = onRestore, enabled = snapshot.compressedEntryIds.isNotEmpty()) {
          Text("Restore")
        }
        OutlinedButton(onClick = onClearTemporary) { Text("Clear temp") }
        OutlinedButton(
          onClick = {
            onNewConversation()
            onDismiss()
          }
        ) {
          Text("New chat")
        }
      }
      Text(
        "Pinned and retained entries are protected from automatic compression. Originals remain " +
          "visible and recoverable after density compression.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      HorizontalDivider()
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(snapshot.entries, key = ContextEntryView::id) { entry ->
          ContextEntryRow(entry = entry, onPolicyChange = onPolicyChange)
        }
        item { Spacer(Modifier.height(20.dp)) }
      }
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
    percentage >= 90 -> Color(0xFFFF4D5E)
    percentage >= 80 -> EdgeLightPalette.Gold
    else -> EdgeLightPalette.Cyan
  }

private fun compactNumber(value: Int): String {
  if (value < 1000) return value.toString()
  return "${DecimalFormat("0.#").format(value / 1000.0)}K"
}
