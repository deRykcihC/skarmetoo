package com.deryk.skarmetoo

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()
    val albumOrder by viewModel.albumOrder.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Sort albums: pinned first (right after "All"), then unpinned, respecting custom order
    val sortedAlbums = remember(albumThumbnails, pinnedAlbumIds, albumOrder) {
        val pinned = albumThumbnails.filter { it.album.bucketId in pinnedAlbumIds }
        val unpinned = albumThumbnails.filter { it.album.bucketId !in pinnedAlbumIds }
        if (albumOrder.isNotEmpty()) {
            val orderMap = albumOrder.withIndex().associate { (i, id) -> id to i }
            val byOrder = compareBy<AlbumWithThumbnails> { orderMap[it.album.bucketId] ?: Int.MAX_VALUE }
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

    val pendingCount = remember(entries) {
        entries.count { it.summary.isBlank() && !it.isAnalyzing }
    }
    val analyzingCount = remember(entries) {
        entries.count { it.isAnalyzing }
    }

    // Compute "All" count as the sum of all individual album counts — always accurate
    val allImageCount = remember(albumThumbnails) {
        albumThumbnails.sumOf { it.album.count }
    }

    // Remember the most recent 4 images for "All" album thumbnail
    // Captured only once on initial load to avoid flickering when switching albums
    var allThumbnailUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var hasCapturedAllThumbnails by remember { mutableStateOf(false) }

    // Load album thumbnails and all images on first composition
    LaunchedEffect(Unit) {
        viewModel.loadAlbumThumbnails()
        viewModel.loadExperimentalImages()
    }

    // Capture "All" thumbnails only on initial load (before any album filtering)
    LaunchedEffect(experimentalImageUris) {
        if (!hasCapturedAllThumbnails && experimentalImageUris.isNotEmpty()) {
            allThumbnailUris = experimentalImageUris.take(4)
            hasCapturedAllThumbnails = true
        }
    }

    // Reload images when album selection changes
    LaunchedEffect(selectedAlbumId) {
        viewModel.loadExperimentalImages(selectedAlbumId)
    }

    // Update display list when sorted albums change (but not during active drag)
    LaunchedEffect(sortedAlbums) {
        if (draggingBucketId == null) {
            displayAlbums = sortedAlbums
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                stringResource(R.string.experimental),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pendingCount > 0 || analyzingCount > 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { if (isModelReady) viewModel.analyzeUnprocessed() },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (analyzingCount > 0) {
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
                ) {
                    Text(
                        "${entries.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
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
                        onClick = { selectedAlbumId = null },
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
                        modifier = Modifier
                            .offset {
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

                                        val currentIdx = displayAlbums.indexOfFirst {
                                            it.album.bucketId == draggingBucketId
                                        }
                                        if (currentIdx < 0) {
                                            return@detectDragGesturesAfterLongPress
                                        }

                                        if (dragOffsetX > itemWidthPx * 0.5f &&
                                            currentIdx < displayAlbums.lastIndex
                                        ) {
                                            displayAlbums =
                                                displayAlbums.toMutableList().apply {
                                                    add(currentIdx + 1, removeAt(currentIdx))
                                                }
                                            dragOffsetX -= itemWidthPx
                                        } else if (dragOffsetX < -itemWidthPx * 0.5f &&
                                            currentIdx > 0
                                        ) {
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
                                                val list =
                                                    displayAlbums.toMutableList()
                                                val item = list.find {
                                                    it.album.bucketId == bucketId
                                                }
                                                if (item != null) {
                                                    list.remove(item)
                                                    val lastPinnedIdx = list.indexOfLast {
                                                        it.album.bucketId in pinnedAlbumIds
                                                    }
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
                                selectedAlbumId =
                                    if (selectedAlbumId == albumWithThumbs.album.bucketId) {
                                        null
                                    } else {
                                        albumWithThumbs.album.bucketId
                                    }
                            },
                        )
                    }
                }
            }
        }

        if (experimentalImageUris.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
            val scrollState = rememberScrollState()

            // Chunk URIs into rows of `gridColumns` items each
            val rows = remember(experimentalImageUris, gridColumns) {
                experimentalImageUris.chunked(gridColumns)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(gridColumns) {
                        // Pinch-to-zoom detector that coexists with verticalScroll.
                        // Uses PointerEventPass.Initial so we see events BEFORE the child
                        // Column's verticalScroll (which uses Main pass).
                        // Only consume events when pinching (2+ fingers); otherwise let
                        // single-finger events pass through to verticalScroll.
                        var isPinching = false
                        var initialPinchDistance = 0f
                        var startColumns = gridColumns

                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val activePointers = event.changes.filter { it.pressed }

                                if (activePointers.isEmpty()) {
                                    isPinching = false
                                    continue
                                }

                                if (!isPinching && activePointers.size >= 2) {
                                    isPinching = true
                                    initialPinchDistance = pinchDistance(activePointers)
                                    startColumns = gridColumns
                                }

                                if (isPinching) {
                                    // Consume events so verticalScroll doesn't scroll during pinch
                                    event.changes.forEach { it.consume() }
                                    if (activePointers.size >= 2) {
                                        val currentDistance = pinchDistance(activePointers)
                                        val scale = currentDistance / initialPinchDistance
                                        val newColumns =
                                            (startColumns / scale).roundToInt().coerceIn(1, 10)
                                        viewModel.setExperimentalGridColumns(newColumns)
                                    }
                                    if (activePointers.size < 2) {
                                        isPinching = false
                                    }
                                }
                            }
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            row.forEach { uri ->
                                val uriString = uri.toString()
                                val statusPair = experimentalStatuses[uriString]
                                val entryId = statusPair?.first
                                val isAnalyzed = statusPair?.second ?: false

                                val dotColor = when {
                                    statusPair == null -> null
                                    isAnalyzed -> Color(0xFF4CAF50) // Green
                                    else -> Color(0xFF9E9E9E) // Grey
                                }

                                ExperimentalGalleryItem(
                                    uri = uri,
                                    dotColor = dotColor,
                                    onClick = {
                                        entryId?.let { onScreenshotClick(it) }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Fill remaining columns with empty space so items maintain width
                            if (row.size < gridColumns) {
                                repeat(gridColumns - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                PillScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 2.dp),
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
    val borderColor = when {
        isDragging -> MaterialTheme.colorScheme.tertiary
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.tertiaryContainer
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
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
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                // 2x2 thumbnail grid — clip each thumbnail to rounded corners
                // so they don't poke out of the parent's rounded shape
                Column(
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    repeat(minOf(2, thumbnailUris.size)) { row ->
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            repeat(minOf(2, thumbnailUris.size - row * 2)) { col ->
                                val index = row * 2 + col
                                // Determine corner radii based on position in the 2x2 grid
                                val cornerShape = RoundedCornerShape(
                                    topStart = if (row == 0 && col == 0) 10.dp else 2.dp,
                                    topEnd = if (row == 0 && col == 1) 10.dp else 2.dp,
                                    bottomStart = if (row == 1 && col == 0) 10.dp else 2.dp,
                                    bottomEnd = if (row == 1 && col == 1) 10.dp else 2.dp,
                                )
                                if (index < thumbnailUris.size) {
                                    AsyncImage(
                                        model = thumbnailUris[index],
                                        contentDescription = null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxSize()
                                            .clip(cornerShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
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
                    modifier = Modifier
                        .align(Alignment.TopStart)
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

@Composable
private fun ExperimentalGalleryItem(
    uri: Uri,
    dotColor: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (dotColor != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Dot indicator: grey = in gallery, green = analyzed, no dot = not in gallery
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(10.dp)
                    .background(dotColor, RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50)),
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
