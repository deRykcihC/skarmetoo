package com.deryk.skarmetoo.ui.screens

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.network.BenchmarkLeaderboardRepository
import com.deryk.skarmetoo.network.LeaderboardEntry
import com.deryk.skarmetoo.network.LeaderboardMetric
import com.deryk.skarmetoo.network.LeaderboardModel
import com.deryk.skarmetoo.network.LeaderboardSnapshot
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.components.SecondaryPageLayout
import com.deryk.skarmetoo.ui.components.rememberSquigglePillShape
import com.deryk.skarmetoo.ui.theme.AppMotion
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private sealed interface LeaderboardLoadState {
  data object Loading : LeaderboardLoadState

  data object OptedOut : LeaderboardLoadState

  data class Loaded(val snapshot: LeaderboardSnapshot) : LeaderboardLoadState

  data object Error : LeaderboardLoadState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("DEPRECATION")
@Composable
fun LeaderboardScreen(
    currentDeviceId: String,
    leaderboardOptedIn: Boolean,
    onLeaderboardOptInChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val isDebugBuild =
      remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
  val repository = remember { BenchmarkLeaderboardRepository(context) }
  val scope = rememberCoroutineScope()
  var refreshKey by remember { mutableIntStateOf(0) }
  var expandedEntryKeys by remember(refreshKey) { mutableStateOf<Set<String>>(emptySet()) }
  var showTop100 by rememberSaveable { mutableStateOf(false) }
  var showRules by rememberSaveable { mutableStateOf(false) }
  var showDataCollectionDialog by rememberSaveable { mutableStateOf(false) }
  var selectedMetricName by rememberSaveable {
    mutableStateOf(LeaderboardMetric.FASTEST_TIME_PER_IMAGE.name)
  }
  var selectedModelName by rememberSaveable { mutableStateOf(LeaderboardModel.ALL.name) }
  var loadState by remember { mutableStateOf<LeaderboardLoadState>(LeaderboardLoadState.Loading) }
  val selectedMetric =
      LeaderboardMetric.entries.firstOrNull { it.name == selectedMetricName }
          ?: LeaderboardMetric.FASTEST_TIME_PER_IMAGE
  val selectedModel =
      LeaderboardModel.entries.firstOrNull { it.name == selectedModelName }
          ?: LeaderboardModel.ALL
  val dataCollectionContentDescription =
      stringResource(R.string.leaderboard_data_collection_content_description)
  val dataTitle = stringResource(R.string.leaderboard_data_title)
  val dataDescription = stringResource(R.string.leaderboard_data_description)
  val participationLabel = stringResource(R.string.leaderboard_participation)
  val participationStatus =
      stringResource(
          if (leaderboardOptedIn) R.string.leaderboard_opted_in else R.string.leaderboard_opted_out,
      )
  val doneLabel = stringResource(R.string.done)
  val notificationPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

  fun updateLeaderboardOptIn(enabled: Boolean) {
    onLeaderboardOptInChanged(enabled)
    if (enabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  LaunchedEffect(refreshKey, leaderboardOptedIn) {
    if (!leaderboardOptedIn) {
      loadState = LeaderboardLoadState.OptedOut
      return@LaunchedEffect
    }
    loadState = LeaderboardLoadState.Loading
    repository.load(currentDeviceId) { result ->
      scope.launch {
        loadState =
            result.fold(
                onSuccess = { LeaderboardLoadState.Loaded(it) },
                onFailure = { LeaderboardLoadState.Error },
            )
      }
    }
  }

  SecondaryPageLayout(
      title = stringResource(R.string.leaderboard_title),
      onBack = onBack,
      bottomActions = {
        val infoInteraction = remember { MutableInteractionSource() }
        val warningInteraction = remember { MutableInteractionSource() }
        val infoContainerColor by animateColorAsState(
            if (showRules) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
            animationSpec = AppMotion.timed(240), label = "rulesButtonFill",
        )
        val infoContentColor by animateColorAsState(
            if (showRules) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer,
            animationSpec = AppMotion.timed(240), label = "rulesButtonContent",
        )
        if (isDebugBuild) {
          IconButton(onClick = hapticOnClick { refreshKey += 1 }, modifier = Modifier.size(40.dp), shape = CircleShape) {
            Icon(Icons.Rounded.Refresh, stringResource(R.string.leaderboard_refresh_content_description))
          }
        }
        ButtonGroup(
            modifier = Modifier.width(90.dp),
            expandedRatio = 0.12f,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
          FilledTonalButton(
              onClick = hapticOnClick { showRules = !showRules },
              modifier = Modifier.weight(1f).height(56.dp).animateWidth(infoInteraction),
              interactionSource = infoInteraction,
              colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = infoContainerColor,
                  contentColor = infoContentColor,
              ),
              contentPadding = PaddingValues(0.dp),
              shapes = ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape),
          ) {
            Icon(Icons.Rounded.Info, stringResource(R.string.leaderboard_rules_content_description))
          }
          FilledTonalButton(
              onClick = hapticOnClick { showDataCollectionDialog = true },
              modifier = Modifier.weight(1f).height(56.dp).animateWidth(warningInteraction),
              interactionSource = warningInteraction,
              contentPadding = PaddingValues(0.dp),
              shapes = ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
          ) {
            Icon(Icons.Rounded.WarningAmber, dataCollectionContentDescription)
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          LeaderboardChoiceMenu(
              selectedLabel = leaderboardModelLabel(selectedModel),
              colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
              ),
          ) { close ->
            LeaderboardModel.entries.forEach { model ->
              DropdownMenuItem(
                  text = { Text(leaderboardModelLabel(model)) },
                  onClick = hapticOnClick { selectedModelName = model.name; close() },
                  trailingIcon = { if (model == selectedModel) Icon(Icons.Rounded.Check, null) },
              )
            }
          }
          LeaderboardChoiceMenu(
              selectedLabel = stringResource(
                  when (selectedMetric) {
                    LeaderboardMetric.FASTEST_TIME_PER_IMAGE -> R.string.leaderboard_metric_image_short
                    LeaderboardMetric.FASTEST_TIME_PER_TEXT -> R.string.leaderboard_metric_text_short
                  }),
              colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              ),
          ) { close ->
            LeaderboardMetric.entries.forEach { metric ->
              DropdownMenuItem(
                  text = { Text(stringResource(metric.labelRes())) },
                  onClick = hapticOnClick { selectedMetricName = metric.name; close() },
                  trailingIcon = { if (metric == selectedMetric) Icon(Icons.Rounded.Check, null) },
              )
            }
          }
        }
      },
  ) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
      Spacer(modifier = Modifier.height(8.dp))

      Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        AnimatedVisibility(
            visible = showRules,
            enter =
                expandVertically(expandFrom = Alignment.Top, animationSpec = AppMotion.spatial()) +
                    fadeIn(animationSpec = AppMotion.effects()),
            exit =
                shrinkVertically(
                    shrinkTowards = Alignment.Top, animationSpec = AppMotion.linear(240)) +
                    fadeOut(animationSpec = AppMotion.linear(160)),
        ) {
          LeaderboardRulesCard()
        }
        when (val state = loadState) {
          LeaderboardLoadState.Loading -> LoadingLeaderboard()
          is LeaderboardLoadState.Error -> ErrorLeaderboard(onRetry = { refreshKey += 1 })
          LeaderboardLoadState.OptedOut -> OptedOutLeaderboard()
          is LeaderboardLoadState.Loaded ->
              LoadedLeaderboard(
                  snapshot = state.snapshot,
                  model = selectedModel,
                  metric = selectedMetric,
                  currentDeviceId = currentDeviceId,
                  showTop100 = showTop100,
                  onToggleListSize = { showTop100 = !showTop100 },
                  expandedEntryKeys = expandedEntryKeys,
                  onToggleEntry = { entryKey ->
                    expandedEntryKeys =
                        if (entryKey in expandedEntryKeys) {
                          expandedEntryKeys - entryKey
                        } else {
                          expandedEntryKeys + entryKey
                        }
                  },
              )
        }
      }
    }
  }

  if (showDataCollectionDialog) {
    AlertDialog(
        onDismissRequest = { showDataCollectionDialog = false },
        icon = {
          Icon(
              imageVector = Icons.Rounded.WarningAmber,
              contentDescription = dataCollectionContentDescription,
              tint = MaterialTheme.colorScheme.primary,
          )
        },
        title = { Text(dataTitle) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                dataDescription,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    participationLabel,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    participationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                  checked = leaderboardOptedIn,
                  onCheckedChange = ::updateLeaderboardOptIn,
              )
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showDataCollectionDialog = false }) { Text(doneLabel) }
        },
    )
  }
}

