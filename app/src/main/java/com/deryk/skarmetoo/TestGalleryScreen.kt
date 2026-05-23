package com.deryk.skarmetoo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val thumbnailMemoryCache: LruCache<Long, Bitmap> =
    object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 3).toInt()) {
      override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

private const val THUMB_SIZE = 256
private const val DISK_CACHE_DIR = "test_gallery_thumbs"
private const val JPEG_QUALITY = 85
private const val MIN_COLUMNS = 4
private const val MAX_COLUMNS = 7
private const val GRID_SPACING_DP = 2
private const val INITIAL_RENDER_ROWS = 36
private const val RENDER_ROWS_CHUNK = 24
private const val LOAD_MORE_THRESHOLD_ROWS = 8
private const val PRELOAD_ROWS = 8

private fun getDiskCacheDir(context: Context): File {
  val dir = File(context.cacheDir, DISK_CACHE_DIR)
  if (!dir.exists()) dir.mkdirs()
  return dir
}

private fun loadFromDiskCache(context: Context, imageId: Long): Bitmap? {
  val file = File(getDiskCacheDir(context), "$imageId.jpg")
  if (!file.exists()) return null
  return try {
    BitmapFactory.decodeFile(file.absolutePath)
  } catch (_: Exception) {
    file.delete()
    null
  }
}

private fun saveToDiskCache(context: Context, imageId: Long, bitmap: Bitmap) {
  try {
    val file = File(getDiskCacheDir(context), "$imageId.jpg")
    file.outputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    }
  } catch (_: Exception) {
    // Non-critical cache write failure.
  }
}

private fun queryMediaStoreImages(context: Context, bucketId: String?): List<MediaStoreImage> {
  val projection = arrayOf(MediaStore.Images.Media._ID)
  val selection = bucketId?.let { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
  val selectionArgs = bucketId?.let { arrayOf(it) }

  val results = mutableListOf<MediaStoreImage>()
  context.contentResolver
      .query(
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          "${MediaStore.Images.Media.DATE_ADDED} DESC",
      )
      ?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
          val id = cursor.getLong(idCol)
          results.add(
              MediaStoreImage(
                  id = id,
                  uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
              ),
          )
        }
      }
  return results
}

