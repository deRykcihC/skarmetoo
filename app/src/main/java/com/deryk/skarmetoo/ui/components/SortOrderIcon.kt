package com.deryk.skarmetoo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.South
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.deryk.skarmetoo.ui.theme.AppMotion

@Composable
fun SortOrderIcon(isSortDescending: Boolean, modifier: Modifier = Modifier) {
  val rotation =
      animateFloatAsState(
          targetValue = if (isSortDescending) 0f else 180f,
          animationSpec = AppMotion.fastSpatial(),
          label = "SortOrderArrowRotation",
      )

  Icon(
      imageVector = Icons.Rounded.South,
      contentDescription = null,
      modifier = modifier.graphicsLayer { rotationZ = rotation.value },
  )
}
