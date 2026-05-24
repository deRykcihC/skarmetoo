package com.deryk.skarmetoo.legacy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ScreenshotViewModel
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.findComponentActivity
import com.deryk.skarmetoo.hapticOnClick

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LegacyScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    scrollToTopKey: Int = 0,
    refreshKey: Int = 0,
    logoRes: Int = R.drawable.app_logo,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    isPickMode: Boolean = false,
) {
  val entries by viewModel.entries.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val searchQuery by viewModel.searchQuery.collectAsState()
  val analysisProgress by viewModel.analysisProgress.collectAsState()
  val isRefreshing by viewModel.isRefreshing.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val entryProgressMap by viewModel.entryProgressMap.collectAsState()
  val activeAnalysisIds by viewModel.activeAnalysisIds.collectAsState()
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
  val isSortDescending by viewModel.isSortDescending.collectAsState()

  LaunchedEffect(scrollToTopKey) {
    if (scrollToTopKey > 0) {
      scrollState.animateScrollTo(0)
    }
  }

  LaunchedEffect(refreshKey) {
    if (refreshKey > 0) {
      scrollState.animateScrollTo(0)
      viewModel.refreshImages()
    }
  }

  val allTags =
      remember(entries) {
        entries
            .flatMap { it.getTagList() }
            .groupBy { it.lowercase() }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(20)
      }

  LaunchedEffect(allTags.toSet()) {
    if (selectedTag != null && allTags.none { it.equals(selectedTag, ignoreCase = true) }) {
      selectedTag = null
    }
  }

  val filteredEntries =
      remember(entries, selectedTag, isSortDescending) {
        val filtered =
            if (selectedTag == null) {
              entries
            } else {
              entries.filter { entry ->
                entry.getTagList().any { it.equals(selectedTag, ignoreCase = true) }
              }
            }

        if (isSortDescending) {
          filtered.sortedByDescending { it.sortKey }
        } else {
          filtered.sortedBy { it.sortKey }
        }
      }

  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()

  val pageSize by viewModel.galleryPageSize.collectAsState()
  var visibleItemCount by
      rememberSaveable(selectedTag, isSortDescending, searchQuery) { mutableIntStateOf(pageSize) }

  LaunchedEffect(pageSize) {
    if (visibleItemCount < pageSize) {
      visibleItemCount = pageSize
    }
  }

  LaunchedEffect(scrollState.value, scrollState.maxValue, filteredEntries.size, visibleItemCount) {
    if (visibleItemCount < filteredEntries.size && scrollState.maxValue > 0) {
      val progress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
      val estimatedTopItem = (progress * visibleItemCount).toInt()
      if (estimatedTopItem >= visibleItemCount - pageSize / 2) {
        kotlinx.coroutines.delay(50)
        visibleItemCount = (visibleItemCount + pageSize).coerceAtMost(filteredEntries.size)
      }
    }
  }

  val context = androidx.compose.ui.platform.LocalContext.current
  val imageLoader = context.imageLoader
  LaunchedEffect(visibleItemCount, filteredEntries) {
    val preloadEnd = (visibleItemCount + pageSize).coerceAtMost(filteredEntries.size)
    val preloadStart = visibleItemCount
    if (preloadStart < preloadEnd) {
      val requestFactory = { coil.request.ImageRequest.Builder(context).size(512).crossfade(true) }
      for (i in preloadStart until preloadEnd) {
        val entry = filteredEntries[i]
        if (entry.imageUri.isNotBlank()) {
          imageLoader.enqueue(requestFactory().data(entry.imageUri).build())
        }
      }
    }
  }

  PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = { viewModel.refreshImages() },
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier =
            Modifier.fillMaxSize().pointerInput(Unit) {
              detectTapGestures(
                  onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                  })
            },
    ) {
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
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
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Spacer(modifier = Modifier.weight(1f))

          if (entries.isNotEmpty() && (!searchQuery.isBlank() || selectedTag != null)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                  filteredEntries.size.toString(),
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
          }

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
                            onDoubleClick = {
                              if (isModelReady) viewModel.forceAnalyzeUnprocessed()
                            },
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
          SearchPill(
              searchQuery = searchQuery,
              onSearchQueryChange = { viewModel.setSearchQuery(it) },
          )
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
        items(allTags) { tag ->
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

      if (entries.isEmpty() && searchQuery.isBlank()) {
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.select_albums_in_settings_to_get_started),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
          }
        }
      } else if (filteredEntries.isEmpty()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.SearchOff,
                null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
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
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
        ) {
          Spacer(modifier = Modifier.height(4.dp))
          val displayedEntries = filteredEntries.take(visibleItemCount)
          Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            val leftColumnEntries = displayedEntries.filterIndexed { index, _ -> index % 2 == 0 }
            val rightColumnEntries = displayedEntries.filterIndexed { index, _ -> index % 2 == 1 }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              leftColumnEntries.forEach { entry ->
                val isActivelyAnalyzing =
                    activeAnalysisIds.contains(entry.id) ||
                        entry.isAnalyzing ||
                        entryProgressMap.containsKey(entry.id)
                ScreenshotGridItem(
                    entry = entry,
                    currentImageProgress =
                        entryProgressMap[entry.id]
                            ?: if (isActivelyAnalyzing) currentImageProgress else 0f,
                    isActivelyAnalyzing = isActivelyAnalyzing,
                    isQueueRunning = isAnalysisRunning,
                    onClick =
                        hapticOnClick {
                          if (isPickMode) {
                            val activity = context.findComponentActivity()
                            if (activity != null) {
                              val resultIntent =
                                  android.content.Intent().apply {
                                    data = android.net.Uri.parse(entry.imageUri)
                                    flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                  }
                              activity.setResult(android.app.Activity.RESULT_OK, resultIntent)
                              activity.finish()
                            }
                          } else {
                            onScreenshotClick(entry.id)
                          }
                        },
                )
              }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              rightColumnEntries.forEach { entry ->
                val isActivelyAnalyzing =
                    activeAnalysisIds.contains(entry.id) ||
                        entry.isAnalyzing ||
                        entryProgressMap.containsKey(entry.id)
                ScreenshotGridItem(
                    entry = entry,
                    currentImageProgress =
                        entryProgressMap[entry.id]
                            ?: if (isActivelyAnalyzing) currentImageProgress else 0f,
                    isActivelyAnalyzing = isActivelyAnalyzing,
                    isQueueRunning = isAnalysisRunning,
                    onClick =
                        hapticOnClick {
                          if (isPickMode) {
                            val activity = context.findComponentActivity()
                            if (activity != null) {
                              val resultIntent =
                                  android.content.Intent().apply {
                                    data = android.net.Uri.parse(entry.imageUri)
                                    flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                  }
                              activity.setResult(android.app.Activity.RESULT_OK, resultIntent)
                              activity.finish()
                            }
                          } else {
                            onScreenshotClick(entry.id)
                          }
                        },
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }
  }
}