data class MediaStoreImage(
    val id: Long,
    val uri: Uri,
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TestGalleryScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    logoRes: Int = R.drawable.app_logo,
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current

  val images = remember { mutableStateListOf<MediaStoreImage>() }
  var isLoading by remember { mutableStateOf(true) }
  var lastLoadedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
  var rememberedScrollValue by rememberSaveable { mutableIntStateOf(0) }
  var pendingRestoreScrollValue by rememberSaveable { mutableIntStateOf(-1) }

  var gridViewportHeightPx by remember { mutableIntStateOf(0) }
  var gridViewportWidthPx by remember { mutableIntStateOf(0) }

  val albumThumbnails by viewModel.albumThumbnails.collectAsState()
  val selectedAlbumId by viewModel.selectedExperimentalAlbumId.collectAsState()
  val selectedAlbums by viewModel.selectedAlbums.collectAsState()
  val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()
  val albumOrder by viewModel.albumOrder.collectAsState()
  val allThumbnailUris by viewModel.allAlbumThumbnailUris.collectAsState()
  val experimentalStatuses by viewModel.experimentalStatuses.collectAsState()
  val entries by viewModel.entries.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()

  val sortedAlbums =
      remember(albumThumbnails, pinnedAlbumIds, albumOrder) {
        val pinned = albumThumbnails.filter { it.album.bucketId in pinnedAlbumIds }
        val unpinned = albumThumbnails.filter { it.album.bucketId !in pinnedAlbumIds }
        if (albumOrder.isNotEmpty()) {
          val orderMap = albumOrder.withIndex().associate { indexed -> indexed.value to indexed.index }
          val byOrder = compareBy<AlbumWithThumbnails> { orderMap[it.album.bucketId] ?: Int.MAX_VALUE }
          pinned.sortedWith(byOrder) + unpinned.sortedWith(byOrder)
        } else {
          pinned + unpinned
        }
      }

  var draggingBucketId by remember { mutableStateOf<String?>(null) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  var accumulatedDrag by remember { mutableStateOf(0f) }
  var displayAlbums by remember { mutableStateOf(sortedAlbums) }

  val allImageCount = remember(albumThumbnails) { albumThumbnails.sumOf { it.album.count } }
  val entryIdByMediaUri =
      remember(entries) {
        entries
            .asSequence()
            .filter { it.imageUri.startsWith("content://media/") }
            .associate { it.imageUri to it.id }
      }
  val selectedAlbumName =
      remember(selectedAlbumId, albumThumbnails) {
        if (selectedAlbumId == null) {
          "All"
        } else {
          albumThumbnails.firstOrNull { album -> album.album.bucketId == selectedAlbumId }?.album?.name
              ?: "All"
        }
      }

  val gridColumns by viewModel.testGalleryGridColumns.collectAsState()
  var isPinching by remember { mutableStateOf(false) }
  var pinchingPreviewColumns by remember { mutableIntStateOf(gridColumns) }
  var pendingConfirmColumns by remember { mutableStateOf<Int?>(null) }
  val effectiveColumns = if (isPinching) pinchingPreviewColumns else gridColumns

  LaunchedEffect(Unit) { viewModel.loadAlbumThumbnails() }

  LaunchedEffect(sortedAlbums) {
    if (draggingBucketId == null) {
      displayAlbums = sortedAlbums
    }
  }

  LaunchedEffect(gridColumns, isPinching) {
    if (!isPinching) {
      pinchingPreviewColumns = gridColumns
    }
  }

  LaunchedEffect(pendingConfirmColumns) {
    val cols = pendingConfirmColumns ?: return@LaunchedEffect
    delay(500)
    viewModel.setTestGalleryGridColumns(cols)
    pendingConfirmColumns = null
    isPinching = false
  }

  LaunchedEffect(selectedAlbumId) {
    val albumChanged = lastLoadedAlbumId != null && lastLoadedAlbumId != selectedAlbumId
    pendingRestoreScrollValue =
        if (albumChanged) {
          0
        } else {
          rememberedScrollValue
        }
    isLoading = true
    val loaded = withContext(Dispatchers.IO) { queryMediaStoreImages(context, selectedAlbumId) }
    images.clear()
    images.addAll(loaded)
    lastLoadedAlbumId = selectedAlbumId
    isLoading = false
  }

  val rows = remember(images.size, effectiveColumns) { images.chunked(effectiveColumns) }
  var renderedRows by remember { mutableIntStateOf(0) }

  LaunchedEffect(rows.size, pendingRestoreScrollValue) {
    renderedRows =
        when {
          rows.isEmpty() -> 0
          pendingRestoreScrollValue > 0 -> rows.size
          renderedRows <= 0 -> minOf(rows.size, INITIAL_RENDER_ROWS)
          else -> renderedRows.coerceIn(1, rows.size)
        }
  }

  LaunchedEffect(scrollState.value, isLoading) {
    if (!isLoading) {
      rememberedScrollValue = scrollState.value
    }
  }

  LaunchedEffect(isLoading, rows.size, pendingRestoreScrollValue) {
    val restoreValue = pendingRestoreScrollValue
    if (isLoading || rows.isEmpty() || restoreValue < 0) return@LaunchedEffect
    if (restoreValue > 0) {
      withTimeoutOrNull(500) {
        snapshotFlow { scrollState.maxValue }
            .filter { it > 0 }
            .first()
      }
    }
    scrollState.scrollTo(restoreValue.coerceIn(0, scrollState.maxValue))
    pendingRestoreScrollValue = -1
  }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(
          painter = painterResource(id = logoRes),
          contentDescription = stringResource(R.string.logo),
          modifier = Modifier.size(36.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
          "Skarmetoo",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.width(12.dp))

      Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(modifier = Modifier.weight(1f))

        if (isAnalysisRunning || pendingCount > 0 || analyzingCount > 0) {
          Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              modifier =
                  Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                    if (isModelReady) viewModel.analyzeUnprocessed()
                  },
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              if (analyzingCount > 1) {
                Box(
                    modifier =
                        Modifier.size(16.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                      Text(
                          text = if (analyzingCount > 5) "5+" else analyzingCount.toString(),
                          color = MaterialTheme.colorScheme.errorContainer,
                          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                          fontWeight = FontWeight.Bold,
                      )
                    }
              } else if (analyzingCount == 1 || isAnalysisRunning) {
                CircularProgressIndicator(
                    progress = { currentImageProgress },
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.errorContainer,
                )
              } else {
                Icon(
                    Icons.Rounded.Schedule,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
              }
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                  stringResource(R.string.items_left, (pendingCount + analyzingCount).toString()),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.error,
              )
            }
          }
        } else {
          Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier =
                  Modifier.clip(RoundedCornerShape(16.dp))
                      .combinedClickable(
                          onDoubleClick = { if (isModelReady) viewModel.forceAnalyzeUnprocessed() },
                          onClick = hapticOnClick {},
                      ),
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                  Icons.Rounded.CheckCircle,
                  null,
                  modifier = Modifier.size(14.dp),
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                  stringResource(R.string.done),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }
      }
    }

    if (albumThumbnails.isNotEmpty()) {
      LazyRow(
          modifier = Modifier.padding(vertical = 4.dp),
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "all") {
          TestGalleryAlbumThumbnailCard(
              albumName = stringResource(R.string.all),
              count = allImageCount,
              thumbnailUris = allThumbnailUris,
              isSelected = selectedAlbumId == null,
              onClick = hapticOnClick { viewModel.setSelectedExperimentalAlbumId(null) },
          )
        }

        itemsIndexed(
            items = displayAlbums,
            key = { _, item -> item.album.bucketId },
        ) { _, albumWithThumbs ->
          val bucketId = albumWithThumbs.album.bucketId
          val isPinned = bucketId in pinnedAlbumIds
          val isAddedForAnalysis = bucketId in selectedAlbums
          val isDragging = draggingBucketId == bucketId

          Box(
              modifier =
                  Modifier.offset {
                        IntOffset(
                            if (isDragging) dragOffsetX.roundToInt() else 0,
                            0,
                        )
                      }
                      .pointerInput(bucketId) {
                        val itemWidthPx = (80.dp + 12.dp).toPx()
                        val dragThresholdPx = 20.dp.toPx()

                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                              draggingBucketId = bucketId
                              dragOffsetX = 0f
                              accumulatedDrag = 0f
                            },
                            onDrag = { change, dragAmount ->
                              change.consume()
                              dragOffsetX += dragAmount.x
                              accumulatedDrag += abs(dragAmount.x)

                              val currentIdx =
                                  displayAlbums.indexOfFirst {
                                    it.album.bucketId == draggingBucketId
                                  }
                              if (currentIdx < 0) {
                                return@detectDragGesturesAfterLongPress
                              }

                              if (dragOffsetX > itemWidthPx * 0.5f && currentIdx < displayAlbums.lastIndex) {
                                displayAlbums =
                                    displayAlbums.toMutableList().apply {
                                      add(currentIdx + 1, removeAt(currentIdx))
                                    }
                                dragOffsetX -= itemWidthPx
                              } else if (dragOffsetX < -itemWidthPx * 0.5f && currentIdx > 0) {
                                displayAlbums =
                                    displayAlbums.toMutableList().apply {
                                      add(currentIdx - 1, removeAt(currentIdx))
                                    }
                                dragOffsetX += itemWidthPx
                              }
                            },
                            onDragEnd = {
                              if (accumulatedDrag < dragThresholdPx) {
                                val wasPinned = bucketId in pinnedAlbumIds
                                viewModel.togglePinAlbum(bucketId)
                                if (!wasPinned) {
                                  val list = displayAlbums.toMutableList()
                                  val item = list.find { it.album.bucketId == bucketId }
                                  if (item != null) {
                                    list.remove(item)
                                    val lastPinnedIdx =
                                        list.indexOfLast { it.album.bucketId in pinnedAlbumIds }
                                    list.add(lastPinnedIdx + 1, item)
                                    displayAlbums = list
                                    viewModel.updateAlbumOrder(
                                        list.map { it.album.bucketId },
                                    )
                                  }
                                }
                              } else {
                                viewModel.updateAlbumOrder(displayAlbums.map { it.album.bucketId })
                              }

                              draggingBucketId = null
                              dragOffsetX = 0f
                            },
                            onDragCancel = {
                              draggingBucketId = null
                              dragOffsetX = 0f
                            },
                        )
                      },
          ) {
            TestGalleryAlbumThumbnailCard(
                albumName = albumWithThumbs.album.name,
                count = albumWithThumbs.album.count,
                thumbnailUris = albumWithThumbs.thumbnailUris,
                isSelected = selectedAlbumId == albumWithThumbs.album.bucketId,
                isPinned = isPinned,
                isAddedForAnalysis = isAddedForAnalysis,
                isDragging = isDragging,
                onClick =
                    hapticOnClick {
                      viewModel.setSelectedExperimentalAlbumId(
                          if (selectedAlbumId == albumWithThumbs.album.bucketId) {
                            null
                          } else {
                            albumWithThumbs.album.bucketId
                          },
                      )
                    },
            )
          }
        }
      }
    }

    if (isLoading) {
      Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
      }
    } else if (images.isEmpty()) {
      Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
              Icons.Rounded.PhotoLibrary,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
              modifier = Modifier.size(72.dp),
          )
          Spacer(modifier = Modifier.size(16.dp))
          Text(
              stringResource(R.string.no_screenshots_yet),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
          )
        }
      }
    } else {
      val density = LocalDensity.current
      val spacingPx = with(density) { GRID_SPACING_DP.dp.toPx() }
      val rowHeightPx =
          remember(gridViewportWidthPx, effectiveColumns, spacingPx) {
            if (gridViewportWidthPx <= 0) return@remember 0f
            val availableWidth =
                gridViewportWidthPx - (spacingPx * 2f) - (spacingPx * (effectiveColumns - 1))
            val cellSize = (availableWidth / effectiveColumns).coerceAtLeast(1f)
            cellSize + spacingPx
          }

      LaunchedEffect(
          scrollState.value,
          renderedRows,
          rows.size,
          rowHeightPx,
          gridViewportHeightPx,
      ) {
        if (rows.isEmpty() || renderedRows >= rows.size) return@LaunchedEffect
        if (rowHeightPx <= 0f || gridViewportHeightPx <= 0) return@LaunchedEffect

        val viewportBottomPx = scrollState.value + gridViewportHeightPx
        val renderedHeightPx = renderedRows * rowHeightPx
        val triggerPx = renderedHeightPx - (LOAD_MORE_THRESHOLD_ROWS * rowHeightPx)
        if (viewportBottomPx >= triggerPx) {
          renderedRows = (renderedRows + RENDER_ROWS_CHUNK).coerceAtMost(rows.size)
        }
      }

      val firstVisibleRow =
          if (rowHeightPx > 0f) {
            (scrollState.value / rowHeightPx).toInt().coerceAtLeast(0)
          } else {
            0
          }
      val visibleRows =
          if (rowHeightPx > 0f && gridViewportHeightPx > 0) {
            ceil(gridViewportHeightPx / rowHeightPx).toInt() + 1
          } else {
            0
          }
      val loadStartRow = (firstVisibleRow - PRELOAD_ROWS).coerceAtLeast(0)
      val loadEndRowExclusive =
          (firstVisibleRow + visibleRows + PRELOAD_ROWS).coerceAtMost(renderedRows)

      Box(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .onSizeChanged {
                    gridViewportWidthPx = it.width
                    gridViewportHeightPx = it.height
                  }
                  .pointerInput(gridColumns) {
                    var localPinching = false
                    var initialPinchDistance = 0f
                    var startColumns = gridColumns

                    awaitPointerEventScope {
                      while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activePointers = event.changes.filter { it.pressed }

                        if (activePointers.isEmpty()) {
                          if (localPinching) {
                            localPinching = false
                            pendingConfirmColumns = pinchingPreviewColumns
                          }
                          continue
                        }

                        if (!localPinching && activePointers.size >= 2) {
                          localPinching = true
                          isPinching = true
                          pendingConfirmColumns = null
                          initialPinchDistance = testGalleryPinchDistance(activePointers)
                          startColumns = pinchingPreviewColumns
                        }

                        if (localPinching) {
                          event.changes.forEach { it.consume() }
                          if (activePointers.size >= 2) {
                            val currentDistance = testGalleryPinchDistance(activePointers)
                            val scale = currentDistance / initialPinchDistance
                            val newColumns =
                                (startColumns / scale)
                                    .roundToInt()
                                    .coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                            pinchingPreviewColumns = newColumns
                          }
                          if (activePointers.size < 2) {
                            localPinching = false
                            pendingConfirmColumns = pinchingPreviewColumns
                          }
                        }
                      }
                    }
                  },
      ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(GRID_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        ) {
          rows.take(renderedRows).forEachIndexed { rowIndex, row ->
            val shouldLoadRow =
                !isPinching && rowIndex >= loadStartRow && rowIndex < loadEndRowExclusive

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
            ) {
              row.forEach { image ->
                val uriString = image.uri.toString()
                val entryId = experimentalStatuses[uriString]?.first ?: entryIdByMediaUri[uriString]
                ThumbnailCell(
                    image = image,
                    shouldLoad = shouldLoadRow,
                    showPlaceholder = isPinching,
                    isClickable = entryId != null,
                    onClick = hapticOnClick { entryId?.let { onScreenshotClick(it) } },
                    modifier = Modifier.weight(1f),
                )
              }

              if (row.size < effectiveColumns) {
                repeat(effectiveColumns - row.size) {
                  Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
              }
            }
          }
        }

        PillScrollbar(
            scrollState = scrollState,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
        )
      }
    }
  }
}

