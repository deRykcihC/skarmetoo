package com.deryk.skarmetoo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.R

/** Title-only header and a floating Back control, matching the Details page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SecondaryPageLayout(
    title: String,
    onBack: () -> Unit,
    bottomActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
  Box(
      modifier = Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Text(
          text = title,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
      )
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
    Row(
        modifier = Modifier.align(Alignment.BottomStart)
            .then(if (bottomActions != null) Modifier.fillMaxWidth() else Modifier)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
      ) {
        IconButton(onClick = hapticOnClick(onBack), modifier = Modifier.size(56.dp), shape = CircleShape) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
        }
      }
      bottomActions?.invoke(this)
    }
  }
}
