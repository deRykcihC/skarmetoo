package com.deryk.skarmetoo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingOverlay(
    targetRect: Rect?,
    text: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    hasPrev: Boolean = true,
    hasNext: Boolean = true,
    allowInteractionWithTarget: Boolean = false
) {
  if (targetRect == null) return

  Box(
      modifier =
          Modifier.fillMaxSize().pointerInput(targetRect, allowInteractionWithTarget) {
            detectTapGestures { offset ->
              if (allowInteractionWithTarget && targetRect.contains(offset)) {
                // We cannot easily pass click through a standard Box unless we return false in
                // awaitPointerEvent,
                // but since Compose gesture detectors consume, it's easier to just do next step on
                // tap
                // if they allowed interaction, or just consume it.
                // Actually, to make it completely transparent to clicks on the target rect,
                // we can handle it at a lower level or just skip gesture detection there.
              }
            }
          }) {
        // Dark overlay with cutout
        Canvas(
            modifier =
                Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f } // Needed for BlendMode.Clear
            ) {
              drawRect(color = Color.Black.copy(alpha = 0.7f), size = size)

              val path =
                  Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = targetRect,
                            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())))
                  }
              drawPath(path = path, color = Color.Transparent, blendMode = BlendMode.Clear)
            }

        // Info Card
        val density = LocalDensity.current
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp

        // Decide if card goes above or below
        val isTargetBelowMidline = targetRect.center.y > (with(density) { screenHeight.toPx() } / 2)

        Box(modifier = Modifier.fillMaxSize()) {
          Card(
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(24.dp)
                      .align(
                          if (isTargetBelowMidline) Alignment.TopCenter else Alignment.BottomCenter)
                      .offset(y = if (isTargetBelowMidline) 80.dp else (-80).dp),
              shape = RoundedCornerShape(16.dp),
              colors =
                  CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(20.dp)) {
                  Text(
                      text = text,
                      style = MaterialTheme.typography.bodyLarge,
                      color = MaterialTheme.colorScheme.onSurface)

                  Spacer(modifier = Modifier.height(20.dp))

                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) {
                          Text("Skip", color = MaterialTheme.colorScheme.outline)
                        }

                        Row {
                          if (hasPrev) {
                            TextButton(onClick = onPrev) { Text("Back") }
                          }
                          Button(onClick = onNext) { Text(if (hasNext) "Next" else "Done") }
                        }
                      }
                }
              }
        }
      }
}
