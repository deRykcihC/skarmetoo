package com.deryk.skarmetoo.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import com.deryk.skarmetoo.util.ShareUtils
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import com.deryk.skarmetoo.viewmodel.SemanticSearchViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    viewModel: ScreenshotViewModel,
    semanticViewModel: SemanticSearchViewModel,
    entryId: Long,
    onBack: () -> Unit,
    onTagClick: (String) -> Unit = {},
    onScreenshotClick: (Long) -> Unit = {},
) {
  val entries by viewModel.entries.collectAsState()
  val entry = entries.find { it.id == entryId }
  val isModelReady by viewModel.isModelReady.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val entryProgressMap by viewModel.entryProgressMap.collectAsState()
  val context = LocalContext.current
  val isDark = LocalIsDarkMode.current
  val scrollState = rememberScrollState()

  if (entry == null) {
    LaunchedEffect(Unit) { onBack() }
    return
  }

  val coroutineScope = rememberCoroutineScope()

  var noteText by remember(entry.note) { mutableStateOf(entry.note) }
  var showFullscreenImage by remember { mutableStateOf(false) }

  val isActivelyAnalyzing = entry.isAnalyzing || entryProgressMap.containsKey(entry.id)
  val isAnalyzed = entry.summary.isNotBlank() && !isActivelyAnalyzing
  val isPending = entry.summary.isBlank() && !isActivelyAnalyzing
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()

  // Double-tap detection for status button
  var lastTapTime by remember { mutableStateOf(0L) }

  // Save note when leaving page
  DisposableEffect(Unit) { onDispose { viewModel.updateNote(entryId, noteText) } }

  BackHandler {
    viewModel.updateNote(entryId, noteText)
    onBack()
  }

  val isSemanticModelReady by semanticViewModel.isModelReady.collectAsState()
  val similarScreenshots by semanticViewModel.similarScreenshots.collectAsState()
  val targetScreenshot by semanticViewModel.targetScreenshot.collectAsState()
  val infoMessage by semanticViewModel.infoMessage.collectAsState()
  val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
  var showSimilarSection by remember(entryId) { mutableStateOf(false) }
  var isLoaderActive by remember(entryId) { mutableStateOf(false) }
  var isResettingLoader by remember(entryId) { mutableStateOf(false) }
  val dragProgress = remember { Animatable(0f) }
  val density = androidx.compose.ui.platform.LocalDensity.current
  val maxDragPx = remember(density) { with(density) { 180.dp.toPx() } }

  LaunchedEffect(entryId) {
    showSimilarSection = false
    isLoaderActive = false
    isResettingLoader = false
    semanticViewModel.clearTargetScreenshot()
    dragProgress.snapTo(0f)
  }

  LaunchedEffect(showSimilarSection, entryId) {
    if (showSimilarSection) {
      semanticViewModel.selectTargetScreenshot(entry, entries)
    }
  }

  val nestedScrollConnection =
      remember(entryId, isSemanticModelReady, showSimilarSection) {
        object : NestedScrollConnection {
          override fun onPostScroll(
              consumed: Offset,
              available: Offset,
              source: NestedScrollSource,
          ): Offset {
            if (!isSemanticModelReady || showSimilarSection) return Offset.Zero
            val isAtBottom = scrollState.value >= scrollState.maxValue
            val overscrollY = available.y

            if (isAtBottom && overscrollY < 0f) {
              isLoaderActive = true
              isResettingLoader = false
              val previousProgress = dragProgress.value
              val newProgress = (dragProgress.value + (-overscrollY / maxDragPx)).coerceIn(0f, 1f)
              if (previousProgress < 1f && newProgress >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              }
              coroutineScope.launch { dragProgress.snapTo(newProgress) }
              return Offset(0f, overscrollY)
            }

            if (overscrollY > 0f && dragProgress.value > 0f) {
              val newProgress = (dragProgress.value - (overscrollY / maxDragPx)).coerceAtLeast(0f)
              if (newProgress <= 0f) {
                isLoaderActive = false
              }
              coroutineScope.launch { dragProgress.snapTo(newProgress) }
              return Offset(0f, overscrollY)
            }

            return Offset.Zero
          }

          override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (dragProgress.value >= 0.99f) {
              showSimilarSection = true
              isResettingLoader = false
              isLoaderActive = false
              dragProgress.snapTo(0f)
              return Velocity.Zero
            }

            if (showSimilarSection) return Velocity.Zero
            if (dragProgress.value > 0f) {
              isLoaderActive = true
              isResettingLoader = true
              val resetDurationMs =
                  (260 * dragProgress.value.coerceIn(0f, 1f)).toInt().coerceAtLeast(120)
              dragProgress.animateTo(
                  targetValue = 0f,
                  animationSpec = tween(durationMillis = resetDurationMs, easing = LinearEasing),
              )
              isResettingLoader = false
              isLoaderActive = false
            }
            return Velocity.Zero
          }
        }
      }

  // Fullscreen image dialog with pinch-to-zoom and double-tap zoom
  if (showFullscreenImage && entry.imageUri.isNotBlank()) {
    Dialog(
        onDismissRequest = { showFullscreenImage = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
      Box(
          modifier = Modifier.fillMaxSize().background(Color.Black),
          contentAlignment = Alignment.Center,
      ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var lastDoubleTapTime by remember { mutableLongStateOf(0L) }

        AsyncImage(
            model = entry.imageUri,
            contentDescription = null,
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale.coerceIn(1f, 5f),
                        scaleY = scale.coerceIn(1f, 5f),
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .pointerInput(Unit) {
                      detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                          offsetX += pan.x
                          offsetY += pan.y
                        } else {
                          offsetX = 0f
                          offsetY = 0f
                        }
                      }
                    }
                    .pointerInput(Unit) {
                      awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        // Wait for up
                        do {
                          val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })

                        val upTime = System.currentTimeMillis()
                        if (upTime - downTime < 300) {
                          // It's a tap
                          if (downTime - lastDoubleTapTime < 350) {
                            // Double tap
                            if (scale > 1.5f) {
                              scale = 1f
                              offsetX = 0f
                              offsetY = 0f
                            } else {
                              scale = 3f
                            }
                            lastDoubleTapTime = 0L
                          } else {
                            lastDoubleTapTime = downTime
                          }
                        }
                      }
                    },
            contentScale = ContentScale.Fit,
        )

        // Close button
        IconButton(
            onClick = hapticOnClick { showFullscreenImage = false },
            modifier =
                Modifier.align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
          Icon(Icons.Rounded.Close, "Close", tint = Color.White)
        }
      }
    }
  }

  Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
          onClick =
              hapticOnClick {
                viewModel.updateNote(entryId, noteText)
                onBack()
              }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
          }
      Text(
          stringResource(R.string.details_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.weight(1f))

      // Custom Rendered Share Card Button
      IconButton(
          onClick = hapticOnClick { ShareUtils.shareScreenshotContent(context, entry, noteText) },
          modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Style, "Generate Share Card")
          }
      Spacer(modifier = Modifier.width(4.dp))

      // Direct Original Screenshot Share Button
      IconButton(
          onClick =
              hapticOnClick {
                try {
                  val intent =
                      Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.imageUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                      }
                  context.startActivity(Intent.createChooser(intent, "Share Original Screenshot"))
                } catch (e: Exception) {
                  Toast.makeText(context, "Failed to share original screenshot", Toast.LENGTH_SHORT)
                      .show()
                }
              },
          modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Share, "Share Original")
          }
      Spacer(modifier = Modifier.width(8.dp))

      // Status pill — exact copy of home page style
      if (isActivelyAnalyzing) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)),
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
            } else {
              CircularProgressIndicator(
                  progress = { entryProgressMap[entry.id] ?: currentImageProgress },
                  modifier = Modifier.size(14.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.error,
                  trackColor = MaterialTheme.colorScheme.errorContainer,
              )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(R.string.analyzing),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      } else if (isPending) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier =
                Modifier.clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onDoubleClick = { if (isModelReady) viewModel.analyzeEntry(entry) },
                        onClick = hapticOnClick {},
                    ),
        ) {
          Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
                Icons.Rounded.Schedule,
                null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(R.string.pending),
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
                        onDoubleClick = { if (isModelReady) viewModel.analyzeEntry(entry) },
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

      Spacer(modifier = Modifier.width(8.dp))
    }

    Box(modifier = Modifier.weight(1f).nestedScroll(nestedScrollConnection)) {
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(scrollState)
                  .padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().clickable { showFullscreenImage = true },
        ) {
          if (entry.imageUri.isNotBlank()) {
            var isMainImageLoaded by remember(entry.id) { mutableStateOf(false) }
            val mainImageAlpha by
                animateFloatAsState(
                    targetValue = if (isMainImageLoaded) 1f else 0f,
                    animationSpec = tween(durationMillis = 350),
                    label = "mainImageFade",
                )

            AsyncImage(
                model = entry.imageUri,
                contentDescription = entry.summary,
                onSuccess = { isMainImageLoaded = true },
                modifier =
                    Modifier.fillMaxWidth().heightIn(max = 350.dp).graphicsLayer {
                      alpha = mainImageAlpha
                    },
                contentScale = ContentScale.Fit,
            )
          } else {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
              Icon(
                  Icons.Rounded.BrokenImage,
                  null,
                  tint = MaterialTheme.colorScheme.outline,
                  modifier = Modifier.size(48.dp),
              )
            }
          }
          if (isActivelyAnalyzing) {
            LinearProgressIndicator(
                progress = { entryProgressMap[entry.id] ?: currentImageProgress },
                modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Summary
        if (entry.summary.isNotBlank()) {
          Text(
              text = entry.summary,
              style =
                  MaterialTheme.typography.bodyLarge.copy(
                      hyphens = Hyphens.Auto,
                      lineBreak = LineBreak.Paragraph,
                      platformStyle =
                          PlatformTextStyle(
                              includeFontPadding = false,
                          ),
                  ),
              color = MaterialTheme.colorScheme.onBackground,
              lineHeight = 26.sp,
              textAlign = TextAlign.Justify,
          )
        } else if (isActivelyAnalyzing) {
          Text(
              stringResource(R.string.analyzing_screenshot),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.primary,
          )
        } else {
          Text(
              stringResource(R.string.no_summary_double_tap),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.outline,
          )
        }

        // TAGS section — clickable to filter gallery
        if (entry.getTagList().isNotEmpty()) {
          Spacer(modifier = Modifier.height(24.dp))

          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Rounded.Label,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.tags_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
          }

          Spacer(modifier = Modifier.height(4.dp))

          FlowRow(
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalArrangement = Arrangement.spacedBy(0.dp),
          ) {
            entry.getTagList().forEach { tag ->
              OutlinedCard(
                  shape = RoundedCornerShape(20.dp),
                  border = CardDefaults.outlinedCardBorder(),
                  onClick = hapticOnClick { onTagClick(tag) },
              ) {
                Text(
                    text = tag,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }

        // NOTE section
        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
              Icons.Rounded.Edit,
              null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
              stringResource(R.string.note_title),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              letterSpacing = 1.sp,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = noteText,
            onValueChange = { noteText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
              Text(stringResource(R.string.add_a_note), color = MaterialTheme.colorScheme.outline)
            },
            shape = RoundedCornerShape(12.dp),
            minLines = 2,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
        )

        // METADATA section
        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
              Icons.Rounded.Info,
              null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
              stringResource(R.string.metadata_title),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              letterSpacing = 1.sp,
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            if (entry.modelUsed.isNotBlank()) {
              MetadataRow(
                  label = stringResource(R.string.meta_model_used),
                  value = entry.modelUsed,
              )
              Spacer(modifier = Modifier.height(8.dp))
            }

            MetadataRow(
                label = stringResource(R.string.meta_hash),
                value = entry.imageHash.take(16) + if (entry.imageHash.length > 16) "..." else "",
            )

            if (entry.analyzedAt > 0) {
              Spacer(modifier = Modifier.height(8.dp))
              MetadataRow(
                  label = stringResource(R.string.meta_analyzed),
                  value =
                      run {
                        val pattern =
                            if (Locale.getDefault().language == "zh") "M月d日, yyyy HH:mm"
                            else "MMM dd, yyyy HH:mm"
                        SimpleDateFormat(pattern, Locale.getDefault())
                            .format(Date(entry.analyzedAt))
                      },
              )
            }

            if (entry.imageUri.isNotBlank()) {
              Spacer(modifier = Modifier.height(8.dp))
              val sourceName =
                  try {
                    android.net.Uri.decode(
                        Uri.parse(entry.imageUri).lastPathSegment ?: "Unknown",
                    )
                  } catch (e: Exception) {
                    "Unknown"
                  }
              MetadataRow(label = stringResource(R.string.meta_file), value = sourceName)

              Spacer(modifier = Modifier.height(8.dp))
              val fileSize =
                  try {
                    context.contentResolver
                        .openAssetFileDescriptor(Uri.parse(entry.imageUri), "r")
                        ?.use {
                          val bytes = it.length
                          when {
                            bytes < 1024 -> "$bytes B"
                            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
                          }
                        } ?: "Unknown"
                  } catch (e: Exception) {
                    "Unknown"
                  }
              MetadataRow(label = stringResource(R.string.meta_size), value = fileSize)
            }

            Spacer(modifier = Modifier.height(8.dp))
            MetadataRow(label = stringResource(R.string.meta_id), value = "#${entry.id}")
          }
        }

        val progress = dragProgress.value
        if (showSimilarSection) {
          Spacer(modifier = Modifier.height(24.dp))
          AnimatedVisibility(
              visible = true,
              enter =
                  slideInVertically(
                      initialOffsetY = { -it / 2 },
                      animationSpec = tween(durationMillis = 260),
                  ) + fadeIn(animationSpec = tween(durationMillis = 220)),
          ) {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(18.dp)) {
                  Icon(
                      Icons.Rounded.Search,
                      null,
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(18.dp),
                  )
                  Icon(
                      Icons.Rounded.AutoAwesome,
                      null,
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier =
                          Modifier.size(9.dp).align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp),
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.look_similar_title_caps),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                  Text(
                      text = stringResource(R.string.beta),
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Bold,
                  )
                }
              }

              Spacer(modifier = Modifier.height(4.dp))
              Text(
                  stringResource(R.string.look_similar_disclaimer),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )

              Spacer(modifier = Modifier.height(8.dp))

              SimilarScreenshotsSection(
                  currentEntry = entry,
                  targetScreenshot = targetScreenshot,
                  similarScreenshots = similarScreenshots,
                  infoMessage = infoMessage,
                  onScreenshotClick = { matchedEntry ->
                    viewModel.updateNote(entryId, noteText)
                    onScreenshotClick(matchedEntry.id)
                  },
              )
            }
          }
        } else {
          val shouldShowLoader = isLoaderActive || progress > 0f || isResettingLoader
          AnimatedVisibility(
              visible = shouldShowLoader,
              enter = fadeIn(animationSpec = tween(durationMillis = 90)),
              exit =
                  shrinkVertically(animationSpec = tween(durationMillis = 180)) +
                      fadeOut(animationSpec = tween(durationMillis = 180)),
          ) {
            SimilarScreenshotsLoader(progress = progress)
          }
        }
      } // end scroll Column
    } // end Box
  } // end outer Column
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
  }
}