private fun testGalleryPinchDistance(pointers: List<PointerInputChange>): Float {
  if (pointers.size < 2) return 0f
  val a = pointers[0].position
  val b = pointers[1].position
  return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
}

@Composable
private fun ThumbnailCell(
    image: MediaStoreImage,
    shouldLoad: Boolean,
    showPlaceholder: Boolean = false,
    isClickable: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var bitmap by remember(image.id) { mutableStateOf(thumbnailMemoryCache.get(image.id)) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(image.id, shouldLoad) {
    if (!shouldLoad || bitmap != null) return@LaunchedEffect

    val loaded =
        withContext(Dispatchers.IO) {
          thumbnailMemoryCache.get(image.id)?.let {
            return@withContext it
          }

          loadFromDiskCache(context, image.id)?.let { diskBmp ->
            thumbnailMemoryCache.put(image.id, diskBmp)
            return@withContext diskBmp
          }

          try {
            val bmp =
                context.contentResolver.loadThumbnail(
                    image.uri,
                    Size(THUMB_SIZE, THUMB_SIZE),
                    CancellationSignal(),
                )
            thumbnailMemoryCache.put(image.id, bmp)
            val appContext = context.applicationContext
            val imageId = image.id
            scope.launch(Dispatchers.IO) { saveToDiskCache(appContext, imageId, bmp) }
            bmp
          } catch (_: Exception) {
            null
          }
        }

    if (isActive) {
      bitmap = loaded
    }
  }

  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .aspectRatio(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier),
      contentAlignment = Alignment.Center,
  ) {
    val bmp = bitmap
    val shouldShowPlaceholder = showPlaceholder || (!shouldLoad && bmp == null)

    if (shouldShowPlaceholder) {
      Box(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
      )
    } else if (bmp != null) {
      Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
    }
  }
}

