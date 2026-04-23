package com.deryk.skarmetoo

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExperimentalScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    logoRes: Int = R.drawable.app_logo,
) {
  val experimentalImageUris by viewModel.experimentalImageUris.collectAsState()
  val experimentalStatuses by viewModel.experimentalStatuses.collectAsState()
  val albumThumbnails by viewModel.albumThumbnails.collectAsState()
  val entries by viewModel.entries.collectAsState()
  val gridColumns by viewModel.experimentalGridColumns.collectAsState()
  var isPinching by remember { mutableStateOf(false) }
  var pinchingPreviewColumns by remember { mutableStateOf(gridColumns) }
  var pendingConfirmColumns by remember { mutableStateOf<Int?>(null) }
  // Use preview columns during pinch/debounce, committed columns otherwise
  val effectiveColumns = if (isPinching) pinchingPreviewColumns else gridColumns
  val selectedAlbumId by viewModel.selectedExperimentalAlbumId.collectAsState()
  val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()
  val albumOrder by viewModel.albumOrder.collectAsState()
  val isExperimentalLoading by viewModel.isExperimentalLoading.collectAsState()
  val experimentalImageGeneration by viewModel.experimentalImageGeneration.collectAsState()
  val haptic = LocalHapticFeedback.current

  // Sort albums: pinned first (right after "All"), then unpinned, respecting custom order
  val sortedAlbums =
      remember(albumThumbnails, pinnedAlbumIds, albumOrder) {
        val pinned = albumThumbnails.filter { it.album.bucketId in pinnedAlbumIds }
        val unpinned = albumThumbnails.filter { it.album.bucketId !in pinnedAlbumIds }
        if (albumOrder.isNotEmpty()) {
          val orderMap = albumOrder.withIndex().associate { (i, id) -> id to i }
          val byOrder =
              compareBy<AlbumWithThumbnails> { orderMap[it.album.bucketId] ?: Int.MAX_VALUE }
          pinned.sortedWith(byOrder) + unpinned.sortedWith(byOrder)
        } else {
          pinned + unpinned
        }
      }

  // Drag-to-reorder state
  var draggingBucketId by remember { mutableStateOf<String?>(null) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  var accumulatedDrag by remember { mutableStateOf(0f) }
  var displayAlbums by remember { mutableStateOf(sortedAlbums) }

  val isModelReady by viewModel.isModelReady.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()

  val pendingCount = remember(entries) { entries.count { it.summary.isBlank() && !it.isAnalyzing } }
  val analyzingCount = remember(entries) { entries.count { it.isAnalyzing } }

  // Compute "All" count as the sum of all individual album counts — always accurate
  val allImageCount = remember(albumThumbnails) { albumThumbnails.sumOf { it.album.count } }

  // "All" album thumbnails come from the ViewModel so they persist across navigation
  val allThumbnailUris by viewModel.allAlbumThumbnailUris.collectAsState()

  // Load album thumbnails on first composition
  LaunchedEffect(Unit) { viewModel.loadAlbumThumbnails() }

  // Reload images when album selection changes
  LaunchedEffect(selectedAlbumId) { viewModel.loadExperimentalImages(selectedAlbumId) }

  // Update display list when sorted albums change (but not during active drag)
  LaunchedEffect(sortedAlbums) {
    if (draggingBucketId == null) {
      displayAlbums = sortedAlbums
    }
  }

  val scrollState = rememberScrollState()

  // Debounce: commit column change 0.5s after last pinch activity,
  // allowing further pinching during the window before confirming.
  LaunchedEffect(pendingConfirmColumns) {
    val cols = pendingConfirmColumns ?: return@LaunchedEffect
    delay(500)
    viewModel.setExperimentalGridColumns(cols)
    pendingConfirmColumns = null
    isPinching = false
  }

  Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    // Header
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
      Spacer(modifier = Modifier.weight(1f))

      if (pendingCount > 0 || analyzingCount > 0) {
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
                        text = analyzingCount.toString(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                    )
                  }
            } else if (analyzingCount == 1) {
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
                        onClick = {
                          // Single tap does nothing or maybe toast
                        },
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

    // Album selector - horizontal scroll with 2x2 thumbnail previews
    if (albumThumbnails.isNotEmpty()) {
      LazyRow(
          modifier = Modifier.padding(vertical = 4.dp),
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // "All" album item — always first, not draggable
        item(key = "all") {
          AlbumThumbnailCard(
              albumName = stringResource(R.string.all),
              count = allImageCount,
              thumbnailUris = allThumbnailUris,
              isSelected = selectedAlbumId == null,
              onClick = { viewModel.setSelectedExperimentalAlbumId(null) },
          )
        }
        // Album items — long press to pin/unpin, hold + drag to reorder
        itemsIndexed(
            items = displayAlbums,
            key = { _, item -> item.album.bucketId },
        ) { _, albumWithThumbs ->
          val bucketId = albumWithThumbs.album.bucketId
          val isPinned = bucketId in pinnedAlbumIds
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
                              haptic.performHapticFeedback(
                                  HapticFeedbackType.LongPress,
                              )
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

                              if (dragOffsetX > itemWidthPx * 0.5f &&
                                  currentIdx < displayAlbums.lastIndex) {
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
                                // Hold without drag → toggle pin
                                val wasPinned = bucketId in pinnedAlbumIds
                                viewModel.togglePinAlbum(bucketId)
                                if (!wasPinned) {
                                  // Pinning: move to front (after other pinned)
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
                                // Commit reorder
                                viewModel.updateAlbumOrder(
                                    displayAlbums.map { it.album.bucketId },
                                )
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
            AlbumThumbnailCard(
                albumName = albumWithThumbs.album.name,
                count = albumWithThumbs.album.count,
                thumbnailUris = albumWithThumbs.thumbnailUris,
                isSelected = selectedAlbumId == albumWithThumbs.album.bucketId,
                isPinned = isPinned,
                isDragging = isDragging,
                onClick = {
                  viewModel.setSelectedExperimentalAlbumId(
                      if (selectedAlbumId == albumWithThumbs.album.bucketId) {
                        null
                      } else {
                        albumWithThumbs.album.bucketId
                      })
                },
            )
          }
        }
      }
    }

    if (experimentalImageUris.isEmpty() && !isExperimentalLoading) {
      Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
              Icons.Rounded.PhotoLibrary,
              null,
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
              modifier = Modifier.size(72.dp),
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
              stringResource(R.string.no_screenshots_yet),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
          )
        }
      }
    } else {
      // Chunk URIs into rows of `gridColumns` items each
      val rows =
          remember(experimentalImageUris, effectiveColumns) {
            experimentalImageUris.chunked(effectiveColumns)
          }

      Box(
          modifier =
              Modifier.weight(1f).fillMaxWidth().pointerInput(gridColumns) {
                // Pinch-to-resize: shows grey placeholders during gesture,
                // with 0.5s debounce after release for further adjustment before committing.
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
                        // Don't commit yet — start 0.5s debounce
                        pendingConfirmColumns = pinchingPreviewColumns
                      }
                      continue
                    }

                    if (!localPinching && activePointers.size >= 2) {
                      localPinching = true
                      isPinching = true
                      // Cancel any pending debounce
                      pendingConfirmColumns = null
                      initialPinchDistance = pinchDistance(activePointers)
                      startColumns = pinchingPreviewColumns
                    }

                    if (localPinching) {
                      // Consume events so verticalScroll doesn't scroll during pinch
                      event.changes.forEach { it.consume() }
                      if (activePointers.size >= 2) {
                        val currentDistance = pinchDistance(activePointers)
                        val scale = currentDistance / initialPinchDistance
                        val newColumns = (startColumns / scale).roundToInt().coerceIn(1, 10)
                        pinchingPreviewColumns = newColumns
                      }
                      if (activePointers.size < 2) {
                        localPinching = false
                        // Don't commit yet — start 0.5s debounce
                        pendingConfirmColumns = pinchingPreviewColumns
                      }
                    }
                  }
                }
              },
      ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              row.forEachIndexed { colIndex, uri ->
                val flatIndex = rowIndex * effectiveColumns + colIndex
                val uriString = uri.toString()
                val statusPair = experimentalStatuses[uriString]
                val entryId = statusPair?.first
                val isAnalyzed = statusPair?.second ?: false

                val dotColor =
                    when {
                      statusPair == null -> null
                      isAnalyzed -> Color(0xFF4CAF50) // Green
                      else -> Color(0xFF9E9E9E) // Grey
                    }

                ExperimentalGalleryItem(
                    uri = uri,
                    dotColor = dotColor,
                    gridColumns = effectiveColumns,
                    showPlaceholder = isPinching,
                    onClick = { entryId?.let { onScreenshotClick(it) } },
                    modifier = Modifier.weight(1f),
                )
              }
              // Fill remaining columns with empty space so items maintain width
              if (row.size < effectiveColumns) {
                repeat(effectiveColumns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
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

@Composable
private fun AlbumThumbnailCard(
    albumName: String,
    count: Int,
    thumbnailUris: List<Uri>,
    isSelected: Boolean,
    isPinned: Boolean = false,
    isDragging: Boolean = false,
    onClick: () -> Unit,
) {
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
    Box(
        modifier =
            Modifier.size(80.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
      if (thumbnailUris.isEmpty()) {
        // "All" album - show icon
        Icon(
            Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint =
                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(32.dp),
        )
      } else {
        // 2x2 thumbnail grid — clip each thumbnail to rounded corners
        // so they don't poke out of the parent's rounded shape
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
                // Determine corner radii based on position in the 2x2 grid
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

      // Pin indicator for pinned albums
      if (isPinned) {
        Box(
            modifier =
                Modifier.align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(50),
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.8f),
                        RoundedCornerShape(50),
                    ),
        )
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = albumName,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        color =
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun ExperimentalGalleryItem(
    uri: Uri,
    dotColor: Color?,
    gridColumns: Int,
    showPlaceholder: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  // Use painter directly to have reliable access to state for sequential loading
  val painter =
      rememberAsyncImagePainter(
          model =
              remember(uri, gridColumns) {
                // Apply thumbnail compression based on column count.
                // Smaller thumbnails = less memory usage and faster decoding on weak processors.
                // Each thumbnail is decoded at a fixed pixel size that scales with column count.
                // Always set an explicit size to ensure Coil's memory cache uses a unique key
                // per column count — preventing stale low-res bitmaps when expanding the grid.
                val thumbnailSizePx =
                    when {
                      gridColumns > 6 -> 80 // Very small: ~80px per thumbnail
                      gridColumns > 4 -> 120 // Small: ~120px per thumbnail
                      gridColumns > 3 -> 180 // Medium: ~180px per thumbnail
                      gridColumns > 2 -> 360 // Large: ~360px per thumbnail
                      gridColumns > 1 -> 540 // XL: ~540px per thumbnail
                      else -> 720 // Full: ~720px per thumbnail
                    }

                ImageRequest.Builder(context)
                    .data(uri)
                    .scale(coil.size.Scale.FILL)
                    .size(coil.size.Size(thumbnailSizePx, thumbnailSizePx))
                    .build()
              })

  val state = painter.state
  val isLoaded = state is AsyncImagePainter.State.Success
  val isError = state is AsyncImagePainter.State.Error

  val alpha by
      animateFloatAsState(
          targetValue = if (isLoaded) 1f else 0f,
          animationSpec = tween(durationMillis = 400),
          label = "fade")

  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .aspectRatio(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .then(if (dotColor != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    if (showPlaceholder) {
      // Grey placeholder during pinch or staggered reveal — no image decoding
      Box(
          modifier =
              Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
      )
    } else {
      Image(
          painter = painter,
          contentDescription = null,
          modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
          contentScale = ContentScale.Crop,
      )
    }

    // Dot indicator: grey = in gallery, green = analyzed, no dot = not in gallery
    if (dotColor != null && !showPlaceholder) {
      Box(
          modifier =
              Modifier.align(Alignment.TopEnd)
                  .padding(4.dp)
                  .size(10.dp)
                  .background(dotColor, RoundedCornerShape(50))
                  .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                  .graphicsLayer { this.alpha = alpha },
      )
    }
  }
}

/** Euclidean distance between the first two active pointers for pinch detection. */
private fun pinchDistance(pointers: List<PointerInputChange>): Float {
  if (pointers.size < 2) return 0f
  val a = pointers[0].position
  val b = pointers[1].position
  return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
}
