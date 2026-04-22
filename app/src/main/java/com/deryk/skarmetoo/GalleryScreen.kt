package com.deryk.skarmetoo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.deryk.skarmetoo.data.ScreenshotEntry

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    scrollToTopKey: Int = 0,
    refreshKey: Int = 0,
    logoRes: Int = R.drawable.app_logo,
) {
  val entries by viewModel.entries.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val searchQuery by viewModel.searchQuery.collectAsState()
  val analysisProgress by viewModel.analysisProgress.collectAsState()
  val isRefreshing by viewModel.isRefreshing.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val entryProgressMap by viewModel.entryProgressMap.collectAsState()
  var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
  val isSortDescending by viewModel.isSortDescending.collectAsState()
  val scrollState = rememberScrollState()

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

  val pendingCount = remember(entries) { entries.count { it.summary.isBlank() && !it.isAnalyzing } }
  val analyzingCount = remember(entries) { entries.count { it.isAnalyzing } }

  var visibleItemCount by
      rememberSaveable(selectedTag, isSortDescending, searchQuery) { mutableIntStateOf(5) }

  LaunchedEffect(scrollState.value, scrollState.maxValue, filteredEntries.size, visibleItemCount) {
    if (visibleItemCount < filteredEntries.size) {
      if (scrollState.maxValue <= 0 || scrollState.value >= scrollState.maxValue - 800) {
        kotlinx.coroutines.delay(50)
        visibleItemCount += 5
      }
    }
  }

  PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = { viewModel.refreshImages() },
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
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

      OutlinedTextField(
          value = searchQuery,
          onValueChange = { viewModel.setSearchQuery(it) },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          placeholder = { Text(stringResource(R.string.search_placeholder)) },
          singleLine = true,
          shape = RoundedCornerShape(28.dp),
          leadingIcon = {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.outline)
          },
          trailingIcon = {
            if (searchQuery.isNotBlank()) {
              IconButton(onClick = { viewModel.setSearchQuery("") }) {
                Icon(Icons.Rounded.Close, "Clear")
              }
            }
          },
          colors =
              OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = MaterialTheme.colorScheme.outline,
                  unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                  focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                  unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
              ),
      )

      if (entries.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            FilterChip(
                selected = true,
                onClick = { viewModel.toggleSortOrder() },
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
                onClick = { selectedTag = if (selectedTag == tag) null else tag },
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
      } else {
        Spacer(modifier = Modifier.height(8.dp))
      }

      if (entries.isEmpty()) {
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
      } else {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState),
        ) {
          Spacer(modifier = Modifier.height(4.dp))
          // Masonry/staggered grid layout
          val displayedEntries = filteredEntries.take(visibleItemCount)
          Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // Split entries into two columns for masonry layout
            val leftColumnEntries = displayedEntries.filterIndexed { index, _ -> index % 2 == 0 }
            val rightColumnEntries = displayedEntries.filterIndexed { index, _ -> index % 2 == 1 }

            // Left column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              leftColumnEntries.forEach { entry ->
                ScreenshotGridItem(
                    entry = entry,
                    currentImageProgress =
                        entryProgressMap[entry.id]
                            ?: if (entry.isAnalyzing) currentImageProgress else 0f,
                    onClick = { onScreenshotClick(entry.id) },
                )
              }
            }

            // Right column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              rightColumnEntries.forEach { entry ->
                ScreenshotGridItem(
                    entry = entry,
                    currentImageProgress =
                        entryProgressMap[entry.id]
                            ?: if (entry.isAnalyzing) currentImageProgress else 0f,
                    onClick = { onScreenshotClick(entry.id) },
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
fun ScreenshotGridItem(
    entry: ScreenshotEntry,
    currentImageProgress: Float = 0f,
    onClick: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surface,
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

      if (entry.isAnalyzing) {
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
              color = MaterialTheme.colorScheme.onSurface,
              lineHeight = 18.sp,
          )
        } else if (entry.isAnalyzing) {
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
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}
