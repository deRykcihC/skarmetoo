package com.deryk.skarmetoo.ai.agentic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.ui.theme.AppMotion
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ChatQuestionComposer(
    question: String,
    onQuestionChange: (String) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  var typingTick by remember { mutableIntStateOf(0) }
  val wiggle = remember { Animatable(0f) }
  val processingPhase = remember { Animatable(0f) }
  val processingStrength by
      animateFloatAsState(
          targetValue = if (busy) 1f else 0f,
          animationSpec = AppMotion.fastEffects(),
          label = "Processing pill strength")
  LaunchedEffect(busy) {
    if (busy) {
      processingPhase.snapTo(0f)
      processingPhase.animateTo(
          (2 * PI).toFloat(), infiniteRepeatable(animation = AppMotion.linear(1600)))
    }
  }
  val inputShape =
      if (processingStrength > 0f) ProcessingPillShape(processingPhase.value, processingStrength)
      else RoundedCornerShape(28.dp)
  LaunchedEffect(focused, typingTick, busy) {
    if (focused && !busy) {
      // A short motion on focus and each edit; no ongoing animation while the field is idle.
      wiggle.animateTo(-0.6f, AppMotion.pulse(80))
      wiggle.animateTo(0.6f, AppMotion.pulse(120))
      wiggle.animateTo(-0.3f, AppMotion.pulse(100))
    }
    wiggle.animateTo(0f, AppMotion.pulse(120))
  }
  val colors = MaterialTheme.colorScheme
  Row(
      modifier.height(IntrinsicSize.Min),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = question,
            onValueChange = {
              onQuestionChange(it)
              typingTick++
            },
            placeholder = { Text("Your question") },
            modifier =
                Modifier.weight(1f)
                    .graphicsLayer { rotationZ = wiggle.value }
                    .semantics { contentDescription = "Your question" },
            enabled = !busy,
            maxLines = 4,
            shape = inputShape,
            interactionSource = interactionSource,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = colors.secondaryContainer,
                    unfocusedContainerColor = colors.secondaryContainer,
                    disabledContainerColor = colors.secondaryContainer,
                    focusedTextColor = colors.onSecondaryContainer,
                    unfocusedTextColor = colors.onSecondaryContainer,
                    disabledTextColor = colors.onSecondaryContainer.copy(alpha = 0.6f),
                    focusedPlaceholderColor = colors.onSecondaryContainer.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = colors.onSecondaryContainer.copy(alpha = 0.7f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }))
        FilledIconButton(
            onClick = { if (busy) onStop() else onSend() },
            enabled = busy || canSend,
            shape = RoundedCornerShape(50),
            modifier = Modifier.width(72.dp).fillMaxHeight()) {
              Icon(
                  if (busy) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                  contentDescription = if (busy) "Cancel question" else "Send question",
                  modifier = Modifier.size(20.dp))
            }
      }
}

/** Moving waves stay inside the field bounds so the composer and text do not shift. */
private class ProcessingPillShape(private val phase: Float, private val strength: Float) : Shape {
  override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density
  ): Outline {
    val radius = minOf(with(density) { 28.dp.toPx() }, size.width / 2f, size.height / 2f)
    val amplitude = with(density) { 3.dp.toPx() } * strength
    val span = size.width - 2f * radius
    fun wave(t: Float, offset: Float): Float {
      val envelope = sin(PI * t).toFloat()
      return amplitude * (1f + sin(phase + 4f * PI.toFloat() * t + offset)) / 2f *
          envelope *
          envelope
    }
    val path =
        Path().apply {
          moveTo(radius, 0f)
          for (step in 1..48) {
            val t = step / 48f
            lineTo(radius + span * t, wave(t, 0f))
          }
          arcTo(Rect(size.width - 2 * radius, 0f, size.width, 2 * radius), 270f, 90f, false)
          lineTo(size.width, size.height - radius)
          arcTo(
              Rect(size.width - 2 * radius, size.height - 2 * radius, size.width, size.height),
              0f,
              90f,
              false)
          for (step in 47 downTo 0) {
            val t = step / 48f
            lineTo(radius + span * t, size.height - wave(t, PI.toFloat()))
          }
          arcTo(Rect(0f, size.height - 2 * radius, 2 * radius, size.height), 90f, 90f, false)
          lineTo(0f, radius)
          arcTo(Rect(0f, 0f, 2 * radius, 2 * radius), 180f, 90f, false)
          close()
        }
    return Outline.Generic(path)
  }
}
