package com.deryk.skarmetoo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.ui.theme.AppMotion

@Composable
fun ExpressiveNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    indicatorColor: Color,
    selectedContentColor: Color,
    modifier: Modifier = Modifier,
) {
  val selectionProgress by
      animateFloatAsState(
          if (selected) 1f else 0f,
          animationSpec = AppMotion.timed(240),
          label = "navigationSelection",
      )
  val contentColor by
      animateColorAsState(
          if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
          animationSpec = AppMotion.timed(240),
          label = "navigationContentColor",
      )
  Surface(
      modifier = modifier.height(64.dp),
      shape = RoundedCornerShape(50),
      color = Color.Transparent,
      contentColor = contentColor,
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Box(
          modifier =
              Modifier.size(width = 56.dp, height = 32.dp).drawBehind {
                // Keep the hue fixed while fading; transparent black can cause a dark flash.
                val progress = selectionProgress.coerceIn(0f, 1f)
                val width = size.width * (0.85f + 0.15f * progress)
                drawRoundRect(
                    color = indicatorColor.copy(alpha = indicatorColor.alpha * progress),
                    topLeft = Offset((size.width - width) / 2f, 0f),
                    size = Size(width, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
              },
          contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
      }
      Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