@StringRes
private fun LeaderboardMetric.labelRes(): Int =
    when (this) {
      LeaderboardMetric.FASTEST_TIME_PER_IMAGE -> R.string.leaderboard_metric_time_per_image
      LeaderboardMetric.FASTEST_TIME_PER_TEXT -> R.string.leaderboard_metric_time_per_text
    }

@Composable
private fun leaderboardModelLabel(model: LeaderboardModel): String =
    if (model == LeaderboardModel.ALL) {
      stringResource(R.string.leaderboard_model_all)
    } else {
      model.displayName
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaderboardChoiceMenu(
    selectedLabel: String,
    colors: ButtonColors,
    modifier: Modifier = Modifier,
    choices: @Composable (() -> Unit) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  Box(modifier = modifier) {
    FilledTonalButton(
        onClick = hapticOnClick { expanded = !expanded },
        modifier = Modifier.animateContentSize(
            animationSpec = AppMotion.timed(240), alignment = Alignment.CenterEnd,
        ).height(56.dp),
        interactionSource = interactionSource,
        colors = colors,
        shape = rememberSquigglePillShape(interactionSource, cornerRadius = 28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
      Text(
          selectedLabel,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        shape = RoundedCornerShape(24.dp),
    ) {
      choices { expanded = false }
    }
  }
}

@Composable
internal fun LeaderboardFilterRow(
    selectedModel: LeaderboardModel,
    onSelectModel: (LeaderboardModel) -> Unit,
    selectedMetric: LeaderboardMetric,
    onSelectMetric: (LeaderboardMetric) -> Unit,
    onToggleRules: () -> Unit,
) {
  val listState = rememberLazyListState()
  val modelInteractions = remember { LeaderboardModel.entries.associateWith { MutableInteractionSource() } }
  val metricInteractions = remember { LeaderboardMetric.entries.associateWith { MutableInteractionSource() } }
  // Keep the pulse alive when a selected pill moves to the front of the row.
  val modelShapes = LeaderboardModel.entries.associateWith { rememberSquigglePillShape(modelInteractions.getValue(it)) }
  val metricShapes = LeaderboardMetric.entries.associateWith { rememberSquigglePillShape(metricInteractions.getValue(it)) }
  val unselectedModels =
      remember(selectedModel) { LeaderboardModel.entries.filter { it != selectedModel } }
  val unselectedMetrics =
      remember(selectedMetric) { LeaderboardMetric.entries.filter { it != selectedMetric } }

  // Keep both active choices together at the leading edge. The rules icon is
  // still part of the row, followed by the selected model and metric; all
  // inactive choices move after them.
  LaunchedEffect(selectedModel, selectedMetric) { listState.animateScrollToItem(0) }

  LazyRow(
      state = listState,
      modifier = Modifier.fillMaxWidth().height(40.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    item(key = "rules") {
      Surface(
          modifier =
              Modifier.size(32.dp)
                  .clip(RoundedCornerShape(50))
                  .clickable(onClick = hapticOnClick(onToggleRules)),
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.secondaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
              imageVector = Icons.Rounded.Info,
              contentDescription = stringResource(R.string.leaderboard_rules_content_description),
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    // The selected model and metric stay adjacent at the leading edge. Model
    // filters use the tertiary palette so they remain visually distinct from
    // the metric filters below.
    item(key = "model:${selectedModel.name}") {
      val interactionSource = modelInteractions.getValue(selectedModel)
      FilterChip(
          interactionSource = interactionSource,
          selected = true,
          onClick = hapticOnClick { onSelectModel(selectedModel) },
          label = { Text(leaderboardModelLabel(selectedModel), fontWeight = FontWeight.Bold) },
          shape = modelShapes.getValue(selectedModel),
          colors =
              FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  containerColor = Color.Transparent,
                  labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }

    item(key = "metric:${selectedMetric.name}") {
      val interactionSource = metricInteractions.getValue(selectedMetric)
      FilterChip(
          interactionSource = interactionSource,
          selected = true,
          onClick = hapticOnClick { onSelectMetric(selectedMetric) },
          label = { Text(stringResource(selectedMetric.labelRes()), fontWeight = FontWeight.Bold) },
          shape = metricShapes.getValue(selectedMetric),
          colors =
              FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  containerColor = Color.Transparent,
                  labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }

    items(unselectedModels, key = { "model:${it.name}" }) { model ->
      val interactionSource = modelInteractions.getValue(model)
      FilterChip(
          interactionSource = interactionSource,
          selected = false,
          onClick = hapticOnClick { onSelectModel(model) },
          label = { Text(leaderboardModelLabel(model), fontWeight = FontWeight.Bold) },
          shape = modelShapes.getValue(model),
          colors =
              FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  containerColor = Color.Transparent,
                  labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }

    items(unselectedMetrics, key = { "metric:${it.name}" }) { metric ->
      val interactionSource = metricInteractions.getValue(metric)
      FilterChip(
          interactionSource = interactionSource,
          selected = false,
          onClick = hapticOnClick { onSelectMetric(metric) },
          label = { Text(stringResource(metric.labelRes()), fontWeight = FontWeight.Bold) },
          shape = metricShapes.getValue(metric),
          colors =
              FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  containerColor = Color.Transparent,
                  labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
      )
    }
  }
}

@Composable
private fun LoadingLeaderboard() {
  Box(
      modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
      contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
      }
}

@Composable
private fun ErrorLeaderboard(onRetry: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
          text = stringResource(R.string.leaderboard_unavailable),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Text(
          text = stringResource(R.string.leaderboard_load_failed),
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Button(
          onClick = hapticOnClick(onRetry),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.onErrorContainer,
                  contentColor = MaterialTheme.colorScheme.errorContainer,
              ),
      ) {
        Text(stringResource(R.string.leaderboard_try_again))
      }
    }
  }
}

@Composable
private fun OptedOutLeaderboard() {
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
          imageVector = Icons.Rounded.WarningAmber,
          contentDescription =
              stringResource(R.string.leaderboard_data_collection_content_description),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
      )
      Text(
          text = stringResource(R.string.leaderboard_participation_disabled),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun LoadedLeaderboard(
    snapshot: LeaderboardSnapshot,
    model: LeaderboardModel,
    metric: LeaderboardMetric,
    currentDeviceId: String,
    showTop100: Boolean,
    onToggleListSize: () -> Unit,
    expandedEntryKeys: Set<String>,
    onToggleEntry: (String) -> Unit,
) {
  val entries = snapshot.entriesFor(metric, model)
  val ownSnapshotEntry = entries.firstOrNull { it.deviceId == currentDeviceId }
  val uploadedOwnEntry = snapshot.bestDeviceEntryFor(metric, model)
  val ownEntry = ownSnapshotEntry ?: uploadedOwnEntry
  val ownEntryNeedsSnapshotRank = ownSnapshotEntry == null && uploadedOwnEntry != null
  val rankingLimit = if (showTop100) 100 else 10
  val rankedEntries = entries.filter { (it.rank ?: Int.MAX_VALUE) in 1..rankingLimit }
  val visibleEntries = rankedEntries.ifEmpty { entries.take(rankingLimit) }

  if (entries.isEmpty()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
            text = stringResource(R.string.leaderboard_empty_snapshot),
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      ownEntry?.let { entry ->
        Spacer(modifier = Modifier.height(18.dp))
        val ownKey =
            if (ownEntryNeedsSnapshotRank) "uploaded:${entryKey(entry)}"
            else "pinned:${entryKey(entry)}"
        LeaderboardEntryCard(
            entry = entry,
            metric = metric,
            isCurrentDevice = true,
            rankPending = ownEntryNeedsSnapshotRank,
            expanded = ownKey in expandedEntryKeys,
            onToggle = { onToggleEntry(ownKey) },
        )
      }
    }
  } else {
    Column(modifier = Modifier.fillMaxWidth()) {
      ownEntry?.let { entry ->
        val ownKey =
            if (ownEntryNeedsSnapshotRank) "uploaded:${entryKey(entry)}"
            else "pinned:${entryKey(entry)}"
        LeaderboardEntryCard(
            entry = entry,
            metric = metric,
            isCurrentDevice = true,
            rankPending = ownEntryNeedsSnapshotRank,
            expanded = ownKey in expandedEntryKeys,
            onToggle = { onToggleEntry(ownKey) },
        )
      }

      if (visibleEntries.isNotEmpty()) {
        Spacer(modifier = Modifier.height(if (ownEntry == null) 19.dp else 23.dp))
        Text(
            text = stringResource(R.string.leaderboard_top_n, if (showTop100) 100 else 10),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        visibleEntries.forEachIndexed { index, entry ->
          if (index > 0) Spacer(modifier = Modifier.height(14.dp))
          val rankedKey = "ranked:${entryKey(entry)}"
          LeaderboardEntryCard(
              entry = entry,
              metric = metric,
              isCurrentDevice = entry.deviceId == currentDeviceId,
              expanded = rankedKey in expandedEntryKeys,
              onToggle = { onToggleEntry(rankedKey) },
          )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            modifier = Modifier.fillMaxWidth().offset(y = (-6).dp),
            contentAlignment = Alignment.Center,
        ) {
          Row(
              modifier =
                  Modifier.clip(RoundedCornerShape(50))
                      .clickable(onClick = hapticOnClick(onToggleListSize))
                      .padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
          ) {
            Text(
                text =
                    stringResource(
                        if (showTop100) R.string.leaderboard_show_less
                        else R.string.leaderboard_see_more,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector =
                    if (showTop100) Icons.Rounded.KeyboardArrowUp
                    else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LeaderboardRulesCard() {
  val localRefreshTime = remember { localLeaderboardRefreshTime() }
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          ),
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
      Text(
          text = stringResource(R.string.leaderboard_rules),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = stringResource(R.string.leaderboard_rules_description, localRefreshTime),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun LeaderboardEntryCard(
    entry: LeaderboardEntry,
    metric: LeaderboardMetric,
    isCurrentDevice: Boolean,
    rankPending: Boolean = false,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
  val isDark = LocalIsDarkMode.current
  val featured = entry.rank == 1
  val deviceName =
      listOf(entry.deviceManufacturer, entry.deviceModel)
          .filter { !it.isNullOrBlank() }
          .joinToString(" ")
  val displayDeviceName =
      if (deviceName.isBlank()) stringResource(R.string.leaderboard_unknown_device) else deviceName
  val valueUnavailable = stringResource(R.string.leaderboard_value_unavailable)
  val rankText =
      entry.rank?.let { stringResource(R.string.leaderboard_rank, it) } ?: valueUnavailable
  val displayMetric = metricValue(entry, metric)
  val imageSize =
      if (entry.analyzedImageWidthPixels != null && entry.analyzedImageHeightPixels != null) {
        stringResource(
            R.string.leaderboard_image_size_value,
            formatNumber(entry.analyzedImageWidthPixels),
            formatNumber(entry.analyzedImageHeightPixels),
        )
      } else {
        valueUnavailable
      }
  val recordedTime = entry.recordedAt ?: entry.recordedAtClientMillis?.let { Date(it) }
  val totalPixels =
      entry.analyzedImagePixelCount?.let {
        stringResource(R.string.leaderboard_pixels_value, formatNumber(it))
      } ?: valueUnavailable
  val generatedText =
      entry.generatedTextCharacterCount?.let {
        stringResource(R.string.leaderboard_characters_value, formatNumber(it))
      } ?: valueUnavailable
  val recordedTimeText = recordedTime?.let(::formatDate) ?: valueUnavailable
  val standingColors = standingColors(entry.rank, isDark)
  val cardPadding = if (featured) 18.dp else 15.dp
  val timeTextSize =
      when (entry.rank) {
        1 -> 24.sp
        2 -> 22.sp
        3 -> 20.sp
        else -> 18.sp
      }

  Card(
      onClick = hapticOnClick(onToggle),
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(if (featured) 24.dp else 18.dp),
      colors = CardDefaults.cardColors(containerColor = standingColors.card),
      elevation = CardDefaults.cardElevation(
          defaultElevation = if (featured || isCurrentDevice) 0.dp else 1.dp,
          pressedElevation = if (isCurrentDevice) 0.dp else 1.dp,
          focusedElevation = if (isCurrentDevice) 0.dp else 1.dp,
          hoveredElevation = if (isCurrentDevice) 0.dp else 1.dp,
          draggedElevation = if (isCurrentDevice) 0.dp else 6.dp,
      ),
  ) {
    Column(
        modifier =
            Modifier.padding(
                start = cardPadding,
                top = cardPadding,
                end = cardPadding,
                bottom = cardPadding,
            )) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
                modifier = Modifier.weight(1f).offset(y = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                  modifier = Modifier.align(Alignment.CenterVertically),
                  shape = RoundedCornerShape(12.dp),
                  color = standingColors.badge,
              ) {
                if (rankPending) {
                  Box(
                      modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp).size(24.dp),
                      contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = rankText,
                        tint = standingColors.onBadge,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "?",
                        color = standingColors.onBadge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = (-1).dp),
                    )
                  }
                } else {
                  Text(
                      text = rankText,
                      modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                      color = standingColors.onBadge,
                      fontWeight = FontWeight.Bold,
                  )
                }
              }
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                if (isCurrentDevice) {
                  Text(
                      text = stringResource(R.string.leaderboard_your_device),
                      color = standingColors.content.copy(alpha = .72f),
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                      letterSpacing = .7.sp,
                  )
                }
                Text(
                    text = displayDeviceName,
                    style =
                        if (featured) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (featured) FontWeight.Bold else FontWeight.Medium,
                    color = standingColors.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.modelUsed ?: valueUnavailable,
                    color = standingColors.content.copy(alpha = .72f),
                    style =
                        if (featured) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
              }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.align(Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                  text = displayMetric.value ?: valueUnavailable,
                  color = standingColors.content,
                  fontSize = timeTextSize,
                  fontWeight = FontWeight.Bold,
              )
              Text(
                  text = stringResource(displayMetric.unitRes),
                  color = standingColors.content.copy(alpha = .72f),
                  style = MaterialTheme.typography.labelSmall,
              )
            }
          }

          AnimatedVisibility(
              visible = expanded,
              enter =
                  expandVertically(animationSpec = AppMotion.spatial()) +
                      fadeIn(animationSpec = AppMotion.effects()),
              exit =
                  shrinkVertically(animationSpec = AppMotion.fastSpatial()) +
                      fadeOut(animationSpec = AppMotion.fastEffects()),
          ) {
            Column(
                modifier = Modifier.padding(top = 9.dp),
            ) {
              Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(16.dp),
                  color = standingColors.content.copy(alpha = .08f),
              ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    DetailGridItem(
                        label = stringResource(R.string.leaderboard_total_pixels),
                        value = totalPixels,
                        contentColor = standingColors.content,
                        modifier = Modifier.weight(1f),
                    )
                    DetailGridItem(
                        label = stringResource(R.string.leaderboard_generated_text),
                        value = generatedText,
                        contentColor = standingColors.content,
                        modifier = Modifier.weight(1f),
                    )
                  }
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    DetailGridItem(
                        label = stringResource(R.string.leaderboard_image_size),
                        value = imageSize,
                        contentColor = standingColors.content,
                        modifier = Modifier.weight(1f),
                    )
                    DetailGridItem(
                        label = stringResource(R.string.leaderboard_recorded_time),
                        value = recordedTimeText,
                        contentColor = standingColors.content,
                        modifier = Modifier.weight(1f),
                    )
                  }
                }
              }
            }
          }
        }
  }
}

private data class StandingColors(
    val card: Color,
    val content: Color,
    val badge: Color,
    val onBadge: Color,
)

private data class MetricDisplay(val value: String?, @get:StringRes val unitRes: Int)

private fun metricValue(entry: LeaderboardEntry, metric: LeaderboardMetric): MetricDisplay =
    when (metric) {
      LeaderboardMetric.FASTEST_TIME_PER_IMAGE ->
          MetricDisplay(
              value =
                  formatDuration(
                      entry.durationSeconds ?: entry.durationMillis?.div(1000.0),
                  ),
              unitRes = R.string.leaderboard_unit_seconds_per_image,
          )
      LeaderboardMetric.FASTEST_TIME_PER_TEXT -> {
        val millisecondsPerText =
            entry.metricScore
                ?: entry.durationMillis?.toDouble()?.let { duration ->
                  entry.generatedTextCharacterCount
                      ?.takeIf { it > 0 }
                      ?.let { textCount -> duration / textCount }
                }
        MetricDisplay(
            value = formatMetricScore(millisecondsPerText),
            unitRes = R.string.leaderboard_unit_milliseconds_per_text,
        )
      }
    }

@Composable
private fun standingColors(rank: Int?, isDark: Boolean): StandingColors =
    when (rank) {
      1 ->
          if (isDark) {
            StandingColors(
                card = Color(0xFF594500),
                content = Color(0xFFFFE8A6),
                badge = Color(0xFFFFD45A),
                onBadge = Color(0xFF3A2D00),
            )
          } else {
            StandingColors(
                card = Color(0xFFFFE7A3),
                content = Color(0xFF3D2D00),
                badge = Color(0xFF8A6100),
                onBadge = Color.White,
            )
          }
      2 ->
          if (isDark) {
            StandingColors(
                card = Color(0xFF45484E),
                content = Color(0xFFE7E9EE),
                badge = Color(0xFFC4C8D0),
                onBadge = Color(0xFF292C31),
            )
          } else {
            StandingColors(
                card = Color(0xFFE3E6EB),
                content = Color(0xFF292C31),
                badge = Color(0xFF666A72),
                onBadge = Color.White,
            )
          }
      3 ->
          if (isDark) {
            StandingColors(
                card = Color(0xFF5A3828),
                content = Color(0xFFFFDDCC),
                badge = Color(0xFFD69A73),
                onBadge = Color(0xFF3A2013),
            )
          } else {
            StandingColors(
                card = Color(0xFFF1C6A8),
                content = Color(0xFF402315),
                badge = Color(0xFF8A4E29),
                onBadge = Color.White,
            )
          }
      else ->
          StandingColors(
              card = MaterialTheme.colorScheme.surfaceContainer,
              content = MaterialTheme.colorScheme.onSurface,
              badge = MaterialTheme.colorScheme.primaryContainer,
              onBadge = MaterialTheme.colorScheme.onPrimaryContainer,
          )
    }

@Composable
private fun DetailGridItem(
    label: String,
    value: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.height(42.dp).padding(horizontal = 4.dp),
      verticalArrangement = Arrangement.Center,
  ) {
    Text(
        text = label,
        color = contentColor.copy(alpha = .7f),
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        text = value,
        color = contentColor,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun entryKey(entry: LeaderboardEntry): String = entry.entryId ?: entry.deviceId

private fun formatNumber(value: Number): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun formatDuration(seconds: Double?): String? {
  if (seconds == null || !seconds.isFinite()) return null
  return String.format(Locale.getDefault(), "%.3f", seconds).trimEnd('0').trimEnd('.')
}

private fun formatMetricScore(score: Double?): String? {
  if (score == null || !score.isFinite()) return null
  return String.format(Locale.getDefault(), "%.3f", score).trimEnd('0').trimEnd('.')
}

private fun formatDate(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)

private fun localLeaderboardRefreshTime(): String {
  val singaporeZone = ZoneId.of("Asia/Singapore")
  val refreshInstant =
      Instant.now().atZone(singaporeZone).toLocalDate().atStartOfDay(singaporeZone).toInstant()
  return refreshInstant
      .atZone(ZoneId.systemDefault())
      .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
}
