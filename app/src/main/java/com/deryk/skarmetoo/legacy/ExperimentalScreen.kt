package com.deryk.skarmetoo.legacy

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ScreenshotViewModel
import com.deryk.skarmetoo.hapticOnClick
import com.deryk.skarmetoo.AlbumWithThumbnails
import com.deryk.skarmetoo.PillScrollbar

private const val EXP_GRID_SPACING_DP = 2
private const val EXP_INITIAL_RENDER_ROWS = 36
private const val EXP_RENDER_ROWS_CHUNK = 24
private const val EXP_LOAD_MORE_THRESHOLD_ROWS = 8
private const val EXP_PRELOAD_ROWS = 8

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
  var isNavigating by remember { mutableStateOf(false) }
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
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()

  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()

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
  var gridViewportHeightPx by remember { mutableIntStateOf(0) }
  var gridViewportWidthPx by remember { mutableIntStateOf(0) }

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
        modifier =
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
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
              onClick = hapticOnClick { viewModel.setSelectedExperimentalAlbumId(null) },
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
                onClick =
                    hapticOnClick {
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
      // Chunk URIs into rows of `gridColumns` items each.
      val rows =
          remember(experimentalImageUris, effectiveColumns) {
            experimentalImageUris.chunked(effectiveColumns)
          }
      var renderedRows by remember { mutableStateOf(0) }

      LaunchedEffect(rows.size) {
        renderedRows =
            when {
              rows.isEmpty() -> 0
              renderedRows <= 0 -> minOf(rows.size, EXP_INITIAL_RENDER_ROWS)
              else -> renderedRows.coerceIn(1, rows.size)
            }
      }

      val density = LocalDensity.current
      val spacingPx = with(density) { EXP_GRID_SPACING_DP.dp.toPx() }
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
        val triggerPx = renderedHeightPx - (EXP_LOAD_MORE_THRESHOLD_ROWS * rowHeightPx)
        if (viewportBottomPx >= triggerPx) {
          renderedRows = (renderedRows + EXP_RENDER_ROWS_CHUNK).coerceAtMost(rows.size)
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
      val loadStartRow = (firstVisibleRow - EXP_PRELOAD_ROWS).coerceAtLeast(0)
      val loadEndRowExclusive =
          (firstVisibleRow + visibleRows + EXP_PRELOAD_ROWS).coerceAtMost(renderedRows)

      Box(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .onSizeChanged {
                    gridViewportWidthPx = it.width
                    gridViewportHeightPx = it.height
                  }
                  .pointerInput(gridColumns) {
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
            modifier =
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(EXP_GRID_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(EXP_GRID_SPACING_DP.dp),
        ) {
          rows.take(renderedRows).forEachIndexed { rowIndex, row ->
            val shouldLoadRow =
                !isPinching && rowIndex >= loadStartRow && rowIndex < loadEndRowExclusive

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EXP_GRID_SPACING_DP.dp),
            ) {
              row.forEach { uri ->
                val uriString = uri.toString()
                val statusPair = experimentalStatuses[uriString]
                val entryId = statusPair?.first
                ExperimentalGalleryItem(
                    uri = uri,
                    gridColumns = effectiveColumns,
                    shouldLoad = shouldLoadRow,
                    showPlaceholder = isPinching,
                    isClickable = true,
                    onClick =
                        hapticOnClick {
                          if (isNavigating) return@hapticOnClick
                          if (entryId != null) {
                            onScreenshotClick(entryId)
                          } else {
                            isNavigating = true
                            viewModel.getOrCreateEntryForUri(uri) { newId ->
                              isNavigating = false
                              onScreenshotClick(newId)
                            }
                          }
                        },
                    modifier = Modifier.weight(1f),
                )
              }
              // Fill remaining columns with empty space so items maintain width
              if (row.size < effectiveColumns) {
                repeat(effectiveColumns - row.size) {
                  Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
              }
            }
          }
        }

        // Total content height based on ALL rows (not just rendered),
        // so the scrollbar reflects the full image count in the folder.
        val totalGridContentHeightPx =
            if (rows.isNotEmpty() && rowHeightPx > 0f) {
              val paddingPx = with(density) { EXP_GRID_SPACING_DP.dp.toPx() }
              rows.size * rowHeightPx + paddingPx * 2
            } else 0f

        PillScrollbar(
            scrollState = scrollState,
            totalContentHeightPx = totalGridContentHeightPx,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
        )
      }
    }

    if (isNavigating) {
      Dialog(
          onDismissRequest = {},
          properties =
              DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Box(
                modifier =
                    Modifier.size(120.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                  Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.Center,
                      modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.status_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }
                }
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
    gridColumns: Int,
    shouldLoad: Boolean,
    showPlaceholder: Boolean = false,
    isClickable: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  // Use painter directly to have reliable access to state for sequential loading.
  val painter =
      rememberAsyncImagePainter(
          model =
              remember(uri, gridColumns, shouldLoad) {
                if (!shouldLoad) {
                  return@remember null
                }

                // Apply thumbnail compression based on column count.
                // Smaller thumbnails reduce memory and decode work on weak devices.
                val thumbnailSizePx =
                    when {
                      gridColumns > 6 -> 80
                      gridColumns > 4 -> 120
                      gridColumns > 3 -> 180
                      gridColumns > 2 -> 360
                      gridColumns > 1 -> 540
                      else -> 720
                    }

                ImageRequest.Builder(context)
                    .data(uri)
                    .scale(Scale.FILL)
                    .size(Size(thumbnailSizePx, thumbnailSizePx))
                    .build()
              },
      )

  val state = painter.state
  val isLoaded = state is AsyncImagePainter.State.Success

  val alpha by
      animateFloatAsState(
          targetValue = if (isLoaded) 1f else 0f,
          animationSpec = tween(durationMillis = 400),
          label = "fade",
      )

  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .aspectRatio(1f)
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    if (showPlaceholder || !shouldLoad) {
      // Grey placeholder during pinch or when this row is outside the preload window.
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
  }
}
/** Euclidean distance between the first two active pointers for pinch detection. */
private fun pinchDistance(pointers: List<PointerInputChange>): Float {
  if (pointers.size < 2) return 0f
  val a = pointers[0].position
  val b = pointers[1].position
  return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
}
