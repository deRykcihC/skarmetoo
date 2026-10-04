package com.deryk.skarmetoo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.ui.theme.AppMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Briefly reshapes the existing pill on press, then flattens back without delaying its click. */
@Composable
fun rememberSquigglePillShape(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 20.dp,
    continuous: Boolean = false,
): Shape {
  val amount = remember(interactionSource) { Animatable(0f) }
  val phase = remember(interactionSource) { Animatable(0f) }
  val density = LocalDensity.current
  val radiusPx = with(density) { cornerRadius.toPx() }
  val amplitudePx = with(density) { 1.4.dp.toPx() }
  val wavelengthPx = with(density) { 18.dp.toPx() }
  val normalShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  LaunchedEffect(interactionSource, continuous) {
    var pulse: Job? = null
    if (continuous) {
      pulse = launch {
        coroutineScope {
          launch { amount.animateTo(1f, AppMotion.timed(80)) }
          while (true) {
            phase.animateTo(phase.value + 1f, tween(380, easing = LinearEasing))
            phase.snapTo(phase.value % 1f)
          }
        }
      }
    } else if (amount.value > 0f) {
      // Continue moving the wave as its amplitude fades into the resting outline.
      pulse = launch {
        coroutineScope {
          launch { phase.animateTo(phase.value + 240f / 380f, tween(240, easing = LinearEasing)) }
          amount.animateTo(0f, AppMotion.timed(240))
        }
      }
    }
    interactionSource.interactions.collect { interaction ->
      if (!continuous && interaction is PressInteraction.Press) {
        pulse?.cancel()
        pulse = launch {
          coroutineScope {
            launch { phase.animateTo(phase.value + 1f, tween(380, easing = LinearEasing)) }
            amount.animateTo(1f, AppMotion.timed(80))
            delay(60)
            amount.animateTo(0f, AppMotion.timed(240))
          }
        }
      }
    }
  }
  val waveAmount = amount.value
  val wavePhase = phase.value
  if (waveAmount == 0f) return normalShape
  return remember(waveAmount, wavePhase, radiusPx, amplitudePx, wavelengthPx) {
    object : Shape {
      override fun createOutline(
          size: Size,
          layoutDirection: LayoutDirection,
          density: Density
      ): Outline {
        // Keep all crests inside the existing bounds so the pill never clips or changes layout.
        val amplitude = amplitudePx * waveAmount
        val radius =
            (radiusPx - amplitude).coerceIn(
                0f, (size.minDimension / 2f - amplitude).coerceAtLeast(0f))
        val base =
            android.graphics.Path().apply {
              addRoundRect(
                  amplitude,
                  amplitude,
                  size.width - amplitude,
                  size.height - amplitude,
                  radius,
                  radius,
                  android.graphics.Path.Direction.CW)
            }
        val measure = android.graphics.PathMeasure(base, true)
        val waveCount = (measure.length / wavelengthPx).roundToInt().coerceIn(6, 32)
        val sampleCount = waveCount * 12
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        val path = Path()
        repeat(sampleCount) { index ->
          val fraction = index.toFloat() / sampleCount
          measure.getPosTan(measure.length * fraction, position, tangent)
          val displacement = sin((fraction * waveCount - wavePhase) * 2f * PI.toFloat()) * amplitude
          val x = position[0] - tangent[1] * displacement
          val y = position[1] + tangent[0] * displacement
          if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
      }
    }
  }
}
