package com.terminus.edge.light

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class EdgeThemeMode(val wireValue: String) {
  DEFAULT("default"),
  DARK("dark"),
  LIGHT("light");

  companion object {
    fun fromWireValue(value: String?): EdgeThemeMode =
      entries.firstOrNull { it.wireValue == value } ?: DEFAULT
  }
}

object EdgeLightPalette {
  val ShellBlack = Color(0xFF0B0910)
  val ChatBlack = Color(0xFF07060A)
  val SurfaceBlack = Color(0xFF121016)
  val RaisedBlack = Color(0xFF17121D)
  val HotPink = Color(0xFFFF3EB5)
  val Purple = Color(0xFF6D36FF)
  val DeepPurple = Color(0xFF4B2396)
  val Gold = Color(0xFFD4AF57)
  val Cyan = Color(0xFF55DDEC)
  val Muted = Color(0xFFAAA2B3)
  val Gradient = Brush.horizontalGradient(listOf(HotPink, Purple))
}

val LocalEdgeThemeMode = staticCompositionLocalOf { EdgeThemeMode.DEFAULT }

private val DefaultColors =
  darkColorScheme(
    primary = EdgeLightPalette.HotPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF37132F),
    onPrimaryContainer = Color(0xFFFFC8ED),
    secondary = EdgeLightPalette.Purple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF271746),
    onSecondaryContainer = Color(0xFFE5D9FF),
    background = EdgeLightPalette.ShellBlack,
    onBackground = Color(0xFFF4EFF7),
    surface = EdgeLightPalette.SurfaceBlack,
    onSurface = Color(0xFFF4EFF7),
    surfaceVariant = EdgeLightPalette.RaisedBlack,
    onSurfaceVariant = EdgeLightPalette.Muted,
    outline = EdgeLightPalette.Gold.copy(alpha = 0.62f),
    error = Color(0xFFFF6B76),
    errorContainer = Color(0xFF3B1118),
  )

private val DarkColors =
  darkColorScheme(
    primary = EdgeLightPalette.HotPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF281124),
    onPrimaryContainer = Color(0xFFFFD6F0),
    secondary = EdgeLightPalette.Cyan,
    onSecondary = Color(0xFF082D33),
    secondaryContainer = Color(0xFF0F2A31),
    onSecondaryContainer = Color(0xFFBFF7FF),
    background = EdgeLightPalette.ShellBlack,
    onBackground = Color(0xFFF0EDF4),
    surface = EdgeLightPalette.SurfaceBlack,
    onSurface = Color(0xFFF0EDF4),
    surfaceVariant = EdgeLightPalette.RaisedBlack,
    onSurfaceVariant = Color(0xFFB9B1C0),
    outline = EdgeLightPalette.Gold.copy(alpha = 0.56f),
    error = Color(0xFFFF9AA2),
    errorContainer = Color(0xFF3B1118),
    onErrorContainer = Color(0xFFFFDAD6),
  )

private val LightColors =
  lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF284777),
    secondary = Color(0xFF565F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4759),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
  )

@Composable
fun EdgeLightTheme(mode: EdgeThemeMode, content: @Composable () -> Unit) {
  val colors =
    when (mode) {
      EdgeThemeMode.DEFAULT -> DefaultColors
      EdgeThemeMode.DARK -> DarkColors
      EdgeThemeMode.LIGHT -> LightColors
    }
  CompositionLocalProvider(LocalEdgeThemeMode provides mode) {
    MaterialTheme(colorScheme = colors, content = content)
  }
}

@Composable
fun GradientPillButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
) {
  val shape = RoundedCornerShape(50)
  val branded = LocalEdgeThemeMode.current == EdgeThemeMode.DEFAULT
  Box(
    modifier =
      modifier
        .clip(shape)
        .alpha(if (enabled) 1f else 0.45f)
        .then(
          if (branded) {
            Modifier.background(EdgeLightPalette.Gradient)
          } else {
            Modifier.background(MaterialTheme.colorScheme.primary)
          }
        )
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (branded) Color.White else MaterialTheme.colorScheme.onPrimary,
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

@Composable
fun edgeLightBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
