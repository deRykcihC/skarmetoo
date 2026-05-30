package com.deryk.skarmetoo.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

@Composable
fun hapticOnClick(onClick: () -> Unit): () -> Unit {
  val view = LocalView.current
  val latestOnClick = rememberUpdatedState(onClick)

  return remember(view) {
    {
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      latestOnClick.value()
    }
  }
}

@Composable
fun <T> hapticOnClick(onClick: (T) -> Unit): (T) -> Unit {
  val view = LocalView.current
  val latestOnClick = rememberUpdatedState(onClick)

  return remember(view) {
    { value ->
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      latestOnClick.value(value)
    }
  }
}
