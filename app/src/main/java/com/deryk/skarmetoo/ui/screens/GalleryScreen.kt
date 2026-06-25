package com.deryk.skarmetoo.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.EmbeddingGemma
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotTextEmbeddingDatabase
import com.deryk.skarmetoo.legacy.ScreenshotGridItem
import com.deryk.skarmetoo.ui.components.PillScrollbar
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.findComponentActivity
import com.deryk.skarmetoo.viewmodel.AlbumWithThumbnails
import com.deryk.skarmetoo.viewmodel.ClickedImageBounds
import com.deryk.skarmetoo.viewmodel.MediaStoreImage
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val thumbnailMemoryCache: LruCache<Long, Bitmap> =
    object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 3).toInt()) {
      override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }

private const val THUMB_SIZE = 256
private const val DISK_CACHE_DIR = "gallery_thumbs"
private const val JPEG_QUALITY = 85
private const val MIN_COLUMNS = 4
private const val MAX_COLUMNS = 7
private const val GRID_SPACING_DP = 2
private const val INITIAL_RENDER_ROWS = 36
private const val RENDER_ROWS_CHUNK = 24
private const val LOAD_MORE_THRESHOLD_ROWS = 8
private const val PRELOAD_ROWS = 8
private const val EMBEDDING_GEMMA_SEARCH_THRESHOLD = 0.25f
private const val EMBEDDING_GEMMA_SEARCH_LIMIT = 240

