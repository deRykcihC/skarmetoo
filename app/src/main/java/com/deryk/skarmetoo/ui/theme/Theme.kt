package com.deryk.skarmetoo.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Composition local to let any composable query the current dark-mode state. */
val LocalIsDarkMode = compositionLocalOf { false }

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFBB86FC),
        onPrimary = Color(0xFF1E1E1E),
        primaryContainer = Color(0xFF3A2F5C),
        onPrimaryContainer = Color(0xFFE0D0FF),
        secondary = Color(0xFF03DAC5),
        onSecondary = Color(0xFF1E1E1E),
        secondaryContainer = Color(0xFF1E3A36),
        onSecondaryContainer = Color(0xFFB2DFDB),
        tertiary = Color(0xFFCF6679),
        background = Color(0xFF1E1E1E),
        onBackground = Color(0xFFE8E8E8),
        surface = Color(0xFF2A2A2A),
        onSurface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFF3A3A3A),
        onSurfaceVariant = Color(0xFFBDBDBD),
        surfaceContainerLow = Color(0xFF2E2E2E),
        surfaceContainerHigh = Color(0xFF363636),
        surfaceContainer = Color(0xFF333333),
        outline = Color(0xFF777777),
        outlineVariant = Color(0xFF555555),
        error = Color(0xFFCF6679),
        onError = Color(0xFF1E1E1E),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
        /* Other default colors to override
        background = Color(0xFFFFFBFE),
        surface = Color(0xFFFFFBFE),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onTertiary = Color.White,
        onBackground = Color(0xFF1C1B1F),
        onSurface = Color(0xFF1C1B1F),
         */
    )

@Composable
fun SkarmetooTheme(
    darkTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
  val colorScheme =
      when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
          val context = LocalContext.current
          if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
      }

  // If using dynamic dark, override background/surface to our dark grey instead of pure black
  val finalScheme =
      if (darkTheme && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorScheme.copy(
            background = Color(0xFF1E1E1E),
            surface = Color(0xFF2A2A2A),
            surfaceContainerLow = Color(0xFF2E2E2E),
            surfaceContainerHigh = Color(0xFF363636),
            surfaceContainer = Color(0xFF333333),
        )
      } else {
        colorScheme
      }

  MaterialTheme(
      colorScheme = finalScheme,
      typography = Typography,
      content = content,
  )
}