@Composable
fun SearchPill(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
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
  val textStyle =
      MaterialTheme.typography.labelLarge.copy(
          fontWeight = FontWeight.SemiBold,
      )

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
      Icon(
          Icons.Rounded.Search,
          null,
          tint =
              if (isFocused) MaterialTheme.colorScheme.onSecondaryContainer
              else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
      )
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
                    },
                ),
            modifier =
                Modifier.widthIn(min = 56.dp).focusRequester(focusRequester).onFocusChanged {
                  isFocused = it.isFocused
                },
            textStyle =
                textStyle.copy(
                    color =
                        if (isFocused) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (!hasText && !isFocused) {
          Text(
              placeholder,
              style =
                  textStyle.copy(
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  ),
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
fun ScreenshotGridItem(
    entry: ScreenshotEntry,
    currentImageProgress: Float = 0f,
    isActivelyAnalyzing: Boolean = false,
    isQueueRunning: Boolean = false,
    onClick: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val isRestricted =
      remember(entry.tags) { entry.getTagList().any { it.equals("restricted", ignoreCase = true) } }

  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      colors =
          CardDefaults.cardColors(
              containerColor =
                  if (isRestricted) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                  },
          ),
  ) {
    Column {
      if (entry.imageUri.isNotBlank()) {
        val imageRequest =
            coil.request.ImageRequest.Builder(context)
                .data(entry.imageUri)
                .size(512)
                .crossfade(true)
                .build()

        AsyncImage(
            model = imageRequest,
            contentDescription = entry.summary,
            modifier =
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            contentScale = ContentScale.FillWidth,
        )
      } else {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              Icons.Rounded.BrokenImage,
              null,
              tint = MaterialTheme.colorScheme.outline,
              modifier = Modifier.size(32.dp),
          )
        }
      }

      if (isActivelyAnalyzing) {
        LinearProgressIndicator(
            progress = { currentImageProgress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }

      Column(modifier = Modifier.padding(10.dp)) {
        if (entry.summary.isNotBlank()) {
          Text(
              text = entry.summary,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              color =
                  if (isRestricted) MaterialTheme.colorScheme.onErrorContainer
                  else MaterialTheme.colorScheme.onSurface,
              lineHeight = 18.sp,
          )
        } else if (isActivelyAnalyzing) {
          Text(
              stringResource(R.string.analyzing) + "...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
          )
        } else {
          Text(
              stringResource(R.string.not_analyzed),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
          )
        }

        if (entry.tags.isNotBlank()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = entry.getTagList().joinToString(", "),
              style = MaterialTheme.typography.labelSmall,
              color =
                  if (isRestricted) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