@Composable
private fun TestGalleryAlbumThumbnailCard(
    albumName: String,
    count: Int,
    thumbnailUris: List<Uri>,
    isSelected: Boolean,
    isPinned: Boolean = false,
    isAddedForAnalysis: Boolean = false,
    isDragging: Boolean = false,
    onClick: () -> Unit,
) {
  val analysisGlowColor = Color(0xFF4CAF50)
  val borderColor =
      when {
        isDragging -> MaterialTheme.colorScheme.tertiary
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
      }
  val backgroundColor =
      when {
        isDragging -> MaterialTheme.colorScheme.tertiaryContainer
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
      }

  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(80.dp),
  ) {
    val glowOuterShape = RoundedCornerShape(14.dp)
    val glowInnerShape = RoundedCornerShape(13.dp)

    Box(
        modifier =
            Modifier.size(80.dp)
                .then(
                    if (isAddedForAnalysis) {
                      Modifier.background(
                              analysisGlowColor.copy(alpha = 0.24f),
                              glowOuterShape,
                          )
                          .padding(1.dp)
                          .background(
                              analysisGlowColor.copy(alpha = 0.14f),
                              glowInnerShape,
                          )
                          .padding(1.dp)
                    } else {
                      Modifier
                    },
                )
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
      if (thumbnailUris.isEmpty()) {
        Icon(
            Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint =
                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(32.dp),
        )
      } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
          repeat(2) { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
              repeat(2) { col ->
                val index = row * 2 + col
                val cornerShape =
                    RoundedCornerShape(
                        topStart = if (row == 0 && col == 0) 10.dp else 2.dp,
                        topEnd = if (row == 0 && col == 1) 10.dp else 2.dp,
                        bottomStart = if (row == 1 && col == 0) 10.dp else 2.dp,
                        bottomEnd = if (row == 1 && col == 1) 10.dp else 2.dp,
                    )

                if (index < thumbnailUris.size) {
                  AsyncImage(
                      model = thumbnailUris[index],
                      contentDescription = null,
                      modifier = Modifier.weight(1f).fillMaxSize().clip(cornerShape),
                      contentScale = ContentScale.Crop,
                  )
                } else {
                  Box(
                      modifier =
                          Modifier.weight(1f)
                              .fillMaxSize()
                              .clip(cornerShape)
                              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                  )
                }
              }
            }
          }
        }
      }

      if (isPinned) {
        Box(
            modifier =
                Modifier.align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
        )
      }
    }

    Spacer(modifier = Modifier.size(4.dp))

    Text(
        text = albumName,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = "$count",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
  }
}
