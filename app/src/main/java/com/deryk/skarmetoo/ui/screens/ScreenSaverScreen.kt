package com.deryk.skarmetoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel

@Composable
fun ScreenSaver(viewModel: ScreenshotViewModel, onClose: () -> Unit) {
  var offsetX by remember { mutableStateOf(0f) }
  var offsetY by remember { mutableStateOf(0f) }
  var currentTime by remember { mutableStateOf("") }
  val analysisProgress by viewModel.analysisProgress.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val context = LocalContext.current
  val activity = context.findActivity()

  DisposableEffect(Unit) {
    activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose {
      activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  LaunchedEffect(Unit) {
    val locale = java.util.Locale.getDefault()
    val pattern = if (locale.language == "zh") "HH:mm\nM月d日, yyyy" else "HH:mm\nMMM dd, yyyy"
    val format = java.text.SimpleDateFormat(pattern, locale)
    while (true) {
      currentTime = format.format(java.util.Date())
      kotlinx.coroutines.delay(1000)
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      offsetX = kotlin.random.Random.nextInt(-50, 50).toFloat()
      offsetY = kotlin.random.Random.nextInt(-100, 100).toFloat()
      kotlinx.coroutines.delay(60000)
    }
  }

  Box(
      modifier = Modifier.fillMaxSize().background(Color.Black).clickable { onClose() },
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(x = offsetX.dp, y = offsetY.dp),
    ) {
      Text(
          text = currentTime,
          color = Color.White.copy(alpha = 0.8f),
          style = MaterialTheme.typography.displayMedium,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(32.dp))
      analysisProgress?.let { (remaining, _) ->
        Text(
            text = stringResource(R.string.analyzing_in_background),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.items_left, remaining),
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyLarge,
        )
      }
          ?: run {
            if (isModelReady) {
              Text(
                  text = stringResource(R.string.all_images_analyzed),
                  color = Color.White.copy(alpha = 0.5f),
                  style = MaterialTheme.typography.titleMedium,
              )
            }
          }
    }
    Text(
        text = stringResource(R.string.tap_to_exit),
        color = Color.White.copy(alpha = 0.3f),
        modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        style = MaterialTheme.typography.labelLarge,
    )
  }
}

fun android.content.Context.findActivity(): android.app.Activity? {
  var context = this
  while (context is android.content.ContextWrapper) {
    if (context is android.app.Activity) return context
    context = context.baseContext
  }
  return null
}
