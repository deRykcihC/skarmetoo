package com.deryk.skarmetoo.ui.screens

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotVectorDatabase
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DuplicateImagesScreen(
    viewModel: ScreenshotViewModel,
    onBack: () -> Unit,
    onScreenshotClick: (Long) -> Unit,
) {
  val context = LocalContext.current
  val entries by viewModel.entries.collectAsState()
  val duplicateGroups =
      entries
          .filter { it.imageHash.isNotBlank() }
          .groupBy { it.imageHash }
          .mapValues { (_, groupEntries) -> groupEntries.sortedByDescending { it.sortKey } }
          .filterValues { it.size > 1 }
          .toList()
          .sortedWith(
              compareByDescending<Pair<String, List<ScreenshotEntry>>> { it.second.size }
                  .thenByDescending { it.second.maxOfOrNull { entry -> entry.sortKey } ?: 0L })
  val duplicateEntries = duplicateGroups.flatMap { it.second }
  val albumNameByUri by
      produceState(
          initialValue = emptyMap<String, String?>(),
          key1 = duplicateEntries.map { it.imageUri },
      ) {
        value =
            withContext(Dispatchers.IO) {
              duplicateEntries.associate { entry ->
                entry.imageUri to queryAlbumName(context, entry.imageUri)
              }
            }
      }
  val efficientNetSimilarityByUri by
      produceState(
          initialValue = emptyMap<String, Int>(),
          key1 =
              duplicateGroups.map { (hash, groupEntries) ->
                hash to groupEntries.map { it.imageUri }
              },
      ) {
        value =
            withContext(Dispatchers.IO) {
              queryEfficientNetSimilarityPercent(context, duplicateGroups)
            }
      }

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
          text = stringResource(R.string.duplicate_images_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
      )
    }

    if (duplicateGroups.isEmpty()) {
      Box(
          modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHighest,
          ) {
            Icon(
                Icons.Rounded.ImageSearch,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.height(14.dp))
          Text(
              text = stringResource(R.string.duplicate_images_none),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = stringResource(R.string.duplicate_images_none_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding =
              androidx.compose.foundation.layout.PaddingValues(
                  start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Text(
              text =
                  stringResource(
                      R.string.duplicate_images_summary,
                      duplicateGroups.sumOf { it.second.size },
                      duplicateGroups.size,
                  ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 4.dp),
          )
        }

        items(duplicateGroups, key = { it.first }) { (hash, groupEntries) ->
          DuplicateImageGroupCard(
              imageHash = hash,
              entries = groupEntries,
              albumNameByUri = albumNameByUri,
              efficientNetSimilarityByUri = efficientNetSimilarityByUri,
              onScreenshotClick = onScreenshotClick,
          )
        }
      }
    }
  }
}

@Composable
private fun DuplicateImageGroupCard(
    imageHash: String,
    entries: List<ScreenshotEntry>,
    albumNameByUri: Map<String, String?>,
    efficientNetSimilarityByUri: Map<String, Int>,
    onScreenshotClick: (Long) -> Unit,
) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.duplicate_images_group_count, entries.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = imageHash.take(16) + if (imageHash.length > 16) "..." else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        items(entries, key = { it.id }) { entry ->
          val isReferenceImage = entry.id == entries.firstOrNull()?.id
          DuplicateImageTile(
              entry = entry,
              albumName = albumNameByUri[entry.imageUri],
              efficientNetSimilarityPercent =
                  if (isReferenceImage) null else efficientNetSimilarityByUri[entry.imageUri],
              modifier = Modifier.width(82.dp),
              onScreenshotClick = onScreenshotClick,
          )
        }
      }
    }
  }
}

@Composable
private fun DuplicateImageTile(
    entry: ScreenshotEntry,
    albumName: String?,
    efficientNetSimilarityPercent: Int?,
    modifier: Modifier,
    onScreenshotClick: (Long) -> Unit,
) {
  Column(
      modifier =
          modifier
              .clip(RoundedCornerShape(10.dp))
              .clickable(onClick = hapticOnClick { onScreenshotClick(entry.id) }),
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      AsyncImage(
          model = entry.imageUri,
          contentDescription = entry.summary,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
      if (efficientNetSimilarityPercent != null) {
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(6.dp),
        ) {
          Text(
              text = "$efficientNetSimilarityPercent%",
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              color = Color.White,
              fontWeight = FontWeight.Bold,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = albumName.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

private suspend fun queryEfficientNetSimilarityPercent(
    context: android.content.Context,
    duplicateGroups: List<Pair<String, List<ScreenshotEntry>>>,
): Map<String, Int> {
  val vectorDb = ScreenshotVectorDatabase(context.applicationContext)
  return try {
    val similarities = mutableMapOf<String, Int>()
    duplicateGroups.forEach { (_, entries) ->
      val referenceEntry = entries.firstOrNull() ?: return@forEach
      val referenceEmbedding =
          vectorDb.getEmbeddingByUri(referenceEntry.imageUri)?.embedding ?: return@forEach

      entries.forEach { entry ->
        val entryEmbedding = vectorDb.getEmbeddingByUri(entry.imageUri)?.embedding ?: return@forEach
        val similarity = cosineSimilarity(referenceEmbedding, entryEmbedding)
        similarities[entry.imageUri] = (similarity.coerceIn(0f, 1f) * 100f).toInt()
      }
    }
    similarities
  } finally {
    vectorDb.close()
  }
}

private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
  var dotProduct = 0f
  val len = first.size.coerceAtMost(second.size)
  for (i in 0 until len) {
    dotProduct += first[i] * second[i]
  }
  return dotProduct
}

private fun queryAlbumName(context: android.content.Context, imageUri: String): String {
  if (imageUri.isBlank()) return context.getString(R.string.unknown_album)

  val uri =
      runCatching { Uri.parse(imageUri) }.getOrNull()
          ?: return context.getString(R.string.unknown_album)

  return runCatching {
        context.contentResolver
            .query(
                uri,
                arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                null,
                null,
                null,
            )
            ?.use { cursor ->
              if (cursor.moveToFirst()) {
                val albumName =
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                albumName?.takeIf { it.isNotBlank() }
              } else {
                null
              }
            }
      }
      .getOrNull() ?: context.getString(R.string.unknown_album)
}
