package com.deryk.skarmetoo.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.EmbeddingGemmaSkippedStore
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel

@Composable
fun EmbeddingGemmaSkippedImagesScreen(
    viewModel: ScreenshotViewModel,
    onBack: () -> Unit,
    onScreenshotClick: (Long) -> Unit,
) {
  val context = LocalContext.current
  val entries by viewModel.entries.collectAsState()
  val skippedIds = remember { EmbeddingGemmaSkippedStore.getEntryIds(context) }
  val skippedEntries = remember(entries, skippedIds) { entries.filter { it.id in skippedIds } }
  val gridColumns =
      if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3

  LaunchedEffect(Unit) { viewModel.refreshEntries() }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = hapticOnClick(onBack)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
      }
      Text(
          text = stringResource(R.string.embeddinggemma_skipped_images_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
      )
    }

    if (skippedEntries.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
              imageVector = Icons.Rounded.WarningAmber,
              contentDescription = null,
              modifier = Modifier.padding(12.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
              text = stringResource(R.string.embeddinggemma_skipped_images_none),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      LazyVerticalGrid(
          columns = GridCells.Fixed(gridColumns),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(skippedEntries, key = { it.id }) { entry ->
          AsyncImage(
              model = entry.imageUri,
              contentDescription = entry.summary,
              contentScale = ContentScale.Crop,
              modifier =
                  Modifier.fillMaxWidth()
                      .aspectRatio(1f)
                      .clip(RoundedCornerShape(10.dp))
                      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                      .clickable(onClick = hapticOnClick { onScreenshotClick(entry.id) }),
          )
        }
      }
    }
  }
}