private data class AnalysisItemLayout(val topPx: Float, val heightPx: Float)

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

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GalleryScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    logoRes: Int = R.drawable.app_logo,
    isPickMode: Boolean = false,
) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  val images by viewModel.mediaStoreImages.collectAsState()
  val isLoading by viewModel.isMediaStoreLoading.collectAsState()
  var isNavigating by remember { mutableStateOf(false) }
  var lastLoadedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
  var rememberedScrollValue by rememberSaveable { mutableIntStateOf(0) }
  var pendingRestoreScrollValue by rememberSaveable { mutableIntStateOf(-1) }

  var gridViewportHeightPx by remember { mutableIntStateOf(0) }
  var gridViewportWidthPx by remember { mutableIntStateOf(0) }
  var hasScrolledOnLaunch by rememberSaveable { mutableStateOf(false) }

  val albumThumbnails by viewModel.albumThumbnails.collectAsState()
  val selectedAlbumId by viewModel.selectedExperimentalAlbumId.collectAsState()
  val selectedAlbums by viewModel.selectedAlbums.collectAsState()
  val pinnedAlbumIds by viewModel.pinnedAlbumIds.collectAsState()
  val albumOrder by viewModel.albumOrder.collectAsState()
  val allThumbnailUris by viewModel.allAlbumThumbnailUris.collectAsState()
  val experimentalStatuses by viewModel.experimentalStatuses.collectAsState()
  val entries by viewModel.entries.collectAsState()
  val activeAnalysisIds by viewModel.activeAnalysisIds.collectAsState()
  val entryProgressMap by viewModel.entryProgressMap.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  val isAnalysisPaused by viewModel.isAnalysisPaused.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()
  val isSortDescending by viewModel.isSortDescending.collectAsState()

  val viewModelSearchQuery by viewModel.searchQuery.collectAsState()
  var searchQuery by rememberSaveable { mutableStateOf(viewModelSearchQuery) }
  var allSearchEntries by remember { mutableStateOf<List<ScreenshotEntry>>(emptyList()) }

  // Sync inbound query changes from the ViewModel (e.g. tag clicked in DetailScreen)
  LaunchedEffect(viewModelSearchQuery) {
    if (viewModelSearchQuery.isNotBlank() && viewModelSearchQuery != searchQuery) {
      searchQuery = viewModelSearchQuery
    }
  }

  LaunchedEffect(entries, images.size) {
    allSearchEntries = withContext(Dispatchers.IO) { viewModel.getAllValidEntriesSnapshot() }
  }
  val embeddingGemma = remember(context) { EmbeddingGemma(context.applicationContext) }
  val textEmbeddingDb =
      remember(context) { ScreenshotTextEmbeddingDatabase(context.applicationContext) }
  var isEmbeddingGemmaReady by remember { mutableStateOf(false) }
  var isEmbeddingSearching by remember { mutableStateOf(false) }
  var isEmbeddingSearchSettled by remember { mutableStateOf(false) }
  var isEmbeddingSearchMode by rememberSaveable { mutableStateOf(true) }
  var semanticSearchScores by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
  var isEmbeddingGlowVisible by remember { mutableStateOf(false) }
  var embeddingSearchCompletion by remember { mutableFloatStateOf(0f) }
  val isEmbeddingSearchActive =
      isEmbeddingSearchMode &&
          (isEmbeddingGlowVisible ||
              (searchQuery.isNotBlank() &&
                  isEmbeddingGemmaReady &&
                  semanticSearchScores.isNotEmpty()))

  LaunchedEffect(searchQuery, semanticSearchScores, isEmbeddingGemmaReady, isEmbeddingSearchMode) {
    val shouldShowGlow =
        isEmbeddingSearchMode &&
            searchQuery.isNotBlank() &&
            isEmbeddingGemmaReady &&
            semanticSearchScores.isNotEmpty()
    if (shouldShowGlow) {
      isEmbeddingGlowVisible = true
    } else if (isEmbeddingGlowVisible) {
      delay(450)
      val stillShouldShowGlow =
          isEmbeddingSearchMode &&
              searchQuery.isNotBlank() &&
              isEmbeddingGemmaReady &&
              semanticSearchScores.isNotEmpty()
      if (!stillShouldShowGlow) {
        isEmbeddingGlowVisible = false
        embeddingSearchCompletion = 0f
      }
    }
  }

  DisposableEffect(embeddingGemma, textEmbeddingDb) {
    onDispose {
      embeddingGemma.close()
      textEmbeddingDb.close()
    }
  }

  LaunchedEffect(embeddingGemma) {
    isEmbeddingGemmaReady = withContext(Dispatchers.IO) { embeddingGemma.initialize() }
  }

  LaunchedEffect(
      searchQuery,
      allSearchEntries,
      selectedAlbumId,
      images,
      isEmbeddingGemmaReady,
      isEmbeddingSearchMode) {
        val query = searchQuery.trim()
        if (query.isBlank() || !isEmbeddingGemmaReady || !isEmbeddingSearchMode) {
          semanticSearchScores = emptyMap()
          isEmbeddingSearching = false
          isEmbeddingSearchSettled = false
          embeddingSearchCompletion = 0f
          return@LaunchedEffect
        }

        try {
          isEmbeddingSearching = true
          isEmbeddingSearchSettled = false
          isEmbeddingGlowVisible = true
          embeddingSearchCompletion = 0.30f
          semanticSearchScores = emptyMap()
          delay(300)
          embeddingSearchCompletion = 0.35f
          val queryVector = embeddingGemma.embed(query)
          if (queryVector == null) {
            semanticSearchScores = emptyMap()
            return@LaunchedEffect
          }
          embeddingSearchCompletion = 0.80f
          Log.d("GalleryScreen", "EmbeddingGemma query vector dimensions=${queryVector.size}")
          delay(180)
          embeddingSearchCompletion = 0.90f
          val visibleAlbumUris = images.map { it.uri.toString() }.toSet()
          val scores =
              textEmbeddingDb
                  .findSimilar(
                      queryVector,
                      similarityThreshold = EMBEDDING_GEMMA_SEARCH_THRESHOLD,
                      limit = EMBEDDING_GEMMA_SEARCH_LIMIT,
                  )
                  .filter { (record, _) -> record.imageUri in visibleAlbumUris }
                  .associate { (record, score) -> record.imageUri to score }
          embeddingSearchCompletion = 1f
          semanticSearchScores = scores
          isEmbeddingSearchSettled = true
          Log.d(
              "GalleryScreen",
              "EmbeddingGemma album search active for query='$query', matches=${scores.size}, album=$selectedAlbumId")
        } finally {
          isEmbeddingSearching = false
        }
      }
  var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingAnalysisFocusId by remember { mutableStateOf<Long?>(null) }
  var analysisFocusId by remember { mutableStateOf<Long?>(null) }
  var analysisFocusPulse by remember { mutableIntStateOf(0) }
  val analysisItemLayouts = remember { mutableStateMapOf<Long, AnalysisItemLayout>() }
  var isAlbumRowVisible by rememberSaveable { mutableStateOf(true) }
  var isAlbumDrawerExpanded by rememberSaveable { mutableStateOf(false) }
  val isGalleryStylePersisted by viewModel.galleryIsGalleryStyle.collectAsState()
  var isGalleryStyle by remember { mutableStateOf(isGalleryStylePersisted) }
  var isPillVisible by rememberSaveable { mutableStateOf(true) }
  var lastScrollOffset by remember { mutableIntStateOf(0) }

  LaunchedEffect(scrollState.value) {
    if (scrollState.isScrollInProgress) {
      val delta = scrollState.value - lastScrollOffset
      if (delta > 20) {
        isPillVisible = false
      } else if (delta < -20) {
        isPillVisible = true
      }
    }
    lastScrollOffset = scrollState.value
  }

  LaunchedEffect(isGalleryStyle) {
    isPillVisible = true
    viewModel.setGalleryIsGalleryStyle(isGalleryStyle)
  }

  val sortedAlbums =
      remember(albumThumbnails, pinnedAlbumIds, selectedAlbums, albumOrder) {
        val source = albumThumbnails.filter { it.album.bucketId in selectedAlbums }
        val pinned =
            albumThumbnails.filter {
              it.album.bucketId in pinnedAlbumIds && it.album.bucketId !in selectedAlbums
            }
        val unpinned =
            albumThumbnails.filter {
              it.album.bucketId !in pinnedAlbumIds && it.album.bucketId !in selectedAlbums
            }
        if (albumOrder.isNotEmpty()) {
          val orderMap =
              albumOrder.withIndex().associate { indexed -> indexed.value to indexed.index }
          val byOrder =
              compareBy<AlbumWithThumbnails> { orderMap[it.album.bucketId] ?: Int.MAX_VALUE }
          source.sortedWith(byOrder) + pinned.sortedWith(byOrder) + unpinned.sortedWith(byOrder)
        } else {
          source + pinned + unpinned
        }
      }

  var draggingBucketId by remember { mutableStateOf<String?>(null) }
  var dragOffsetX by remember { mutableStateOf(0f) }
  var dragOffsetY by remember { mutableStateOf(0f) }
  var displayAlbums by remember { mutableStateOf(sortedAlbums) }

  fun togglePinAndPositionAlbum(bucketId: String) {
    val wasPinned = bucketId in pinnedAlbumIds
    viewModel.togglePinAlbum(bucketId)
    if (!wasPinned) {
      val list = displayAlbums.toMutableList()
      val item = list.find { it.album.bucketId == bucketId }
      if (item != null) {
        list.remove(item)
        if (bucketId in selectedAlbums) {
          val lastSourceIdx = list.indexOfLast { it.album.bucketId in selectedAlbums }
          list.add(if (lastSourceIdx >= 0) lastSourceIdx + 1 else 0, item)
        } else {
          val lastPinnedIdx =
              list.indexOfLast {
                it.album.bucketId in pinnedAlbumIds && it.album.bucketId !in selectedAlbums
              }
          val insertIdx =
              if (lastPinnedIdx >= 0) {
                lastPinnedIdx + 1
              } else {
                val lastSourceIdx = list.indexOfLast { it.album.bucketId in selectedAlbums }
                if (lastSourceIdx >= 0) lastSourceIdx + 1 else 0
              }
          list.add(insertIdx, item)
        }
        displayAlbums = list
        viewModel.updateAlbumOrder(list.map { it.album.bucketId })
      }
    }
  }

  val albumRowListState = rememberLazyListState()
  val albumRowDragScope = rememberCoroutineScope()

  val allImageCount = remember(albumThumbnails) { albumThumbnails.sumOf { it.album.count } }
  val entryByMediaUri =
      remember(allSearchEntries) {
        allSearchEntries
            .asSequence()
            .filter { it.imageUri.startsWith("content://media/") }
            .associateBy { it.imageUri }
      }
  val entryIdByMediaUri = remember(entryByMediaUri) { entryByMediaUri.mapValues { it.value.id } }
  val allTags by
      remember(entryByMediaUri, images.size) {
        derivedStateOf {
          images
              .asSequence()
              .flatMap { image ->
                entryByMediaUri[image.uri.toString()]?.getTagList().orEmpty().asSequence()
              }
              .map { it.trim() }
              .filter { it.isNotBlank() }
              .groupingBy { it }
              .eachCount()
              .entries
              .sortedByDescending { it.value }
              .map { it.key }
        }
      }
  val filteredImages by
      remember(
          entryByMediaUri,
          searchQuery,
          selectedTag,
          isEmbeddingGemmaReady,
          isEmbeddingSearchMode,
          isEmbeddingSearching,
          isEmbeddingSearchSettled,
          semanticSearchScores,
          isSortDescending,
          images.size) {
            derivedStateOf {
              val query = searchQuery.trim()
              val shouldUseEmbeddingSearch =
                  query.isNotBlank() && isEmbeddingGemmaReady && isEmbeddingSearchMode
              val hasSemanticResults = query.isNotBlank() && semanticSearchScores.isNotEmpty()
              val filtered =
                  images.filter { image ->
                    val uriString = image.uri.toString()
                    val entry = entryByMediaUri[uriString]
                    val tags = entry?.getTagList().orEmpty()
                    val semanticScore = semanticSearchScores[uriString]

                    val matchesTag =
                        selectedTag == null ||
                            tags.any { tag -> tag.equals(selectedTag, ignoreCase = true) }
                    val isAnalyzed = entry != null && entry.summary.isNotBlank()
                    val matchesKeyword =
                        image.displayName.contains(query, ignoreCase = true) ||
                            entry?.summary?.contains(query, ignoreCase = true) == true ||
                            tags.any { tag -> tag.contains(query, ignoreCase = true) } ||
                            entry?.note?.contains(query, ignoreCase = true) == true
                    val matchesSearch =
                        if (query.isBlank()) {
                          true
                        } else if (shouldUseEmbeddingSearch) {
                          isEmbeddingSearchSettled && semanticScore != null
                        } else {
                          isAnalyzed && matchesKeyword
                        }

                    matchesTag && matchesSearch
                  }

              if (hasSemanticResults) {
                filtered.sortedWith(
                    compareByDescending<MediaStoreImage> { image ->
                          semanticSearchScores[image.uri.toString()] ?: Float.NEGATIVE_INFINITY
                        }
                        .thenByDescending { image ->
                          if (image.dateAdded > 0) image.dateAdded else image.id
                        })
              } else if (isSortDescending) {
                filtered.sortedByDescending { image ->
                  if (image.dateAdded > 0) image.dateAdded else image.id
                }
              } else {
                filtered.sortedBy { image ->
                  if (image.dateAdded > 0) image.dateAdded else image.id
                }
              }
            }
          }
  LaunchedEffect(allTags, selectedTag) {
    if (selectedTag != null && allTags.none { it.equals(selectedTag, ignoreCase = true) }) {
      selectedTag = null
    }
  }

  val gridColumns by viewModel.galleryGridColumns.collectAsState()
  var isPinching by remember { mutableStateOf(false) }
  var pinchingPreviewColumns by remember { mutableIntStateOf(gridColumns) }
  var pendingConfirmColumns by remember { mutableStateOf<Int?>(null) }
  val effectiveColumns =
      if (isGalleryStyle) 2 else (if (isPinching) pinchingPreviewColumns else gridColumns)

  LaunchedEffect(Unit) { viewModel.loadAlbumThumbnails() }

  LaunchedEffect(sortedAlbums) {
    if (draggingBucketId == null) {
      displayAlbums = sortedAlbums
    }
  }

  // Scroll the album row so the selected album is visible at the left on app launch
  LaunchedEffect(displayAlbums) {
    if (!hasScrolledOnLaunch && displayAlbums.isNotEmpty()) {
      if (selectedAlbumId != null) {
        val idx = displayAlbums.indexOfFirst { it.album.bucketId == selectedAlbumId }
        if (idx >= 0) {
          // +1 because index 0 in the LazyRow is the "All" item
          albumRowListState.scrollToItem(idx + 1)
        }
      }
      hasScrolledOnLaunch = true
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
    viewModel.setGalleryGridColumns(cols)
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
    viewModel.loadImagesForBucket(context, selectedAlbumId)
    lastLoadedAlbumId = selectedAlbumId
  }

  val rows = remember(filteredImages, effectiveColumns) { filteredImages.chunked(effectiveColumns) }
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
      withTimeoutOrNull(500) { snapshotFlow { scrollState.maxValue }.filter { it > 0 }.first() }
    }
    scrollState.scrollTo(restoreValue.coerceIn(0, scrollState.maxValue))
    pendingRestoreScrollValue = -1
  }

  Column(
      modifier =
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(
              Unit) {
                detectTapGestures(
                    onTap = {
                      focusManager.clearFocus()
                      keyboardController?.hide()
                    },
                )
              },
  ) {
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
      Spacer(modifier = Modifier.width(12.dp))

      Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(modifier = Modifier.weight(1f))

        if (isAnalysisPaused || isAnalysisRunning || pendingCount > 0 || analyzingCount > 0) {
          Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              modifier =
                  Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                    val activeIds = activeAnalysisIds.toList()
                    val currentTargetId = pendingAnalysisFocusId ?: analysisFocusId
                    val currentTargetIndex = activeIds.indexOf(currentTargetId)
                    val targetId =
                        if (activeIds.isEmpty()) {
                          null
                        } else {
                          activeIds[(currentTargetIndex + 1).mod(activeIds.size)]
                        }
                    if (targetId != null) {
                      val targetEntry = allSearchEntries.firstOrNull { it.id == targetId }
                      val targetUri = targetEntry?.imageUri
                      val isInCurrentAlbum =
                          targetUri != null && images.any { it.uri.toString() == targetUri }
                      val isInCurrentResults =
                          filteredImages.any { image ->
                            val uri = image.uri.toString()
                            (experimentalStatuses[uri]?.first ?: entryIdByMediaUri[uri]) == targetId
                          }

                      if (!isInCurrentResults) {
                        searchQuery = ""
                        selectedTag = null
                        semanticSearchScores = emptyMap()
                        isEmbeddingSearching = false
                        isEmbeddingSearchSettled = false
                        if (!isInCurrentAlbum) {
                          viewModel.setSelectedExperimentalAlbumId(null)
                        }
                      }
                      pendingAnalysisFocusId = targetId
                    } else if (isModelReady) {
                      viewModel.analyzeUnprocessed()
                    }
                  },
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              if (isAnalysisPaused && activeAnalysisIds.isEmpty()) {
                Icon(
                    Icons.Rounded.Pause,
                    contentDescription = stringResource(R.string.pause),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
              } else if (analyzingCount > 1) {
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

    LazyRow(
        modifier = Modifier.padding(bottom = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        FilterChip(
            selected = true,
            onClick =
                hapticOnClick {
                  isAlbumRowVisible = !isAlbumRowVisible
                  if (!isAlbumRowVisible) isAlbumDrawerExpanded = false
                },
            label = {
              Icon(
                  if (isAlbumRowVisible) {
                    Icons.Rounded.KeyboardArrowDown
                  } else {
                    Icons.Rounded.KeyboardArrowUp
                  },
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
              )
            },
            shape = RoundedCornerShape(20.dp),
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        )
      }
      item {
        val targetGlowAlpha =
            when {
              isEmbeddingSearching -> embeddingSearchCompletion.coerceIn(0.30f, 1f)
              isEmbeddingSearchActive -> 1f
              else -> 0f
            }

        val glowAlpha by
            animateFloatAsState(
                targetValue = targetGlowAlpha,
                animationSpec =
                    tween(
                        durationMillis = if (targetGlowAlpha >= 0.80f) 650 else 350,
                        easing = FastOutSlowInEasing,
                    ),
                label = "EmbeddingSearchGlow")

        val glowColor = Color(0xFF2196F3)
        Box(
            modifier =
                Modifier.zIndex(-1f)
                    .dispersedGlow(
                        color = glowColor,
                        alpha = 0.25f * glowAlpha,
                        glowRadius = 20.dp,
                        borderRadius = 20.dp,
                        horizontalInset = -8.dp)
                    .dispersedGlow(
                        color = glowColor,
                        alpha = 0.45f * glowAlpha,
                        glowRadius = 8.dp,
                        borderRadius = 20.dp,
                        horizontalInset = -4.dp),
            contentAlignment = Alignment.Center,
        ) {
          SearchPill(
              searchQuery = searchQuery,
              onSearchQueryChange = { q -> searchQuery = q },
              isEmbeddingSearchMode = isEmbeddingSearchMode,
              onSearchModeToggle = {
                isEmbeddingSearchMode = !isEmbeddingSearchMode
                semanticSearchScores = emptyMap()
                isEmbeddingSearching = false
                isEmbeddingSearchSettled = false
                isEmbeddingGlowVisible = false
                embeddingSearchCompletion = 0f
              },
          )
        }
      }
      item {
        FilterChip(
            selected = true,
            onClick = hapticOnClick { viewModel.toggleSortOrder() },
            label = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isSortDescending) Icons.Rounded.South else Icons.Rounded.North,
                    null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (isSortDescending) {
                      stringResource(R.string.newest_first)
                    } else {
                      stringResource(R.string.oldest_first)
                    },
                    fontWeight = FontWeight.Bold,
                )
              }
            },
            shape = RoundedCornerShape(20.dp),
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        )
      }
      items(allTags, key = { it }) { tag ->
        FilterChip(
            selected = selectedTag == tag,
            onClick = hapticOnClick { selectedTag = if (selectedTag == tag) null else tag },
            label = {
              Text(
                  tag,
                  fontWeight = FontWeight.SemiBold,
              )
            },
            shape = RoundedCornerShape(20.dp),
        )
      }
    }

    AnimatedVisibility(
        visible = isAlbumRowVisible && albumThumbnails.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()) {
          BoxWithConstraints(
              modifier = Modifier.fillMaxWidth(),
          ) {
            val horizontalPadding = 16.dp
            val albumSpacing = 12.dp
            val availableWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp)
            val dynamicColumns =
                ((availableWidth + albumSpacing) / (80.dp + albumSpacing)).toInt().coerceAtLeast(4)
            val albumSquareSize =
                ((availableWidth - albumSpacing * (dynamicColumns - 1)) / dynamicColumns).coerceIn(
                    64.dp, 96.dp)
            val drawerVerticalPadding = 4.dp
            var measuredAlbumCardHeightPx by remember(albumSquareSize) { mutableIntStateOf(0) }
            val albumCardHeight =
                if (measuredAlbumCardHeightPx > 0) {
                  with(LocalDensity.current) { measuredAlbumCardHeightPx.toDp() }
                } else {
                  albumSquareSize + 36.dp
                }
            val drawerRows =
                ceil((displayAlbums.size + 1).toFloat() / dynamicColumns).toInt().coerceAtLeast(1)
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val drawerHeight =
                ((albumCardHeight * drawerRows) +
                        (albumSpacing * (drawerRows - 1)) +
                        (drawerVerticalPadding * 2))
                    .coerceAtMost(screenHeight * 0.56f)
            val compactRowHeight = albumCardHeight + (drawerVerticalPadding * 2)
            val albumContentAlpha = remember { Animatable(1f) }
            var hasAlbumDrawerTransitioned by remember { mutableStateOf(false) }

            LaunchedEffect(isAlbumDrawerExpanded) {
              if (hasAlbumDrawerTransitioned) {
                albumContentAlpha.snapTo(0.92f)
                albumContentAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(160, easing = FastOutSlowInEasing),
                )
              } else {
                hasAlbumDrawerTransitioned = true
              }
            }

            val animatedAlbumAreaHeight by
                animateDpAsState(
                    targetValue = if (isAlbumDrawerExpanded) drawerHeight else compactRowHeight,
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    label = "albumDrawerHeight",
                )
            val albumDrawerGestureModifier =
                Modifier.pointerInput(isAlbumDrawerExpanded, drawerHeight, drawerRows) {
                  val expandSwipeThreshold = 20.dp.toPx()
                  val collapseSwipeThreshold =
                      maxOf(
                          112.dp.toPx(),
                          drawerHeight.toPx() * 0.38f,
                          (84.dp + 28.dp * (drawerRows - 1).coerceAtLeast(0).toFloat()).toPx(),
                      )
                  val collapseVerticalDominanceRatio = 1.45f
                  awaitEachGesture {
                    val down =
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                    var totalX = 0f
                    var totalY = 0f
                    var isPressed = true

                    while (isPressed) {
                      val event = awaitPointerEvent(PointerEventPass.Initial)
                      val change = event.changes.firstOrNull { it.id == down.id } ?: break
                      val delta = change.positionChange()
                      totalX += delta.x
                      totalY += delta.y
                      isPressed = change.pressed
                    }

                    val isExpandSwipe =
                        !isAlbumDrawerExpanded &&
                            totalY >= expandSwipeThreshold &&
                            abs(totalY) > abs(totalX)
                    val isCollapseSwipe =
                        isAlbumDrawerExpanded &&
                            totalY <= -collapseSwipeThreshold &&
                            abs(totalY) > abs(totalX) * collapseVerticalDominanceRatio
                    when {
                      isExpandSwipe -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isAlbumDrawerExpanded = true
                      }
                      isCollapseSwipe -> {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isAlbumDrawerExpanded = false
                      }
                    }
                  }
                }

            CompositionLocalProvider(LocalOverscrollFactory provides null) {
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .height(animatedAlbumAreaHeight)
                          .clipToBounds()
                          .graphicsLayer { alpha = albumContentAlpha.value }
                          .then(albumDrawerGestureModifier)) {
                    if (!isAlbumDrawerExpanded) {
                      LazyRow(
                          state = albumRowListState,
                          modifier = Modifier.padding(vertical = 4.dp),
                          contentPadding = PaddingValues(horizontal = 16.dp),
                          horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        item(key = "all") {
                          GalleryAlbumThumbnailCard(
                              albumName = stringResource(R.string.all),
                              count = allImageCount,
                              thumbnailUris = allThumbnailUris,
                              isSelected = selectedAlbumId == null,
                              size = albumSquareSize,
                              onClick =
                                  hapticOnClick { viewModel.setSelectedExperimentalAlbumId(null) },
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
                                  (if (isDragging) Modifier else Modifier.animateItem())
                                      .offset {
                                        IntOffset(
                                            if (isDragging) dragOffsetX.roundToInt() else 0,
                                            if (isDragging) dragOffsetY.roundToInt() else 0,
                                        )
                                      }
                                      .zIndex(if (isDragging) 1f else 0f)
                                      .pointerInput(bucketId) {
                                        val itemWidthPx = (albumSquareSize + albumSpacing).toPx()
                                        val leftEdgeScrollThresholdPx = 180.dp.toPx()
                                        val rightEdgeScrollThresholdPx = 96.dp.toPx()
                                        val maxEdgeScrollStepPx = 32.dp.toPx()
                                        var edgeScrollJob: kotlinx.coroutines.Job? = null
                                        var latestPointerXInItem: Float? = null

                                        fun moveDraggedAlbumIfNeeded(thresholdFraction: Float) {
                                          val currentIdx =
                                              displayAlbums.indexOfFirst {
                                                it.album.bucketId == draggingBucketId
                                              }
                                          if (currentIdx < 0) return

                                          val currentRowPosition = currentIdx + 1
                                          val columnDelta =
                                              when {
                                                dragOffsetX > itemWidthPx * thresholdFraction -> 1
                                                dragOffsetX < -itemWidthPx * thresholdFraction -> -1
                                                else -> 0
                                              }

                                          if (columnDelta != 0) {
                                            val targetRowPosition =
                                                (currentRowPosition + columnDelta).coerceIn(
                                                    1, displayAlbums.size)
                                            val targetIdx = targetRowPosition - 1
                                            if (targetIdx != currentIdx) {
                                              displayAlbums =
                                                  displayAlbums.toMutableList().apply {
                                                    add(targetIdx, removeAt(currentIdx))
                                                  }
                                              dragOffsetX -=
                                                  (targetRowPosition - currentRowPosition) *
                                                      itemWidthPx
                                            }
                                          }
                                        }

                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                              edgeScrollJob?.cancel()
                                              draggingBucketId = bucketId
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                              latestPointerXInItem = null
                                              edgeScrollJob =
                                                  albumRowDragScope.launch {
                                                    while (isActive &&
                                                        draggingBucketId == bucketId) {
                                                      val pointerXInItem = latestPointerXInItem
                                                      val layoutInfo = albumRowListState.layoutInfo
                                                      val itemInfo =
                                                          layoutInfo.visibleItemsInfo.firstOrNull {
                                                            it.key == bucketId
                                                          }
                                                      val scrollDelta =
                                                          if (pointerXInItem == null ||
                                                              itemInfo == null) {
                                                            0f
                                                          } else {
                                                            val pointerXInViewport =
                                                                itemInfo.offset +
                                                                    dragOffsetX +
                                                                    pointerXInItem
                                                            val viewportStart =
                                                                layoutInfo.viewportStartOffset
                                                            val viewportEnd =
                                                                layoutInfo.viewportEndOffset
                                                            when {
                                                              pointerXInViewport <
                                                                  viewportStart +
                                                                      leftEdgeScrollThresholdPx ->
                                                                  -((viewportStart +
                                                                          leftEdgeScrollThresholdPx -
                                                                          pointerXInViewport) /
                                                                          leftEdgeScrollThresholdPx)
                                                                      .coerceIn(0f, 1f) *
                                                                      maxEdgeScrollStepPx
                                                              pointerXInViewport >
                                                                  viewportEnd -
                                                                      rightEdgeScrollThresholdPx ->
                                                                  ((pointerXInViewport -
                                                                          (viewportEnd -
                                                                              rightEdgeScrollThresholdPx)) /
                                                                          rightEdgeScrollThresholdPx)
                                                                      .coerceIn(0f, 1f) *
                                                                      maxEdgeScrollStepPx
                                                              else -> 0f
                                                            }
                                                          }

                                                      if (scrollDelta != 0f) {
                                                        val consumed =
                                                            albumRowListState.scrollBy(scrollDelta)
                                                        if (consumed != 0f) {
                                                          dragOffsetX += consumed
                                                          moveDraggedAlbumIfNeeded(
                                                              thresholdFraction = 1.1f)
                                                        }
                                                      }
                                                      delay(16L)
                                                    }
                                                  }
                                            },
                                            onDrag = { change, dragAmount ->
                                              change.consume()
                                              latestPointerXInItem = change.position.x
                                              dragOffsetX += dragAmount.x
                                              dragOffsetY += dragAmount.y

                                              moveDraggedAlbumIfNeeded(thresholdFraction = 0.5f)
                                            },
                                            onDragEnd = {
                                              edgeScrollJob?.cancel()
                                              edgeScrollJob = null
                                              viewModel.updateAlbumOrder(
                                                  displayAlbums.map { it.album.bucketId })
                                              draggingBucketId = null
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                              edgeScrollJob?.cancel()
                                              edgeScrollJob = null
                                              draggingBucketId = null
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                            },
                                        )
                                      },
                          ) {
                            GalleryAlbumThumbnailCard(
                                albumName = albumWithThumbs.album.name,
                                count = albumWithThumbs.album.count,
                                thumbnailUris = albumWithThumbs.thumbnailUris,
                                isSelected = selectedAlbumId == albumWithThumbs.album.bucketId,
                                isPinned = isPinned,
                                isAddedForAnalysis = isAddedForAnalysis,
                                isDragging = isDragging,
                                size = albumSquareSize,
                                onDoubleClick = { togglePinAndPositionAlbum(bucketId) },
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
                    } else {
                      LazyVerticalGrid(
                          columns = GridCells.Fixed(dynamicColumns),
                          modifier = Modifier.fillMaxWidth().height(drawerHeight),
                          contentPadding =
                              PaddingValues(
                                  start = horizontalPadding,
                                  end = horizontalPadding,
                                  top = drawerVerticalPadding,
                                  bottom = drawerVerticalPadding,
                              ),
                          horizontalArrangement = Arrangement.spacedBy(albumSpacing),
                          verticalArrangement = Arrangement.spacedBy(albumSpacing),
                      ) {
                        item(key = "all") {
                          Box(
                              modifier =
                                  Modifier.fillMaxWidth().onSizeChanged {
                                    measuredAlbumCardHeightPx =
                                        maxOf(measuredAlbumCardHeightPx, it.height)
                                  },
                              contentAlignment = Alignment.TopCenter,
                          ) {
                            GalleryAlbumThumbnailCard(
                                albumName = stringResource(R.string.all),
                                count = allImageCount,
                                thumbnailUris = allThumbnailUris,
                                isSelected = selectedAlbumId == null,
                                size = albumSquareSize,
                                onClick =
                                    hapticOnClick {
                                      viewModel.setSelectedExperimentalAlbumId(null)
                                      isAlbumDrawerExpanded = false
                                    },
                            )
                          }
                        }

                        gridItems(
                            items = displayAlbums,
                            key = { it.album.bucketId },
                        ) { albumWithThumbs ->
                          val bucketId = albumWithThumbs.album.bucketId
                          val isDragging = draggingBucketId == bucketId
                          Box(
                              modifier =
                                  (if (isDragging) Modifier else Modifier.animateItem())
                                      .fillMaxWidth()
                                      .offset {
                                        IntOffset(
                                            if (isDragging) dragOffsetX.roundToInt() else 0,
                                            if (isDragging) dragOffsetY.roundToInt() else 0,
                                        )
                                      }
                                      .zIndex(if (isDragging) 1f else 0f)
                                      .pointerInput(
                                          bucketId,
                                          dynamicColumns,
                                          albumSquareSize,
                                          albumCardHeight,
                                      ) {
                                        val itemWidthPx = (albumSquareSize + albumSpacing).toPx()
                                        val itemHeightPx = (albumCardHeight + albumSpacing).toPx()
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                              draggingBucketId = bucketId
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                              change.consume()
                                              dragOffsetX += dragAmount.x
                                              dragOffsetY += dragAmount.y

                                              val currentIdx =
                                                  displayAlbums.indexOfFirst {
                                                    it.album.bucketId == draggingBucketId
                                                  }
                                              if (currentIdx < 0) {
                                                return@detectDragGesturesAfterLongPress
                                              }

                                              val currentGridPosition = currentIdx + 1
                                              val currentRow = currentGridPosition / dynamicColumns
                                              val currentColumn =
                                                  currentGridPosition % dynamicColumns
                                              val columnDelta =
                                                  when {
                                                    dragOffsetX > itemWidthPx * 0.5f -> 1
                                                    dragOffsetX < -itemWidthPx * 0.5f -> -1
                                                    else -> 0
                                                  }
                                              val rowDelta =
                                                  when {
                                                    dragOffsetY > itemHeightPx * 0.5f -> 1
                                                    dragOffsetY < -itemHeightPx * 0.5f -> -1
                                                    else -> 0
                                                  }

                                              if (columnDelta != 0 || rowDelta != 0) {
                                                val targetGridPosition =
                                                    (currentGridPosition +
                                                            (rowDelta * dynamicColumns) +
                                                            columnDelta)
                                                        .coerceIn(1, displayAlbums.size)
                                                val targetIdx = targetGridPosition - 1
                                                if (targetIdx != currentIdx) {
                                                  displayAlbums =
                                                      displayAlbums.toMutableList().apply {
                                                        add(targetIdx, removeAt(currentIdx))
                                                      }

                                                  val targetRow =
                                                      targetGridPosition / dynamicColumns
                                                  val targetColumn =
                                                      targetGridPosition % dynamicColumns
                                                  dragOffsetX -=
                                                      (targetColumn - currentColumn) * itemWidthPx
                                                  dragOffsetY -=
                                                      (targetRow - currentRow) * itemHeightPx
                                                }
                                              }
                                            },
                                            onDragEnd = {
                                              viewModel.updateAlbumOrder(
                                                  displayAlbums.map { it.album.bucketId })
                                              draggingBucketId = null
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                              draggingBucketId = null
                                              dragOffsetX = 0f
                                              dragOffsetY = 0f
                                            },
                                        )
                                      },
                              contentAlignment = Alignment.TopCenter,
                          ) {
                            GalleryAlbumThumbnailCard(
                                albumName = albumWithThumbs.album.name,
                                count = albumWithThumbs.album.count,
                                thumbnailUris = albumWithThumbs.thumbnailUris,
                                isSelected = selectedAlbumId == bucketId,
                                isPinned = bucketId in pinnedAlbumIds,
                                isAddedForAnalysis = bucketId in selectedAlbums,
                                size = albumSquareSize,
                                onDoubleClick = { togglePinAndPositionAlbum(bucketId) },
                                onClick =
                                    hapticOnClick {
                                      viewModel.setSelectedExperimentalAlbumId(
                                          if (selectedAlbumId == bucketId) null else bucketId,
                                      )
                                      isAlbumDrawerExpanded = false
                                    },
                            )
                          }
                        }
                      }
                    }
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
    } else if (isEmbeddingSearchMode &&
        isEmbeddingGemmaReady &&
        searchQuery.isNotBlank() &&
        (isEmbeddingSearching || !isEmbeddingSearchSettled)) {
      Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(
              modifier = Modifier.size(36.dp),
              strokeWidth = 3.dp,
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.primaryContainer,
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
              stringResource(R.string.searching),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
              searchQuery.trim(),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    } else if (filteredImages.isEmpty()) {
      Box(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
              Icons.Rounded.SearchOff,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
              modifier = Modifier.size(72.dp),
          )
          Spacer(modifier = Modifier.size(16.dp))
          Text(
              stringResource(R.string.no_results_found),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              stringResource(R.string.no_results_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
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

      val pendingAnalysisIndex =
          pendingAnalysisFocusId?.let { targetId ->
            filteredImages.indexOfFirst { image ->
              val uri = image.uri.toString()
              (experimentalStatuses[uri]?.first ?: entryIdByMediaUri[uri]) == targetId
            }
          } ?: -1
      val pendingAnalysisLayout =
          pendingAnalysisFocusId?.let { targetId -> analysisItemLayouts[targetId] }

      LaunchedEffect(
          pendingAnalysisFocusId,
          pendingAnalysisIndex,
          pendingAnalysisLayout,
          isGalleryStyle,
          effectiveColumns,
          renderedRows,
          rowHeightPx,
          gridViewportHeightPx,
          isLoading,
          pendingRestoreScrollValue,
      ) {
        val targetId = pendingAnalysisFocusId ?: return@LaunchedEffect
        if (isLoading || pendingRestoreScrollValue >= 0 || pendingAnalysisIndex < 0) {
          return@LaunchedEffect
        }

        val requiredRows =
            if (isGalleryStyle) {
              (pendingAnalysisIndex / 2) + 1
            } else {
              (pendingAnalysisIndex / effectiveColumns) + 1
            }
        if (renderedRows < requiredRows) {
          renderedRows = requiredRows.coerceAtMost(rows.size)
          return@LaunchedEffect
        }

        val targetScroll =
            if (isGalleryStyle) {
              val layout = pendingAnalysisLayout ?: return@LaunchedEffect
              layout.topPx - ((gridViewportHeightPx - layout.heightPx) / 2f)
            } else {
              if (rowHeightPx <= 0f) return@LaunchedEffect
              val row = pendingAnalysisIndex / effectiveColumns
              (row * rowHeightPx) - ((gridViewportHeightPx - rowHeightPx) / 2f)
            }

        scrollState.animateScrollTo(targetScroll.roundToInt().coerceIn(0, scrollState.maxValue))
        analysisFocusId = targetId
        analysisFocusPulse += 1
        pendingAnalysisFocusId = null
      }

      Box(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .onSizeChanged {
                    gridViewportWidthPx = it.width
                    gridViewportHeightPx = it.height
                  }
                  .then(
                      if (isGalleryStyle) {
                        Modifier
                      } else {
                        Modifier.pointerInput(gridColumns) {
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
                                initialPinchDistance = galleryPinchDistance(activePointers)
                                startColumns = pinchingPreviewColumns
                              }

                              if (localPinching) {
                                event.changes.forEach { it.consume() }
                                if (activePointers.size >= 2) {
                                  val currentDistance = galleryPinchDistance(activePointers)
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
                        }
                      }),
      ) {
        Column(
            modifier =
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(GRID_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        ) {
          if (isGalleryStyle) {
            val displayedImages = filteredImages.take(renderedRows * 2)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              val leftColumnImages =
                  displayedImages.filterIndexed { index, image: MediaStoreImage -> index % 2 == 0 }
              val rightColumnImages =
                  displayedImages.filterIndexed { index, image: MediaStoreImage -> index % 2 == 1 }

              Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                leftColumnImages.forEach { image: MediaStoreImage ->
                  val uriString = image.uri.toString()
                  val entryId =
                      experimentalStatuses[uriString]?.first ?: entryIdByMediaUri[uriString]
                  val entry =
                      entryByMediaUri[uriString]
                          ?: ScreenshotEntry(
                              id = entryId ?: -1L, imageUri = uriString, imageHash = "")
                  val isActivelyAnalyzing =
                      activeAnalysisIds.contains(entry.id) ||
                          entry.isAnalyzing ||
                          entryProgressMap.containsKey(entry.id)
                  var itemBounds by remember { mutableStateOf<ClickedImageBounds?>(null) }
                  AnalysisFocusFrame(
                      pulseKey = analysisFocusPulse.takeIf { analysisFocusId == entry.id },
                      cornerRadius = 16.dp,
                      modifier =
                          Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            val size = coords.size
                            itemBounds =
                                ClickedImageBounds(
                                    pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                            analysisItemLayouts[entry.id] =
                                AnalysisItemLayout(
                                    topPx = coords.positionInParent().y,
                                    heightPx = size.height.toFloat(),
                                )
                          }) {
                        ScreenshotGridItem(
                            entry = entry,
                            currentImageProgress =
                                entryProgressMap[entry.id]
                                    ?: if (isActivelyAnalyzing) currentImageProgress else 0f,
                            isActivelyAnalyzing = isActivelyAnalyzing,
                            isQueueRunning = isAnalysisRunning,
                            onClick =
                                hapticOnClick {
                                  viewModel.setClickedImageBounds(itemBounds)
                                  if (isPickMode) {
                                    val activity = context.findComponentActivity()
                                    if (activity != null) {
                                      val resultIntent =
                                          android.content.Intent().apply {
                                            data = image.uri
                                            flags =
                                                android.content.Intent
                                                    .FLAG_GRANT_READ_URI_PERMISSION
                                          }
                                      activity.setResult(
                                          android.app.Activity.RESULT_OK, resultIntent)
                                      activity.finish()
                                    }
                                  } else {
                                    if (entryId != null) {
                                      onScreenshotClick(entryId)
                                    } else {
                                      isNavigating = true
                                      viewModel.getOrCreateEntryForUri(image.uri) { newId ->
                                        isNavigating = false
                                        if (newId > 0L) {
                                          onScreenshotClick(newId)
                                        }
                                      }
                                    }
                                  }
                                },
                        )
                      }
                }
              }

              Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                rightColumnImages.forEach { image: MediaStoreImage ->
                  val uriString = image.uri.toString()
                  val entryId =
                      experimentalStatuses[uriString]?.first ?: entryIdByMediaUri[uriString]
                  val entry =
                      entryByMediaUri[uriString]
                          ?: ScreenshotEntry(
                              id = entryId ?: -1L, imageUri = uriString, imageHash = "")
                  val isActivelyAnalyzing =
                      activeAnalysisIds.contains(entry.id) ||
                          entry.isAnalyzing ||
                          entryProgressMap.containsKey(entry.id)
                  var itemBounds by remember { mutableStateOf<ClickedImageBounds?>(null) }
                  AnalysisFocusFrame(
                      pulseKey = analysisFocusPulse.takeIf { analysisFocusId == entry.id },
                      cornerRadius = 16.dp,
                      modifier =
                          Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            val size = coords.size
                            itemBounds =
                                ClickedImageBounds(
                                    pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                            analysisItemLayouts[entry.id] =
                                AnalysisItemLayout(
                                    topPx = coords.positionInParent().y,
                                    heightPx = size.height.toFloat(),
                                )
                          }) {
                        ScreenshotGridItem(
                            entry = entry,
                            currentImageProgress =
                                entryProgressMap[entry.id]
                                    ?: if (isActivelyAnalyzing) currentImageProgress else 0f,
                            isActivelyAnalyzing = isActivelyAnalyzing,
                            isQueueRunning = isAnalysisRunning,
                            onClick =
                                hapticOnClick {
                                  viewModel.setClickedImageBounds(itemBounds)
                                  if (isPickMode) {
                                    val activity = context.findComponentActivity()
                                    if (activity != null) {
                                      val resultIntent =
                                          android.content.Intent().apply {
                                            data = image.uri
                                            flags =
                                                android.content.Intent
                                                    .FLAG_GRANT_READ_URI_PERMISSION
                                          }
                                      activity.setResult(
                                          android.app.Activity.RESULT_OK, resultIntent)
                                      activity.finish()
                                    }
                                  } else {
                                    if (entryId != null) {
                                      onScreenshotClick(entryId)
                                    } else {
                                      isNavigating = true
                                      viewModel.getOrCreateEntryForUri(image.uri) { newId ->
                                        isNavigating = false
                                        if (newId > 0L) {
                                          onScreenshotClick(newId)
                                        }
                                      }
                                    }
                                  }
                                },
                        )
                      }
                }
              }
            }
          } else {
            rows.take(renderedRows).forEachIndexed { rowIndex, row ->
              val shouldLoadRow =
                  !isPinching && rowIndex >= loadStartRow && rowIndex < loadEndRowExclusive

              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
              ) {
                row.forEach { image ->
                  val uriString = image.uri.toString()
                  val entryId =
                      experimentalStatuses[uriString]?.first ?: entryIdByMediaUri[uriString]
                  var itemBounds by remember { mutableStateOf<ClickedImageBounds?>(null) }
                  ThumbnailCell(
                      image = image,
                      shouldLoad = shouldLoadRow,
                      showPlaceholder = isPinching,
                      focusPulseKey =
                          analysisFocusPulse.takeIf {
                            entryId != null && analysisFocusId == entryId
                          },
                      isClickable = true,
                      onClick =
                          hapticOnClick {
                            viewModel.setClickedImageBounds(itemBounds)
                            if (isPickMode) {
                              val activity = context.findComponentActivity()
                              if (activity != null) {
                                val resultIntent =
                                    android.content.Intent().apply {
                                      data = image.uri
                                      flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                activity.setResult(android.app.Activity.RESULT_OK, resultIntent)
                                activity.finish()
                              }
                            } else {
                              if (entryId != null) {
                                onScreenshotClick(entryId)
                              } else {
                                isNavigating = true
                                viewModel.getOrCreateEntryForUri(image.uri) { newId ->
                                  isNavigating = false
                                  if (newId > 0L) {
                                    onScreenshotClick(newId)
                                  }
                                }
                              }
                            }
                          },
                      modifier =
                          Modifier.weight(1f).onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            val size = coords.size
                            itemBounds =
                                ClickedImageBounds(
                                    pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
                          },
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
        }

        // Only show scrollbar in grid mode, not in gallery/carousel style
        if (!isGalleryStyle) {
          // Total content height based on ALL rows (not just rendered),
          // so the scrollbar reflects the full image count in the folder.
          val totalGridContentHeightPx =
              if (rows.isNotEmpty() && rowHeightPx > 0f) {
                val paddingPx = with(density) { GRID_SPACING_DP.dp.toPx() }
                rows.size * rowHeightPx + paddingPx * 2
              } else 0f

          PillScrollbar(
              scrollState = scrollState,
              totalContentHeightPx = totalGridContentHeightPx,
              modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
          )
        }

        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()
        val selectedOffsetAnim = remember {
          Animatable(if (isGalleryStyle) 0.dp else 56.dp, Dp.VectorConverter)
        }

        // Keep the Animatable in sync with programmatic isGalleryStyle changes (e.g. from taps)
        LaunchedEffect(isGalleryStyle) {
          selectedOffsetAnim.animateTo(
              targetValue = if (isGalleryStyle) 0.dp else 56.dp,
              animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f))
        }

        val pillOffsetY by
            animateDpAsState(
                targetValue = if (isPillVisible) 0.dp else 100.dp,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                label = "PillVisibilityOffset")

        // Float segmented toggle pills at the bottom middle of the Gallery screen
        Surface(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .offset(y = pillOffsetY)
                    .width(120.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.CircleShape)
                    .pointerInput(Unit) {
                      detectHorizontalDragGestures(
                          onDragStart = { coroutineScope.launch { selectedOffsetAnim.stop() } },
                          onDragEnd = {
                            val targetValue = if (selectedOffsetAnim.value < 28.dp) 0.dp else 56.dp
                            val targetStyle = targetValue == 0.dp
                            if (targetStyle != isGalleryStyle) {
                              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                              isGalleryStyle = targetStyle
                            }
                            coroutineScope.launch {
                              selectedOffsetAnim.animateTo(
                                  targetValue = targetValue,
                                  animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f))
                            }
                          },
                          onDragCancel = {
                            val targetValue = if (selectedOffsetAnim.value < 28.dp) 0.dp else 56.dp
                            coroutineScope.launch {
                              selectedOffsetAnim.animateTo(
                                  targetValue = targetValue,
                                  animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f))
                            }
                          },
                          onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val dragAmountDp = with(density) { dragAmount.toDp() }
                            coroutineScope.launch {
                              selectedOffsetAnim.snapTo(
                                  (selectedOffsetAnim.value + dragAmountDp).coerceIn(0.dp, 56.dp))
                            }
                          })
                    },
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            shadowElevation = 2.dp) {
              Box(modifier = Modifier.padding(4.dp).width(112.dp).height(40.dp)) {
                // 1. Base Layer: Icons with the default unselected color
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier = Modifier.weight(1f).fillMaxHeight(),
                          contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ViewQuilt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                          }

                      Box(
                          modifier = Modifier.weight(1f).fillMaxHeight(),
                          contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.GridView,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                          }
                    }

                // 2. Sliding Overlay Layer: Primary color background + White icons
                // Clipped to the dynamic bounds and shape of the sliding pill
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .graphicsLayer {
                              clip = true
                              shape =
                                  object : Shape {
                                    override fun createOutline(
                                        size: androidx.compose.ui.geometry.Size,
                                        layoutDirection: LayoutDirection,
                                        density: Density
                                    ): Outline {
                                      val widthPx = with(density) { 56.dp.toPx() }
                                      val heightPx = size.height
                                      val offsetPx =
                                          with(density) { selectedOffsetAnim.value.toPx() }
                                      val rect =
                                          Rect(
                                              left = offsetPx,
                                              top = 0f,
                                              right = offsetPx + widthPx,
                                              bottom = heightPx)
                                      val roundRect =
                                          RoundRect(
                                              rect = rect,
                                              cornerRadius =
                                                  CornerRadius(heightPx / 2f, heightPx / 2f))
                                      return Outline.Rounded(roundRect)
                                    }
                                  }
                            }
                            .background(MaterialTheme.colorScheme.primary)) {
                      // Inside the sliding pill, we draw the white icons row
                      // in the exact same position as the base layer (no offsets needed!)
                      Row(
                          modifier = Modifier.fillMaxSize(),
                          verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center) {
                                  Icon(
                                      imageVector = Icons.Rounded.ViewQuilt,
                                      contentDescription = "Gallery Layout",
                                      tint = Color.White,
                                      modifier = Modifier.size(20.dp))
                                }

                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center) {
                                  Icon(
                                      imageVector = Icons.Rounded.GridView,
                                      contentDescription = "Grid Layout",
                                      tint = Color.White,
                                      modifier = Modifier.size(20.dp))
                                }
                          }
                    }

                // 3. Top Layer: Transparent clickable regions to handle user input
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier =
                              Modifier.weight(1f)
                                  .fillMaxHeight()
                                  .clip(androidx.compose.foundation.shape.CircleShape)
                                  .clickable(onClick = hapticOnClick { isGalleryStyle = true }),
                          contentAlignment = Alignment.Center) {
                            // Transparent click target for Gallery layout
                          }

                      Box(
                          modifier =
                              Modifier.weight(1f)
                                  .fillMaxHeight()
                                  .clip(androidx.compose.foundation.shape.CircleShape)
                                  .clickable(onClick = hapticOnClick { isGalleryStyle = false }),
                          contentAlignment = Alignment.Center) {
                            // Transparent click target for Grid layout
                          }
                    }
              }
            }
      }
    }
  }

  if (isNavigating) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
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

