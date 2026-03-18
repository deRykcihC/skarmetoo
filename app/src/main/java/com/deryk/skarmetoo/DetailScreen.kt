package com.deryk.skarmetoo

import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================
// DETAIL SCREEN
// =============================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    viewModel: ScreenshotViewModel,
    entryId: Long,
    onBack: () -> Unit,
    onTagClick: (String) -> Unit = {},
) {
    val entries by viewModel.entries.collectAsState()
    val entry = entries.find { it.id == entryId }
    val isModelReady by viewModel.isModelReady.collectAsState()
    val currentImageProgress by viewModel.currentImageProgress.collectAsState()
    val context = LocalContext.current

    if (entry == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var noteText by remember(entry.note) { mutableStateOf(entry.note) }
    var showFullscreenImage by remember { mutableStateOf(false) }

    val isAnalyzed = entry.summary.isNotBlank() && !entry.isAnalyzing
    val isPending = entry.summary.isBlank() && !entry.isAnalyzing

    // Double-tap detection for status button
    var lastTapTime by remember { mutableStateOf(0L) }

    // Save note when leaving page
    DisposableEffect(Unit) {
        onDispose {
            viewModel.updateNote(entryId, noteText)
        }
    }

    // Fullscreen image dialog with pinch-to-zoom and double-tap zoom
    if (showFullscreenImage && entry.imageUri.isNotBlank()) {
        Dialog(
            onDismissRequest = { showFullscreenImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
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
                        Modifier
                            .fillMaxSize()
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
                    onClick = { showFullscreenImage = false },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White)
                }
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        // Top Bar — exactly matching home page style
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
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

            IconButton(onClick = { ShareUtils.shareScreenshotContent(context, entry, noteText) }) {
                Icon(Icons.Rounded.Share, "Share")
            }
            Spacer(modifier = Modifier.width(4.dp))

            // Status pill — exact copy of home page style
            if (entry.isAnalyzing) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(16.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            progress = currentImageProgress,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.errorContainer,
                        )
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
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onDoubleClick = {
                                    if (isModelReady) viewModel.analyzeEntry(entry)
                                },
                                onClick = { },
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
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onDoubleClick = {
                                    if (isModelReady) viewModel.analyzeEntry(entry)
                                },
                                onClick = { },
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

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Screenshot image in rounded card (clickable for fullscreen)
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showFullscreenImage = true },
            ) {
                if (entry.imageUri.isNotBlank()) {
                    AsyncImage(
                        model = entry.imageUri,
                        contentDescription = entry.summary,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 350.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
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
                if (entry.isAnalyzing) {
                    LinearProgressIndicator(
                        progress = currentImageProgress,
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
            } else if (entry.isAnalyzing) {
                Text(
                    "Analyzing screenshot...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "No summary available. Double-tap status to analyze.",
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
                            onClick = { onTagClick(tag) },
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
                                    val pattern = if (Locale.getDefault().language == "zh") "M月d日, yyyy HH:mm" else "MMM dd, yyyy HH:mm"
                                    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(entry.analyzedAt))
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
                                context.contentResolver.openAssetFileDescriptor(Uri.parse(entry.imageUri), "r")?.use {
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
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
