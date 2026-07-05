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
  val ShellBlack = Color(0xFF000000)
  val ChatBlack = Color(0xFF070709)
  val SurfaceBlack = Color(0xFF0D0714)
  val RaisedBlack = Color(0xFF1A1D22)
  val HotPink = Color(0xFFFF35D3)
  val Purple = Color(0xFF9147FF)
  val DeepPurple = Color(0xFF5A2CA0)
  val Gold = Color(0xFFFFD85A)
  val Cyan = Color(0xFF35D7E8)
  val Muted = Color(0xFFA7A1AD)
  val Gradient = Brush.horizontalGradient(listOf(Color(0xFFED35C3), Color(0xFF8B43F2)))
}

val LocalEdgeThemeMode = staticCompositionLocalOf { EdgeThemeMode.DEFAULT }

private val DefaultColors =
  darkColorScheme(
    primary = EdgeLightPalette.HotPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF35142F),
    onPrimaryContainer = EdgeLightPalette.Gold,
    secondary = EdgeLightPalette.Purple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF231832),
    onSecondaryContainer = EdgeLightPalette.Gold,
    background = EdgeLightPalette.ShellBlack,
    onBackground = Color(0xFFF4EFF7),
    surface = EdgeLightPalette.SurfaceBlack,
    onSurface = Color(0xFFF4EFF7),
    surfaceVariant = EdgeLightPalette.RaisedBlack,
    onSurfaceVariant = EdgeLightPalette.Muted,
    outline = EdgeLightPalette.DeepPurple,
    error = Color(0xFFFF6B76),
    errorContainer = Color(0xFF3B1118),
  )

private val DarkColors =
  darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF112F60),
    primaryContainer = Color(0xFF294777),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8E9099),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
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