@Composable
private fun SimilarScreenshotsSection(
    currentEntry: ScreenshotEntry,
    targetScreenshot: ScreenshotEntry?,
    similarScreenshots: List<Pair<ScreenshotEntry, Float>>,
    infoMessage: String?,
    onScreenshotClick: (ScreenshotEntry) -> Unit,
) {
  val isSearchingSimilar =
      infoMessage?.contains(stringResource(R.string.look_similar_searching)) == true
  if (targetScreenshot?.id == currentEntry.id) {
    if (similarScreenshots.isEmpty()) {
      val statusMessage = infoMessage ?: stringResource(R.string.look_similar_no_results)
      Box(
          modifier = Modifier.fillMaxWidth().height(96.dp),
          contentAlignment = Alignment.Center,
      ) {
        if (isSearchingSimilar ||
            statusMessage.contains(stringResource(R.string.look_similar_searching))) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp))
        } else {
          Text(
              text = statusMessage,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
          )
        }
      }
    } else {
      val rows = similarScreenshots.chunked(4)
      Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              row.forEachIndexed { colIndex, (matchedEntry, score) ->
                val tileIndex = rowIndex * 4 + colIndex
                var isImageLoaded by remember(matchedEntry.id) { mutableStateOf(false) }
                val imageAlpha by
                    animateFloatAsState(
                        targetValue = if (isImageLoaded) 1f else 0f,
                        animationSpec =
                            tween(
                                durationMillis = 280,
                                delayMillis = (tileIndex * 24).coerceAtMost(192),
                            ),
                        label = "similarImageFade",
                    )

                Box(
                    modifier =
                        Modifier.weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(onClick = hapticOnClick { onScreenshotClick(matchedEntry) }),
                    contentAlignment = Alignment.Center,
                ) {
                  if (matchedEntry.imageUri.isNotBlank()) {
                    AsyncImage(
                        model = matchedEntry.imageUri,
                        contentDescription = null,
                        onSuccess = { isImageLoaded = true },
                        onError = { isImageLoaded = true },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                        contentScale = ContentScale.Crop,
                    )
                  } else {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                  }

                  Surface(
                      modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                      color = Color.Black.copy(alpha = 0.65f),
                      shape = RoundedCornerShape(6.dp),
                  ) {
                    Text(
                        text = "${(score * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }

              if (row.size < 4) {
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f).aspectRatio(1f)) }
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  } else {
    Box(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(22.dp))
    }
  }
}

@Composable
private fun SimilarScreenshotsLoader(progress: Float) {
  val clampedProgress = progress.coerceIn(0f, 1f)
  val animatedScale by
      animateFloatAsState(
          targetValue = 0.86f + (clampedProgress * 0.14f),
          animationSpec =
              spring(
                  dampingRatio = Spring.DampingRatioMediumBouncy,
                  stiffness = Spring.StiffnessLow,
              ),
          label = "overscrollLoaderScale",
      )

  Box(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      contentAlignment = Alignment.Center,
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
      CircularProgressIndicator(
          progress = { clampedProgress },
          modifier =
              Modifier.fillMaxSize().graphicsLayer(scaleX = animatedScale, scaleY = animatedScale),
          strokeWidth = 4.dp,
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
      Text(
          text = "${(clampedProgress * 100).toInt()}%",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