private fun galleryPinchDistance(pointers: List<PointerInputChange>): Float {
  if (pointers.size < 2) return 0f
  val a = pointers[0].position
  val b = pointers[1].position
  return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
}

@Composable
private fun SearchPill(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isEmbeddingSearchMode: Boolean = false,
    onSearchModeToggle: (() -> Unit)? = null,
) {
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  var isFocused by remember { mutableStateOf(false) }
  var textFieldValue by remember {
    mutableStateOf(TextFieldValue(searchQuery, TextRange(searchQuery.length)))
  }

  LaunchedEffect(searchQuery) {
    if (searchQuery != textFieldValue.text) {
      textFieldValue = TextFieldValue(searchQuery, TextRange(searchQuery.length))
    }
  }

  val hasText = searchQuery.isNotBlank()
  val placeholder = stringResource(R.string.search_placeholder)
  val textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
  val modeIconScale by
      animateFloatAsState(
          targetValue = if (isEmbeddingSearchMode) 1.12f else 1f,
          animationSpec = tween(durationMillis = 220),
          label = "SearchModeIconScale")
  val modeIconAlpha by
      animateFloatAsState(
          targetValue = if (onSearchModeToggle != null) 1f else 0.72f,
          animationSpec = tween(durationMillis = 220),
          label = "SearchModeIconAlpha")

  Surface(
      shape = RoundedCornerShape(20.dp),
      color =
          if (isFocused) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.surfaceContainerHighest,
      onClick = hapticOnClick { focusRequester.requestFocus() },
  ) {
    Row(
        modifier = Modifier.height(32.dp).padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
          modifier =
              Modifier.size(22.dp)
                  .then(
                      if (onSearchModeToggle != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = hapticOnClick { onSearchModeToggle.invoke() },
                        )
                      } else {
                        Modifier
                      }),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            if (isEmbeddingSearchMode) Icons.Rounded.AutoAwesome else Icons.Rounded.Search,
            contentDescription =
                if (isEmbeddingSearchMode) "Embedding search" else "Keyword search",
            tint =
                if (isEmbeddingSearchMode) MaterialTheme.colorScheme.primary
                else if (isFocused) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.size(18.dp).graphicsLayer {
                  alpha = modeIconAlpha
                  scaleX = modeIconScale
                  scaleY = modeIconScale
                },
        )
      }
      Spacer(modifier = Modifier.width(4.dp))
      Box(contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
              textFieldValue = newValue
              onSearchQueryChange(newValue.text)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                      focusManager.clearFocus()
                      keyboardController?.hide()
                    }),
            modifier =
                Modifier.widthIn(min = 56.dp).focusRequester(focusRequester).onFocusChanged {
                  isFocused = it.isFocused
                },
            textStyle =
                textStyle.copy(
                    color =
                        if (isFocused) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (!hasText && !isFocused) {
          Text(
              placeholder,
              style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
              softWrap = false,
              maxLines = 1,
          )
        }
      }
      Spacer(modifier = Modifier.width(2.dp))
      Box(
          modifier = Modifier.size(16.dp),
          contentAlignment = Alignment.Center,
      ) {
        if (hasText) {
          IconButton(
              onClick =
                  hapticOnClick {
                    textFieldValue = TextFieldValue("")
                    onSearchQueryChange("")
                  },
              modifier = Modifier.size(16.dp),
          ) {
            Icon(
                Icons.Rounded.Close,
                "Clear",
                modifier = Modifier.size(14.dp),
                tint =
                    if (isFocused) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AnalysisFocusFrame(
    pulseKey: Int?,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
  val pulseProgress = remember { Animatable(1f) }

  LaunchedEffect(pulseKey) {
    if (pulseKey == null) {
      pulseProgress.snapTo(1f)
    } else {
      pulseProgress.snapTo(0f)
      pulseProgress.animateTo(
          targetValue = 1f,
          animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
      )
    }
  }

  val progress = pulseProgress.value
  val glowAlpha =
      if (progress < 0.16f) {
        progress / 0.16f
      } else {
        (1f - ((progress - 0.16f) / 0.84f)).coerceIn(0f, 1f)
      }
  val glowColor = Color(0xFF35D07F)
  val expansion = 5f + (progress * 25f)

  Box(
      modifier =
          modifier
              .zIndex(if (glowAlpha > 0f) 1f else 0f)
              .dispersedGlow(
                  color = glowColor,
                  alpha = 0.34f * glowAlpha,
                  glowRadius = expansion.dp,
                  borderRadius = cornerRadius + (progress * 8f).dp,
                  horizontalInset = (-progress * 10f).dp,
                  verticalInset = (-progress * 10f).dp,
              )
              .dispersedGlow(
                  color = glowColor,
                  alpha = 0.62f * glowAlpha,
                  glowRadius = (4f + progress * 10f).dp,
                  borderRadius = cornerRadius,
                  horizontalInset = (-progress * 4f).dp,
                  verticalInset = (-progress * 4f).dp,
              )
              .border(
                  width = 2.dp,
                  color = glowColor.copy(alpha = 0.82f * glowAlpha),
                  shape = RoundedCornerShape(cornerRadius),
              ),
  ) {
    content()
  }
}

@Composable
private fun ThumbnailCell(
    image: MediaStoreImage,
    shouldLoad: Boolean,
    showPlaceholder: Boolean = false,
    focusPulseKey: Int? = null,
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

  val isLoaded = bitmap != null
  val imageAlpha by
      animateFloatAsState(
          targetValue = if (isLoaded) 1f else 0f,
          animationSpec = tween(durationMillis = 350),
          label = "thumbnailFade",
      )

  AnalysisFocusFrame(
      pulseKey = focusPulseKey,
      cornerRadius = 6.dp,
      modifier = modifier,
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
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
            modifier =
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
      } else if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
            contentScale = ContentScale.Crop,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryAlbumThumbnailCard(
    albumName: String,
    count: Int,
    thumbnailUris: List<Uri>,
    isSelected: Boolean,
    isPinned: Boolean = false,
    isAddedForAnalysis: Boolean = false,
    isDragging: Boolean = false,
    size: Dp = 80.dp,
    onDoubleClick: (() -> Unit)? = null,
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
      modifier = Modifier.width(size),
  ) {
    val glowOuterShape = RoundedCornerShape(14.dp)
    val glowInnerShape = RoundedCornerShape(13.dp)

    Box(modifier = Modifier.size(size)) {
      Box(
          modifier =
              Modifier.matchParentSize()
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
                  .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick),
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
      }

      if (isPinned) {
        Surface(
            modifier =
                Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp).size(18.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary,
            shadowElevation = 2.dp,
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(11.dp),
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.size(4.dp))

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

fun Modifier.dispersedGlow(
    color: Color,
    alpha: Float,
    glowRadius: Dp,
    borderRadius: Dp = 22.dp,
    horizontalInset: Dp = 0.dp,
    verticalInset: Dp = 0.dp
): Modifier =
    this.drawBehind {
      if (alpha <= 0f || glowRadius <= 0.dp) return@drawBehind

      drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = color.copy(alpha = alpha).toArgb()
        frameworkPaint.maskFilter = BlurMaskFilter(glowRadius.toPx(), BlurMaskFilter.Blur.NORMAL)

        val hInsetPx = horizontalInset.toPx()
        val vInsetPx = verticalInset.toPx()

        canvas.nativeCanvas.drawRoundRect(
            hInsetPx,
            vInsetPx,
            size.width - hInsetPx,
            size.height - vInsetPx,
            borderRadius.toPx(),
            borderRadius.toPx(),
            frameworkPaint)
      }
    }
