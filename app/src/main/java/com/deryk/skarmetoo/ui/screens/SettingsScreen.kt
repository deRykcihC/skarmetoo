package com.deryk.skarmetoo.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_MODEL
import com.deryk.skarmetoo.ai.LlmManager
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import com.deryk.skarmetoo.viewmodel.ModelType
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import com.deryk.skarmetoo.viewmodel.SemanticSearchViewModel
import com.google.mlkit.genai.common.FeatureStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: ScreenshotViewModel,
    semanticViewModel: SemanticSearchViewModel,
    onStartScreenSaver: () -> Unit,
    logoRes: Int = R.drawable.app_logo,
    onRevisitTutorial: () -> Unit = {},
) {
  val isModelReady by viewModel.isModelReady.collectAsState()
  val modelStatus by viewModel.modelStatus.collectAsState()
  val currentDetailLevel by viewModel.detailLevel.collectAsState()
  val customPrompt by viewModel.customPrompt.collectAsState()
  val context = LocalContext.current

  val isDownloadingModel by viewModel.isDownloadingModel.collectAsState()
  val downloadProgress by viewModel.downloadProgress.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val isGemma3nDownloaded by viewModel.isGemma3nDownloaded.collectAsState()
  val isGemma4Downloaded by viewModel.isGemma4Downloaded.collectAsState()

  val ggufManager = remember { GgufLlmManager.getInstance(context) }
  val isGgufDownloading by ggufManager.isDownloading.collectAsState()
  val ggufDownloadProgress by ggufManager.downloadProgress.collectAsState()
  val ggufDownloadingModelName by ggufManager.downloadingModelName.collectAsState()

  val isLfmDownloaded by
      produceState(initialValue = ggufManager.isModelDownloaded(LFM2_5_MODEL), isGgufDownloading) {
        value = ggufManager.isModelDownloaded(LFM2_5_MODEL)
      }
  val downloadingModelType by viewModel.downloadingModelType.collectAsState()

  val modelPath = context.filesDir.absolutePath + "/" + selectedModel.fileName

  val gemma3nUrl =
      "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"
  val gemma4Url =
      "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm"

  var showHfLogin by remember { mutableStateOf(false) }
  var hfLoginModelType by remember { mutableStateOf(ModelType.GEMMA_3N) }
  var showMoreModels by remember { mutableStateOf(false) }
  var showMediaFolderDialog by remember { mutableStateOf(false) }
  var isHfLoggedIn by remember { mutableStateOf(false) }

  val isModelFound by viewModel.isModelFound.collectAsState()
  val sourceFolders by viewModel.sourceFolders.collectAsState()
  val availableAlbums by viewModel.availableAlbums.collectAsState()
  val selectedAlbums by viewModel.selectedAlbums.collectAsState()
  val folderImageCounts by viewModel.folderImageCounts.collectAsState()
  val sourceAllScreenshots by viewModel.entries.collectAsState()
  val sourceIsSemanticModelReady by semanticViewModel.isModelReady.collectAsState()
  val sourceIsSemanticIndexing by semanticViewModel.isIndexing.collectAsState()
  val sourceSemanticIndexedCount by semanticViewModel.indexedCount.collectAsState()
  var autoIndexPending by remember { mutableStateOf(false) }

  // Media permission launcher
  val mediaPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
          isGranted: Boolean ->
        if (isGranted) {
          viewModel.loadAlbums()
          showMediaFolderDialog = true
        }
      }

  val savedResolution by viewModel.imageResolution.collectAsState()
  val savedInstanceCount by viewModel.analysisInstanceCount.collectAsState()
  val savedShowPlayPause by viewModel.showPlayPauseToggle.collectAsState()
  val savedMaxTokens by viewModel.maxTokens.collectAsState()
  val savedBackgroundProcess by viewModel.backgroundProcessEnabled.collectAsState()
  val savedPageSize by viewModel.galleryPageSize.collectAsState()

  var localResolution by remember(savedResolution) { mutableIntStateOf(savedResolution) }
  var localInstanceCount by remember(savedInstanceCount) { mutableIntStateOf(savedInstanceCount) }
  var localShowPlayPause by remember(savedShowPlayPause) { mutableStateOf(savedShowPlayPause) }
  var localMaxTokens by remember(savedMaxTokens) { mutableIntStateOf(savedMaxTokens) }
  var localBackgroundProcess by
      remember(savedBackgroundProcess) { mutableStateOf(savedBackgroundProcess) }
  var localPageSize by remember(savedPageSize) { mutableIntStateOf(savedPageSize) }

  val notificationPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
          localBackgroundProcess = false
        }
      }

  val jsonSaveFolderUri by viewModel.jsonSaveFolderUri.collectAsState()
  val jsonSaveFolderName by viewModel.jsonSaveFolderName.collectAsState()
  val jsonLastBackupFilename by viewModel.jsonLastBackupFilename.collectAsState()
  val jsonLastBackupTime by viewModel.jsonLastBackupTime.collectAsState()

  // SAF Folder Picker launcher for external save location
  val jsonFolderPickerLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
          try {
            val takeFlags: Int =
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setJsonSaveFolderUri(uri)
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
      }

  var showMoreLanguages by remember { mutableStateOf(false) }
  var showMoreFolders by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.checkModelExists()
    val cookies = android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
    isHfLoggedIn = !cookies.isNullOrEmpty()
  }

  LaunchedEffect(selectedModel) {
    if (selectedModel == ModelType.GGUF) {
      viewModel.setAnalysisLanguage("en")
      showMoreLanguages = false
      if (currentDetailLevel == LlmManager.DetailLevel.COMPREHENSIVE) {
        viewModel.setDetailLevel(LlmManager.DetailLevel.DETAILED)
      }
    }
  }

  LaunchedEffect(
      autoIndexPending,
      sourceAllScreenshots.size,
      sourceIsSemanticModelReady,
      sourceIsSemanticIndexing) {
        if (!autoIndexPending ||
            !sourceIsSemanticModelReady ||
            sourceIsSemanticIndexing ||
            sourceAllScreenshots.isEmpty()) {
          return@LaunchedEffect
        }

        // Wait until folder import settles before kicking off one indexing run.
        delay(700)
        if (autoIndexPending &&
            sourceIsSemanticModelReady &&
            !sourceIsSemanticIndexing &&
            sourceAllScreenshots.isNotEmpty()) {
          semanticViewModel.startIndexing(sourceAllScreenshots)
          autoIndexPending = false
        }
      }

  val totalImages by viewModel.totalImageCount.collectAsState()
  val analyzedImages by viewModel.analyzedImageCount.collectAsState()
  val isDark = LocalIsDarkMode.current

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()),
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
            stringResource(R.string.settings),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.weight(1f))

        val currentLanguage by viewModel.appLanguage.collectAsState()

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
          Surface(
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
              shape = RoundedCornerShape(14.dp),
          ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                  IconButton(
                      onClick = hapticOnClick(onRevisitTutorial), modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.MenuBook,
                            contentDescription = "Tutorial",
                            modifier = Modifier.size(20.dp))
                      }
                  IconButton(
                      onClick = hapticOnClick { viewModel.setDarkMode(!isDark) },
                      modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                            contentDescription = "Toggle Dark Mode",
                            modifier = Modifier.size(20.dp))
                      }
                  IconButton(
                      onClick = hapticOnClick(onStartScreenSaver),
                      modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.Monitor,
                            contentDescription = "Screen Saver",
                            modifier = Modifier.size(20.dp))
                      }
                  IconButton(
                      onClick =
                          hapticOnClick {
                            val nextLang =
                                when (currentLanguage) {
                                  "en" -> "zh-rTW"
                                  else -> "en"
                                }
                            viewModel.setAppLanguage(nextLang)
                          },
                      modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.Language,
                            contentDescription = "Language",
                            modifier = Modifier.size(20.dp))
                      }
                }
          }
        }
      }

      // ===== Stats cards row =====
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2E42) else Color(0xFFE3F2FD)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
          Row(
              modifier = Modifier.padding(16.dp).fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically) {
                val blueText = if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)
                Column {
                  Text(
                      stringResource(R.string.analyzed),
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                      color = blueText,
                      letterSpacing = 1.sp,
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                      "$analyzedImages",
                      style = MaterialTheme.typography.headlineMedium,
                      fontWeight = FontWeight.Bold,
                      color = blueText,
                  )
                }
                val showPlayPause by viewModel.showPlayPauseToggle.collectAsState()
                val isAnalysisPaused by viewModel.isAnalysisPaused.collectAsState()
                if (showPlayPause) {
                  Surface(
                      shape = CircleShape,
                      color = if (isDark) Color(0xFF1E3A5F) else Color(0xFFBBDEFB),
                      modifier =
                          Modifier.size(40.dp).clip(CircleShape).clickable {
                            viewModel.toggleAnalysisPause()
                          }) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center) {
                              Icon(
                                  imageVector =
                                      if (isAnalysisPaused) Icons.Rounded.PlayArrow
                                      else Icons.Rounded.Pause,
                                  contentDescription = if (isAnalysisPaused) "Play" else "Pause",
                                  tint = blueText)
                            }
                      }
                }
              }
        }
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF3E3115) else Color(0xFFFFF8E1)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
          val amberText = if (isDark) Color(0xFFFFD54F) else Color(0xFFF57F17)
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.total_images),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = amberText,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "$totalImages",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = amberText,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      // ===== Section label =====
      Text(
          stringResource(R.string.app_settings).uppercase(),
          modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 1.sp,
      )

      Spacer(modifier = Modifier.height(12.dp))

      // ===== Source Folder Card =====
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0),
                    modifier = Modifier.size(34.dp),
                ) {
                  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        null,
                        tint = if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                        modifier = Modifier.size(20.dp))
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                      stringResource(R.string.source_folders),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Medium,
                  )
                  Text(
                      stringResource(R.string.folders_selected, selectedAlbums.size.toString()),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  )
                }
              }

              Spacer(modifier = Modifier.height(8.dp))

              val albumCounts = availableAlbums.filter { it.bucketId in selectedAlbums }
              val primaryColor = MaterialTheme.colorScheme.primary
              val chartColors =
                  remember(primaryColor) {
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(
                        (primaryColor.red * 255).toInt(),
                        (primaryColor.green * 255).toInt(),
                        (primaryColor.blue * 255).toInt(),
                        hsv)
                    List(12) { i ->
                      val newHsv =
                          floatArrayOf(
                              (hsv[0] + i * 73f) % 360f,
                              // Boost saturation and value for a more vibrant, "bright" look
                              (hsv[1] * 1.1f).coerceIn(0.5f, 0.9f),
                              (hsv[2] * 1.2f).coerceIn(0.7f, 1.0f))
                      // Alternate intensity to maintain distinction
                      if (i % 2 != 0) {
                        newHsv[1] *= 0.75f
                        newHsv[2] *= 0.85f
                      }
                      Color(android.graphics.Color.HSVToColor(newHsv))
                    }
                  }

              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically) {
                    if (selectedAlbums.isNotEmpty()) {
                      val totalInMap = albumCounts.sumOf { it.count }.coerceAtLeast(1)
                      Row(
                          modifier =
                              Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(8.dp)),
                      ) {
                        albumCounts.forEachIndexed { index, album ->
                          val weight = album.count.toFloat()
                          if (weight > 0) {
                            Box(
                                modifier =
                                    Modifier.weight(weight)
                                        .fillMaxHeight()
                                        .background(chartColors[index % chartColors.size]),
                            )
                          }
                        }
                      }
                    } else {
                      Surface(
                          modifier = Modifier.weight(1f).height(16.dp),
                          shape = RoundedCornerShape(8.dp),
                          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {}
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick =
                            hapticOnClick {
                              val permission =
                                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_IMAGES
                                  } else {
                                    @Suppress("DEPRECATION")
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                  }
                              if (android.content.pm.PackageManager.PERMISSION_GRANTED ==
                                  ContextCompat.checkSelfPermission(context, permission)) {
                                viewModel.loadAlbums()
                                showMediaFolderDialog = true
                              } else {
                                mediaPermissionLauncher.launch(permission)
                              }
                            },
                        modifier =
                            Modifier.size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    CircleShape)
                                .clip(CircleShape),
                    ) {
                      Icon(
                          Icons.Rounded.CreateNewFolder,
                          contentDescription = stringResource(R.string.add_media_folder),
                          tint = MaterialTheme.colorScheme.onPrimaryContainer,
                          modifier = Modifier.size(20.dp))
                    }
                  }

              if (selectedAlbums.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Column {
                  val totalInMap = albumCounts.sumOf { it.count }.coerceAtLeast(1)

                  // Show all albums with slide down animation
                  androidx.compose.animation.AnimatedVisibility(
                      visible = showMoreFolders,
                      enter =
                          androidx.compose.animation.expandVertically() +
                              androidx.compose.animation.fadeIn(),
                      exit =
                          androidx.compose.animation.shrinkVertically() +
                              androidx.compose.animation.fadeOut()) {
                        val shownIndexedCount =
                            sourceSemanticIndexedCount.coerceIn(0, sourceAllScreenshots.size)
                        val uniqueHashCount =
                            remember(sourceAllScreenshots) {
                              sourceAllScreenshots
                                  .map { it.imageHash }
                                  .filter { it.isNotBlank() }
                                  .toSet()
                                  .size
                            }
                        Column {
                          Surface(
                              modifier = Modifier.fillMaxWidth(),
                              shape = RoundedCornerShape(12.dp),
                              color =
                                  MaterialTheme.colorScheme.surfaceVariant.copy(
                                      alpha = if (isDark) 0.22f else 0.45f)) {
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                      Box(modifier = Modifier.size(20.dp)) {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(20.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier =
                                                Modifier.size(10.dp)
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 1.dp, y = (-1).dp))
                                      }
                                      Spacer(modifier = Modifier.width(12.dp))
                                      Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                          Text(
                                              stringResource(R.string.look_similar_title_caps),
                                              style = MaterialTheme.typography.labelMedium,
                                              fontWeight = FontWeight.SemiBold,
                                              color = MaterialTheme.colorScheme.onSurface)
                                          Spacer(modifier = Modifier.width(6.dp))
                                          Surface(
                                              color =
                                                  MaterialTheme.colorScheme.primary.copy(
                                                      alpha = 0.15f),
                                              shape = RoundedCornerShape(4.dp)) {
                                                Text(
                                                    text = stringResource(R.string.beta),
                                                    modifier =
                                                        Modifier.padding(
                                                            horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold)
                                              }
                                        }
                                        Text(
                                            text =
                                                "$shownIndexedCount / ${sourceAllScreenshots.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                      }
                                      Spacer(modifier = Modifier.weight(1f))
                                      TextButton(
                                          onClick =
                                              hapticOnClick {
                                                semanticViewModel.startIndexing(
                                                    sourceAllScreenshots)
                                              },
                                          enabled =
                                              sourceIsSemanticModelReady &&
                                                  sourceAllScreenshots.isNotEmpty() &&
                                                  !sourceIsSemanticIndexing,
                                          contentPadding =
                                              PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                          modifier = Modifier.heightIn(min = 26.dp)) {
                                            if (sourceIsSemanticIndexing) {
                                              CircularProgressIndicator(
                                                  modifier = Modifier.size(14.dp),
                                                  strokeWidth = 1.8.dp,
                                                  color = MaterialTheme.colorScheme.primary)
                                            } else {
                                              Text(
                                                  text = stringResource(R.string.refresh_index),
                                                  style = MaterialTheme.typography.labelSmall,
                                                  fontWeight = FontWeight.SemiBold)
                                            }
                                          }
                                      IconButton(
                                          onClick =
                                              hapticOnClick {
                                                semanticViewModel.resetEmbeddingsDatabase()
                                              },
                                          enabled = !sourceIsSemanticIndexing,
                                          modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteSweep,
                                                contentDescription =
                                                    stringResource(R.string.clear_index),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp))
                                          }
                                    }
                              }
                          Spacer(modifier = Modifier.height(8.dp))
                          albumCounts.forEachIndexed { index, album ->
                            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                            val rawPercentage = (album.count.toFloat() / totalInMap * 100)
                            val percentageText =
                                if (rawPercentage < 1f && album.count > 0) "<1"
                                else rawPercentage.roundToInt().toString()
                            val folderColor = chartColors[index % chartColors.size]

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()) {
                                  Surface(
                                      shape = RoundedCornerShape(6.dp),
                                      color = folderColor,
                                      modifier = Modifier.width(42.dp)) {
                                        Text(
                                            text = "$percentageText%",
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            style =
                                                MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp),
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            textAlign = TextAlign.Center)
                                      }
                                  Spacer(modifier = Modifier.width(12.dp))
                                  Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { album.count.toFloat() / totalInMap },
                                        modifier =
                                            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                        color = folderColor,
                                        trackColor = folderColor.copy(alpha = 0.1f),
                                    )
                                  }
                                }
                          }
                        }
                      }
                  Spacer(modifier = Modifier.height(6.dp))
                  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier =
                            Modifier.clip(CircleShape)
                                .clickable { showMoreFolders = !showMoreFolders }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                          Text(
                              if (showMoreFolders) stringResource(R.string.hide_all)
                              else stringResource(R.string.show_all),
                              style = MaterialTheme.typography.labelMedium,
                              color = MaterialTheme.colorScheme.primary,
                              fontWeight = FontWeight.SemiBold)
                          Spacer(modifier = Modifier.width(4.dp))
                          Icon(
                              imageVector =
                                  if (showMoreFolders) Icons.Rounded.KeyboardArrowUp
                                  else Icons.Rounded.KeyboardArrowDown,
                              contentDescription = "Expand folders",
                              tint = MaterialTheme.colorScheme.primary,
                              modifier = Modifier.size(16.dp))
                        }
                  }
                }
              }
            }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // ===== AI Model card (merged with status) =====
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color =
                    if (isModelReady) (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                    else (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0)),
                modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Rounded.Psychology,
                    null,
                    tint =
                        if (isModelReady) (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32))
                        else (if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100)),
                    modifier = Modifier.size(20.dp),
                )
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                  stringResource(R.string.ai_model),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Medium,
              )
              Text(
                  stringResource(R.string.on_device_analysis_model),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }
            // If the model file exists on disk but isn't ready yet, it must be loading
            // (or about to load). Never show "Offline" in that case.
            val statusText =
                when {
                  isModelReady -> stringResource(R.string.ready)
                  !isModelFound && !isDownloadingModel -> stringResource(R.string.status_no_model)
                  isModelFound || isDownloadingModel -> stringResource(R.string.status_loading)
                  else -> stringResource(R.string.status_offline)
                }
            val statusBg =
                when {
                  isModelReady -> if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)
                  isModelFound || isDownloadingModel ->
                      if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0)
                  !isModelFound -> MaterialTheme.colorScheme.errorContainer
                  else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
            val statusColor =
                when {
                  isModelReady -> if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                  isModelFound || isDownloadingModel ->
                      if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100)
                  !isModelFound -> MaterialTheme.colorScheme.onErrorContainer
                  else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusBg,
            ) {
              Text(
                  text = statusText,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = statusColor,
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Model Selector
          Text(
              stringResource(R.string.select_model),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(6.dp))

          val isGemma4Selected = selectedModel == ModelType.GEMMA_4 && isGemma4Downloaded
          val isDownloadingGemma4 = isDownloadingModel && downloadingModelType == ModelType.GEMMA_4
          OutlinedCard(
              onClick =
                  hapticOnClick {
                    viewModel.setSelectedModel(ModelType.GEMMA_4)
                    if (isGemma4Downloaded) {
                      val path = context.filesDir.absolutePath + "/" + ModelType.GEMMA_4.fileName
                      viewModel.initializeModel(path, isGemma4 = true)
                    } else if (!isDownloadingModel) {
                      hfLoginModelType = ModelType.GEMMA_4
                      showHfLogin = true
                    }
                  },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(14.dp),
              colors =
                  CardDefaults.outlinedCardColors(
                      containerColor =
                          if (isGemma4Selected) MaterialTheme.colorScheme.secondaryContainer
                          else Color.Transparent,
                  ),
              border =
                  if (isGemma4Selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                  else CardDefaults.outlinedCardBorder(),
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                FlowRow {
                  Text(
                      stringResource(R.string.model_gemma4),
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = if (isGemma4Selected) FontWeight.Bold else FontWeight.Medium,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Surface(
                      color = (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)),
                      shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = stringResource(R.string.recommended_tag),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)),
                        )
                      }
                  Spacer(modifier = Modifier.width(4.dp))
                  Surface(
                      color = MaterialTheme.colorScheme.surfaceVariant,
                      shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = stringResource(R.string.tags_tag),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                }
                Text(
                    "litert-community/gemma-4-e2b-it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    stringResource(R.string.model_gemma4_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Surface(
                  shape = RoundedCornerShape(8.dp),
                  color =
                      if (isDownloadingGemma4)
                          (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0))
                      else if (isGemma4Downloaded)
                          (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                      else MaterialTheme.colorScheme.surfaceContainerHighest,
                  modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                      if (isDownloadingGemma4) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                        )
                      } else if (isGemma4Downloaded) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Downloaded",
                            tint = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp))
                      } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download Model",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                      }
                    }
                  }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          val isLfmSelected = selectedModel == ModelType.GGUF && isLfmDownloaded
          val isDownloadingLfm =
              isGgufDownloading && ggufDownloadingModelName == LFM2_5_MODEL.displayName

          OutlinedCard(
              onClick =
                  hapticOnClick {
                    if (isLfmDownloaded) {
                      viewModel.setGgufModelAsActive(LFM2_5_MODEL)
                    } else if (!isGgufDownloading) {
                      ggufManager.downloadModel(
                          LFM2_5_MODEL,
                          onComplete = { success ->
                            if (success) {
                              viewModel.setGgufModelAsActive(LFM2_5_MODEL)
                            }
                          })
                    }
                  },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(14.dp),
              colors =
                  CardDefaults.outlinedCardColors(
                      containerColor =
                          if (isLfmSelected) MaterialTheme.colorScheme.secondaryContainer
                          else Color.Transparent,
                  ),
              border =
                  if (isLfmSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                  else CardDefaults.outlinedCardBorder(),
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                FlowRow {
                  Text(
                      LFM2_5_MODEL.displayName,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = if (isLfmSelected) FontWeight.Bold else FontWeight.Medium,
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Surface(
                      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                      shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = stringResource(R.string.beta),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                      }
                  Spacer(modifier = Modifier.width(4.dp))
                  Surface(
                      color = Color(0xFFD32F2F).copy(alpha = 0.15f),
                      shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = stringResource(R.string.no_tags),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold)
                      }
                }
                Text(
                    "LiquidAI/LFM2.5-VL-450M",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    stringResource(R.string.model_lfm_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Surface(
                  shape = RoundedCornerShape(8.dp),
                  color =
                      if (isDownloadingLfm) (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0))
                      else if (isLfmDownloaded)
                          (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                      else MaterialTheme.colorScheme.surfaceContainerHighest,
                  modifier = Modifier.size(32.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                      if (isDownloadingLfm) {
                        Text(
                            text = "${(ggufDownloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                        )
                      } else if (isLfmDownloaded) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Downloaded",
                            tint = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp))
                      } else {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download Model",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                      }
                    }
                  }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable { showMoreModels = !showMoreModels }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                  Text(
                      stringResource(R.string.more_models),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.SemiBold)
                  Spacer(modifier = Modifier.width(4.dp))
                  Icon(
                      imageVector =
                          if (showMoreModels) Icons.Rounded.KeyboardArrowUp
                          else Icons.Rounded.KeyboardArrowDown,
                      contentDescription = "Expand models",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(16.dp))
                }
          }

          androidx.compose.animation.AnimatedVisibility(
              visible = showMoreModels,
              enter =
                  androidx.compose.animation.expandVertically() +
                      androidx.compose.animation.fadeIn(),
              exit =
                  androidx.compose.animation.shrinkVertically() +
                      androidx.compose.animation.fadeOut()) {
                Column {
                  Spacer(modifier = Modifier.height(6.dp))
                  val isGemma3nSelected = selectedModel == ModelType.GEMMA_3N && isGemma3nDownloaded
                  val isDownloadingGemma3n =
                      isDownloadingModel && downloadingModelType == ModelType.GEMMA_3N
                  OutlinedCard(
                      onClick =
                          hapticOnClick {
                            viewModel.setSelectedModel(ModelType.GEMMA_3N)
                            if (isGemma3nDownloaded) {
                              val path =
                                  context.filesDir.absolutePath + "/" + ModelType.GEMMA_3N.fileName
                              viewModel.initializeModel(path, isGemma4 = false)
                            } else if (!isDownloadingModel) {
                              hfLoginModelType = ModelType.GEMMA_3N
                              showHfLogin = true
                            }
                          },
                      modifier = Modifier.fillMaxWidth(),
                      shape = RoundedCornerShape(14.dp),
                      colors =
                          CardDefaults.outlinedCardColors(
                              containerColor =
                                  if (isGemma3nSelected)
                                      MaterialTheme.colorScheme.secondaryContainer
                                  else Color.Transparent,
                          ),
                      border =
                          if (isGemma3nSelected)
                              BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                          else CardDefaults.outlinedCardBorder(),
                  ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        FlowRow {
                          Text(
                              stringResource(R.string.model_gemma3n),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight =
                                  if (isGemma3nSelected) FontWeight.Bold else FontWeight.Medium,
                          )
                          Spacer(modifier = Modifier.width(8.dp))
                          Surface(
                              color = MaterialTheme.colorScheme.surfaceVariant,
                              shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = stringResource(R.string.tags_tag),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                              }
                        }
                        Text(
                            "google/gemma-3n-e2b-it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            stringResource(R.string.model_gemma3n_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Spacer(modifier = Modifier.width(12.dp))
                      Surface(
                          shape = RoundedCornerShape(8.dp),
                          color =
                              if (isDownloadingGemma3n)
                                  (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0))
                              else if (isGemma3nDownloaded)
                                  (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                              else MaterialTheme.colorScheme.surfaceContainerHighest,
                          modifier = Modifier.size(32.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
                                  if (isDownloadingGemma3n) {
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp),
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                                    )
                                  } else if (isGemma3nDownloaded) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Downloaded",
                                        tint = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                                        modifier = Modifier.size(20.dp))
                                  } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Download Model",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp))
                                  }
                                }
                          }
                    }
                  }

                  Spacer(modifier = Modifier.height(8.dp))

                  val isAicoreSelected = selectedModel == ModelType.AICORE
                  val aicoreStatus by viewModel.aicoreCachedStatus.collectAsState()

                  OutlinedCard(
                      onClick =
                          hapticOnClick {
                            viewModel.setSelectedModel(ModelType.AICORE)
                            viewModel.triggerAicoreRescan()
                          },
                      modifier = Modifier.fillMaxWidth(),
                      shape = RoundedCornerShape(14.dp),
                      colors =
                          CardDefaults.outlinedCardColors(
                              containerColor =
                                  if (isAicoreSelected) MaterialTheme.colorScheme.secondaryContainer
                                  else Color.Transparent,
                          ),
                      border =
                          if (isAicoreSelected)
                              BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                          else CardDefaults.outlinedCardBorder(),
                  ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        FlowRow {
                          Text(
                              "Gemini Nano",
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight =
                                  if (isAicoreSelected) FontWeight.Bold else FontWeight.Medium,
                          )
                          Spacer(modifier = Modifier.width(8.dp))
                          Surface(
                              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                              shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = "BETA",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                              }
                        }
                        Text(
                            "Android AICore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            stringResource(R.string.gemini_nano_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Spacer(modifier = Modifier.width(12.dp))

                      val aicoreColor =
                          if (aicoreStatus == FeatureStatus.AVAILABLE) {
                            if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)
                          } else if (aicoreStatus == FeatureStatus.UNAVAILABLE) {
                            MaterialTheme.colorScheme.errorContainer
                          } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                          }

                      Surface(
                          shape = RoundedCornerShape(8.dp),
                          color = aicoreColor,
                          modifier = Modifier.size(32.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
                                  if (aicoreStatus == FeatureStatus.AVAILABLE) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Ready",
                                        tint = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                                        modifier = Modifier.size(20.dp))
                                  } else if (aicoreStatus == FeatureStatus.UNAVAILABLE) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Unsupported",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp))
                                  } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = "Check Setup",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp))
                                  }
                                }
                          }
                    }
                  }
                }
              }

          Spacer(modifier = Modifier.height(8.dp))

          // Analysis Language Row
          Text(
              stringResource(R.string.language),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(6.dp))
          val analysisLang by viewModel.analysisLanguage.collectAsState()

          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            DetailLevelCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.language_en),
                selected = analysisLang == "en",
                onClick =
                    hapticOnClick {
                      viewModel.setAnalysisLanguage("en")
                      showMoreLanguages = false
                    },
            )
            DetailLevelCard(
                modifier = Modifier.weight(1f),
                label =
                    if (analysisLang != "en" && !showMoreLanguages) {
                      when (analysisLang) {
                        "zh-rTW" -> stringResource(R.string.language_zh)
                        "hi" -> stringResource(R.string.language_hi)
                        "es" -> stringResource(R.string.language_es)
                        "ar" -> stringResource(R.string.language_ar)
                        "fr" -> stringResource(R.string.language_fr)
                        "ru" -> stringResource(R.string.language_ru)
                        else -> stringResource(R.string.language_more)
                      }
                    } else {
                      stringResource(R.string.language_more)
                    },
                selected = analysisLang != "en",
                enabled = selectedModel != ModelType.GGUF,
                onClick = hapticOnClick { showMoreLanguages = !showMoreLanguages },
            )
          }

          androidx.compose.animation.AnimatedVisibility(
              visible = showMoreLanguages,
              enter =
                  androidx.compose.animation.expandVertically() +
                      androidx.compose.animation.fadeIn(),
              exit =
                  androidx.compose.animation.shrinkVertically() +
                      androidx.compose.animation.fadeOut(),
          ) {
            Column {
              Spacer(modifier = Modifier.height(8.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_zh),
                        selected = analysisLang == "zh-rTW",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("zh-rTW")
                              showMoreLanguages = false
                            })
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_hi),
                        selected = analysisLang == "hi",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("hi")
                              showMoreLanguages = false
                            })
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_es),
                        selected = analysisLang == "es",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("es")
                              showMoreLanguages = false
                            })
                  }
              Spacer(modifier = Modifier.height(8.dp))
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_ar),
                        selected = analysisLang == "ar",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("ar")
                              showMoreLanguages = false
                            })
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_fr),
                        selected = analysisLang == "fr",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("fr")
                              showMoreLanguages = false
                            })
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_ru),
                        selected = analysisLang == "ru",
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("ru")
                              showMoreLanguages = false
                            })
                  }
            }
          }

          Spacer(modifier = Modifier.height(24.dp))

          // Content Analysis Depth
          Text(
              stringResource(R.string.analysis_detail),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(6.dp))
          val detailLevels = LlmManager.DetailLevel.entries
          Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              detailLevels.take(2).forEach { level ->
                DetailLevelCard(
                    modifier = Modifier.weight(1f),
                    label =
                        when (level.label) {
                          "Brief" -> stringResource(R.string.brief)
                          "Detailed" -> stringResource(R.string.detailed)
                          "Comprehensive" -> stringResource(R.string.full)
                          "Custom" -> stringResource(R.string.custom)
                          else -> level.label
                        },
                    selected = currentDetailLevel == level,
                    enabled =
                        !(level == LlmManager.DetailLevel.COMPREHENSIVE &&
                            selectedModel == ModelType.GGUF),
                    onClick = hapticOnClick { viewModel.setDetailLevel(level) },
                )
              }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              detailLevels.drop(2).forEach { level ->
                DetailLevelCard(
                    modifier = Modifier.weight(1f),
                    label =
                        when (level.label) {
                          "Brief" -> stringResource(R.string.brief)
                          "Detailed" -> stringResource(R.string.detailed)
                          "Comprehensive" -> stringResource(R.string.full)
                          "Custom" -> stringResource(R.string.custom)
                          else -> level.label
                        },
                    selected = currentDetailLevel == level,
                    enabled =
                        !(level == LlmManager.DetailLevel.COMPREHENSIVE &&
                            selectedModel == ModelType.GGUF),
                    onClick = hapticOnClick { viewModel.setDetailLevel(level) },
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          Text(
              text =
                  when (currentDetailLevel) {
                    LlmManager.DetailLevel.BRIEF -> stringResource(R.string.desc_brief)
                    LlmManager.DetailLevel.DETAILED -> stringResource(R.string.desc_detailed)
                    LlmManager.DetailLevel.COMPREHENSIVE -> stringResource(R.string.desc_full)
                    LlmManager.DetailLevel.CUSTOM -> stringResource(R.string.desc_custom)
                  },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              lineHeight = 18.sp,
          )

          androidx.compose.animation.AnimatedVisibility(
              visible = currentDetailLevel == LlmManager.DetailLevel.CUSTOM,
              enter =
                  androidx.compose.animation.expandVertically() +
                      androidx.compose.animation.fadeIn(),
              exit =
                  androidx.compose.animation.shrinkVertically() +
                      androidx.compose.animation.fadeOut(),
          ) {
            Column {
              Spacer(modifier = Modifier.height(12.dp))
              OutlinedTextField(
                  value = customPrompt,
                  onValueChange = { if (it.length <= 500) viewModel.setCustomPrompt(it) },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.custom_prompt_inst)) },
                  placeholder = { Text(stringResource(R.string.search_hint)) },
                  shape = RoundedCornerShape(12.dp),
                  minLines = 3,
                  colors =
                      OutlinedTextFieldDefaults.colors(
                          focusedBorderColor = MaterialTheme.colorScheme.primary,
                          unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                      ),
                  supportingText = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd) {
                          Text(
                              text = "${customPrompt.length}/500",
                              style = MaterialTheme.typography.labelSmall,
                              color =
                                  if (customPrompt.length >= 500) MaterialTheme.colorScheme.error
                                  else MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                  })
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      if (false) {
        // ===== Look similar Card =====
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            val isSemanticModelReady by semanticViewModel.isModelReady.collectAsState()
            val isSemanticDownloading by semanticViewModel.isDownloading.collectAsState()
            val semanticDownloadProgress by semanticViewModel.downloadProgress.collectAsState()
            val isSemanticIndexing by semanticViewModel.isIndexing.collectAsState()
            val semanticIndexingProgress by semanticViewModel.indexingProgress.collectAsState()
            val semanticIndexedCount by semanticViewModel.indexedCount.collectAsState()
            val allScreenshots by viewModel.entries.collectAsState()

            Row(verticalAlignment = Alignment.CenterVertically) {
              Surface(
                  shape = CircleShape,
                  color =
                      if (isSemanticModelReady)
                          (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                      else (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0)),
                  modifier = Modifier.size(34.dp),
              ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                  val badgeIconColor =
                      if (isSemanticModelReady)
                          (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32))
                      else (if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100))

                  Box(modifier = Modifier.size(18.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = badgeIconColor,
                        modifier = Modifier.size(18.dp))
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = badgeIconColor,
                        modifier =
                            Modifier.size(9.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 1.dp, y = (-1).dp))
                  }
                }
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.look_similar_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.look_similar_settings_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
              }

              val statusText =
                  when {
                    isSemanticModelReady -> "Ready"
                    isSemanticDownloading -> "Loading"
                    else -> "No Model"
                  }
              val statusBg =
                  when {
                    isSemanticModelReady -> if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)
                    isSemanticDownloading -> if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0)
                    else -> MaterialTheme.colorScheme.errorContainer
                  }
              val statusColor =
                  when {
                    isSemanticModelReady -> if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                    isSemanticDownloading -> if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.onErrorContainer
                  }

              Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = statusBg,
              ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isSemanticModelReady) {
              OutlinedCard(
                  onClick =
                      hapticOnClick {
                        if (!isSemanticDownloading) {
                          semanticViewModel.downloadModel()
                        }
                      },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(14.dp),
                  border = CardDefaults.outlinedCardBorder(),
              ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Visual Search Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "efficientnet_lite0.tflite (18.5 MB)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Local visual classifier used to map screenshots to visual coordinates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Surface(
                      shape = RoundedCornerShape(8.dp),
                      color =
                          if (isSemanticDownloading)
                              (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0))
                          else MaterialTheme.colorScheme.surfaceContainerHighest,
                      modifier = Modifier.size(32.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              if (isSemanticDownloading) {
                                Text(
                                    text = "${(semanticDownloadProgress * 100).toInt()}%",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                                )
                              } else {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "Download Model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                              }
                            }
                      }
                }
              }
            } else {
              Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                      Column {
                        Text(
                            "Semantic Indexing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))

                        val unindexedDebugEntries by
                            semanticViewModel.unindexedDebugEntries.collectAsState()
                        val isInspectingUnindexed by
                            semanticViewModel.isInspectingUnindexed.collectAsState()
                        var showUnindexedDialog by remember { mutableStateOf(false) }
                        val unindexedCount =
                            (allScreenshots.size - semanticIndexedCount).coerceAtLeast(0)
                        val uniqueHashCount =
                            remember(allScreenshots) {
                              allScreenshots
                                  .map { it.imageHash }
                                  .filter { it.isNotBlank() }
                                  .toSet()
                                  .size
                            }
                        val duplicateHashCount =
                            (allScreenshots.size - uniqueHashCount).coerceAtLeast(0)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                              text = "Indexed: $semanticIndexedCount / ${allScreenshots.size}",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurface,
                          )
                          if (unindexedCount > 0 && !isSemanticIndexing) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier =
                                    Modifier.clickable(
                                        onClick =
                                            hapticOnClick {
                                              if (!isInspectingUnindexed) {
                                                semanticViewModel.inspectUnindexedEntries(
                                                    allScreenshots)
                                                showUnindexedDialog = true
                                              }
                                            })) {
                                  Row(
                                      modifier =
                                          Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                      verticalAlignment = Alignment.CenterVertically) {
                                        if (isInspectingUnindexed) {
                                          CircularProgressIndicator(
                                              modifier = Modifier.size(10.dp),
                                              strokeWidth = 1.5.dp,
                                              color =
                                                  MaterialTheme.colorScheme.onSecondaryContainer)
                                        } else {
                                          Icon(
                                              imageVector = Icons.Rounded.BugReport,
                                              contentDescription = "Debug unindexed",
                                              tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                              modifier = Modifier.size(12.dp))
                                          Spacer(modifier = Modifier.width(4.dp))
                                          Text(
                                              text = "Debug $unindexedCount",
                                              style =
                                                  MaterialTheme.typography.labelSmall.copy(
                                                      fontSize = 10.sp),
                                              fontWeight = FontWeight.Bold,
                                              color =
                                                  MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                      }
                                }
                          }
                        }

                        if (showUnindexedDialog) {
                          AlertDialog(
                              onDismissRequest = { showUnindexedDialog = false },
                              title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                  Icon(
                                      imageVector = Icons.Rounded.BugReport,
                                      contentDescription = null,
                                      tint = MaterialTheme.colorScheme.secondary,
                                      modifier = Modifier.size(24.dp))
                                  Spacer(modifier = Modifier.width(8.dp))
                                  Text("Unindexed Debug (${unindexedDebugEntries.size})")
                                }
                              },
                              text = {
                                Column {
                                  Text(
                                      "Index vectors are stored by image URI. Hash collisions can happen, so duplicate hashes are indexed as separate images.",
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                                  Spacer(modifier = Modifier.height(4.dp))
                                  Text(
                                      "Unique hashes: $uniqueHashCount • Duplicate entries: $duplicateHashCount",
                                      style = MaterialTheme.typography.labelSmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                                  Spacer(modifier = Modifier.height(8.dp))
                                  androidx.compose.foundation.lazy.LazyColumn(
                                      modifier = Modifier.heightIn(max = 240.dp),
                                      verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(unindexedDebugEntries.size) { index ->
                                          val debugEntry = unindexedDebugEntries[index]
                                          val entry = debugEntry.entry
                                          Card(
                                              modifier = Modifier.fillMaxWidth(),
                                              colors =
                                                  CardDefaults.cardColors(
                                                      containerColor =
                                                          MaterialTheme.colorScheme.surfaceVariant
                                                              .copy(alpha = 0.5f))) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                  Text(
                                                      text =
                                                          "ID: ${entry.id} • ${debugEntry.reason}",
                                                      style = MaterialTheme.typography.labelSmall,
                                                      fontWeight = FontWeight.Bold)
                                                  Text(
                                                      text =
                                                          "Hash: ${entry.imageHash.ifBlank { "(blank)" }}",
                                                      style =
                                                          MaterialTheme.typography.bodySmall.copy(
                                                              fontSize = 10.sp),
                                                      color =
                                                          MaterialTheme.colorScheme
                                                              .onSurfaceVariant,
                                                      maxLines = 1,
                                                      overflow =
                                                          androidx.compose.ui.text.style
                                                              .TextOverflow
                                                              .Ellipsis)
                                                  Text(
                                                      text =
                                                          "URI: ${entry.imageUri.ifBlank { "(blank)" }}",
                                                      style =
                                                          MaterialTheme.typography.bodySmall.copy(
                                                              fontSize = 10.sp),
                                                      color =
                                                          MaterialTheme.colorScheme
                                                              .onSurfaceVariant,
                                                      maxLines = 1,
                                                      overflow =
                                                          androidx.compose.ui.text.style
                                                              .TextOverflow
                                                              .Ellipsis)
                                                  if (debugEntry.detail.isNotBlank()) {
                                                    Text(
                                                        text = debugEntry.detail,
                                                        style =
                                                            MaterialTheme.typography.bodySmall.copy(
                                                                fontSize = 10.sp),
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant)
                                                  }
                                                }
                                              }
                                        }
                                      }
                                  if (isInspectingUnindexed) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                      CircularProgressIndicator(
                                          modifier = Modifier.size(14.dp), strokeWidth = 1.8.dp)
                                      Spacer(modifier = Modifier.width(8.dp))
                                      Text(
                                          text = "Inspecting entries...",
                                          style = MaterialTheme.typography.labelSmall,
                                          color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                  }
                                }
                              },
                              dismissButton = {
                                TextButton(
                                    onClick =
                                        hapticOnClick {
                                          if (!isInspectingUnindexed) {
                                            semanticViewModel.inspectUnindexedEntries(
                                                allScreenshots)
                                          }
                                        }) {
                                      Text("Refresh")
                                    }
                              },
                              confirmButton = {
                                TextButton(onClick = { showUnindexedDialog = false }) {
                                  Text("Close")
                                }
                              })
                        }
                      }

                      Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick =
                                    hapticOnClick {
                                      if (isSemanticIndexing) {
                                        semanticViewModel.stopIndexing()
                                      } else {
                                        semanticViewModel.startIndexing(allScreenshots)
                                      }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            if (isSemanticIndexing)
                                                MaterialTheme.colorScheme.errorContainer
                                            else MaterialTheme.colorScheme.primaryContainer,
                                        contentColor =
                                            if (isSemanticIndexing)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else MaterialTheme.colorScheme.onPrimaryContainer),
                                contentPadding =
                                    PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                  Icon(
                                      imageVector =
                                          if (isSemanticIndexing) Icons.Rounded.Pause
                                          else Icons.Rounded.PlayArrow,
                                      contentDescription = null,
                                      modifier = Modifier.size(16.dp))
                                  Spacer(modifier = Modifier.width(6.dp))
                                  Text(
                                      text =
                                          if (isSemanticIndexing) {
                                            stringResource(R.string.pause)
                                          } else {
                                            stringResource(R.string.index_gallery)
                                          },
                                      style = MaterialTheme.typography.labelMedium,
                                      fontWeight = FontWeight.Bold)
                                }

                            IconButton(
                                onClick =
                                    hapticOnClick { semanticViewModel.resetEmbeddingsDatabase() },
                                modifier = Modifier.size(36.dp)) {
                                  Icon(
                                      imageVector = Icons.Rounded.DeleteSweep,
                                      contentDescription = stringResource(R.string.clear_index),
                                      tint = MaterialTheme.colorScheme.error)
                                }
                          }
                    }

                if (semanticIndexingProgress != null || isSemanticIndexing) {
                  val (completed, total) = semanticIndexingProgress ?: Pair(0, 0)
                  val progress = if (total > 0) completed.toFloat() / total.toFloat() else 0f
                  Spacer(modifier = Modifier.height(12.dp))
                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                          Text(
                              text = stringResource(R.string.scanning_layout_vectors),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant)
                          Text(
                              text = "${(progress * 100).toInt()}% ($completed/$total)",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.primary,
                              fontWeight = FontWeight.Bold)
                        }
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
      }

      if (showHfLogin) {
        val loginRepoUrl =
            when (hfLoginModelType) {
              ModelType.GEMMA_3N -> "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
              ModelType.GEMMA_4 ->
                  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
              ModelType.GGUF -> ""
              ModelType.AICORE -> ""
            }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
              showHfLogin = false
              val cookies =
                  android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
              isHfLoggedIn = !cookies.isNullOrEmpty()
              if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
                val url = if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl else gemma4Url
                viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
              }
            },
            properties =
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
          Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
              Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                      text = "Hugging Face Authorization",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                  )
                  IconButton(
                      onClick =
                          hapticOnClick {
                            showHfLogin = false
                            val cookies =
                                android.webkit.CookieManager.getInstance()
                                    .getCookie("https://huggingface.co")
                            isHfLoggedIn = !cookies.isNullOrEmpty()
                            if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
                              val url =
                                  if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl
                                  else gemma4Url
                              viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
                            }
                          },
                      modifier = Modifier) {
                        Icon(Icons.Rounded.Close, "Close")
                      }
                }

                Text(
                    text =
                        "Please log in and optionally accept the model's license agreement. Once done, close this window to begin downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()

                @SuppressLint("SetJavaScriptEnabled")
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                      android.webkit.WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = android.webkit.WebViewClient()
                        loadUrl(loginRepoUrl)
                      }
                    },
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
              }
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))

      // ===== Advanced Settings Card =====
      var isAdvancedExpanded by remember { mutableStateOf(false) }
      var showAdvancedConfirmDialog by remember { mutableStateOf(false) }
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          val interactionSource = remember {
            androidx.compose.foundation.interaction.MutableInteractionSource()
          }
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .clickable(
                          interactionSource = interactionSource,
                          indication = null,
                          onClick = hapticOnClick { isAdvancedExpanded = !isAdvancedExpanded }),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF2D1F3D) else Color(0xFFF3E5F5),
                modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Rounded.Tune,
                    null,
                    tint = if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2),
                    modifier = Modifier.size(20.dp))
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                  stringResource(R.string.advanced_settings),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Medium,
              )
              Text(
                  stringResource(R.string.advanced_settings_desc),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }
            Icon(
                imageVector =
                    if (isAdvancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          androidx.compose.animation.AnimatedVisibility(
              visible = isAdvancedExpanded,
              enter =
                  androidx.compose.animation.expandVertically() +
                      androidx.compose.animation.fadeIn(),
              exit =
                  androidx.compose.animation.shrinkVertically() +
                      androidx.compose.animation.fadeOut(),
          ) {
            Column {
              Spacer(modifier = Modifier.height(16.dp))

              // Image Resolution Setting Block
              val isAicoreSelected = selectedModel == ModelType.AICORE
              Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier =
                      Modifier.fillMaxWidth()
                          .then(if (isAicoreSelected) Modifier.alpha(0.5f) else Modifier)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                              Text(
                                  stringResource(R.string.image_resolution),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Bold,
                                  color = MaterialTheme.colorScheme.onSurface,
                              )
                              Spacer(modifier = Modifier.height(4.dp))
                              Text(
                                  stringResource(R.string.image_resolution_desc),
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                                  lineHeight = 16.sp,
                              )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer) {
                                  Text(
                                      "${localResolution}px",
                                      style = MaterialTheme.typography.labelMedium,
                                      fontWeight = FontWeight.Bold,
                                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                                      modifier =
                                          Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                          }

                      Spacer(modifier = Modifier.height(8.dp))

                      val resOptions = remember { listOf(256, 512, 1024, 1536, 2048) }
                      Slider(
                          value = resOptions.indexOf(localResolution).coerceAtLeast(0).toFloat(),
                          onValueChange = { localResolution = resOptions[it.toInt()] },
                          valueRange = 0f..4f,
                          steps = 3,
                          modifier = Modifier.fillMaxWidth(),
                          enabled = !isAicoreSelected,
                      )
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        resOptions.forEach { res ->
                          Text(
                              res.toString(),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.outline)
                        }
                      }
                    }
                  }

              Spacer(modifier = Modifier.height(12.dp))

              // Instance Count Setting Block
              val isGgufSelected = selectedModel == ModelType.GGUF
              val isInstanceDisabled = isGgufSelected || isAicoreSelected
              Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier =
                      Modifier.fillMaxWidth()
                          .then(if (isInstanceDisabled) Modifier.alpha(0.5f) else Modifier)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                              Text(
                                  stringResource(R.string.analysis_instance_count),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Bold,
                                  color = MaterialTheme.colorScheme.onSurface,
                              )
                              Spacer(modifier = Modifier.height(4.dp))
                              Text(
                                  stringResource(R.string.analysis_instance_count_desc),
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                                  lineHeight = 16.sp,
                              )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer) {
                                  Text(
                                      if (isInstanceDisabled) "1x" else "${localInstanceCount}x",
                                      style = MaterialTheme.typography.labelMedium,
                                      fontWeight = FontWeight.Bold,
                                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                                      modifier =
                                          Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                          }

                      Spacer(modifier = Modifier.height(8.dp))

                      Slider(
                          value = localInstanceCount.toFloat(),
                          onValueChange = { localInstanceCount = it.toInt() },
                          valueRange = 1f..5f,
                          steps = 3,
                          modifier = Modifier.fillMaxWidth(),
                          enabled = !isInstanceDisabled)
                    }
                  }

              Spacer(modifier = Modifier.height(12.dp))

              // Token Length Setting Block
              val isTokenDisabled = isGgufSelected || isAicoreSelected
              Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier =
                      Modifier.fillMaxWidth()
                          .then(if (isTokenDisabled) Modifier.alpha(0.5f) else Modifier)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                              Text(
                                  stringResource(R.string.token_length),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Bold,
                                  color = MaterialTheme.colorScheme.onSurface,
                              )
                              Spacer(modifier = Modifier.height(4.dp))
                              Text(
                                  stringResource(R.string.token_length_desc),
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                                  lineHeight = 16.sp,
                              )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer) {
                                  Text(
                                      if (isTokenDisabled) "Auto" else "$localMaxTokens",
                                      style = MaterialTheme.typography.labelMedium,
                                      fontWeight = FontWeight.Bold,
                                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                                      modifier =
                                          Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                          }

                      Spacer(modifier = Modifier.height(8.dp))

                      val tokenOptions = remember { listOf(2048, 3072, 4096) }
                      Slider(
                          value = tokenOptions.indexOf(localMaxTokens).coerceAtLeast(0).toFloat(),
                          onValueChange = { localMaxTokens = tokenOptions[it.toInt()] },
                          valueRange = 0f..2f,
                          steps = 1,
                          modifier = Modifier.fillMaxWidth(),
                          enabled = !isTokenDisabled)
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        tokenOptions.forEach { tokens ->
                          Text(
                              tokens.toString(),
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.outline)
                        }
                      }
                    }
                  }

              Spacer(modifier = Modifier.height(16.dp))

              // Gallery Page Size Setting Block
              Surface(
                  onClick = { localShowPlayPause = !localShowPlayPause },
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                              Text(
                                  stringResource(R.string.gallery_page_size),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Bold,
                                  color = MaterialTheme.colorScheme.onSurface,
                              )
                              Spacer(modifier = Modifier.height(4.dp))
                              Text(
                                  stringResource(R.string.gallery_page_size_desc),
                                  style = MaterialTheme.typography.bodySmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                                  lineHeight = 16.sp,
                              )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer) {
                                  Text(
                                      "${localPageSize}",
                                      style = MaterialTheme.typography.labelMedium,
                                      fontWeight = FontWeight.Bold,
                                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                                      modifier =
                                          Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                }
                          }
                      Spacer(modifier = Modifier.height(8.dp))
                      Slider(
                          value = localPageSize.toFloat(),
                          onValueChange = { localPageSize = it.toInt() },
                          valueRange = 5f..50f,
                          modifier = Modifier.fillMaxWidth(),
                      )
                    }
                  }

              Spacer(modifier = Modifier.height(16.dp))

              // Play/Pause button toggle
              Surface(
                  onClick = hapticOnClick { localShowPlayPause = !localShowPlayPause },
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.play_pause_toggle_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.play_pause_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                      }
                      Spacer(modifier = Modifier.width(12.dp))
                      Switch(
                          checked = localShowPlayPause,
                          onCheckedChange = { localShowPlayPause = it })
                    }
                  }

              Spacer(modifier = Modifier.height(16.dp))

              // Background process toggle
              Surface(
                  onClick =
                      hapticOnClick {
                        if (!localBackgroundProcess) {
                          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED) {
                              notificationPermissionLauncher.launch(
                                  Manifest.permission.POST_NOTIFICATIONS)
                              localBackgroundProcess = true
                            } else {
                              localBackgroundProcess = true
                            }
                          } else {
                            localBackgroundProcess = true
                          }
                        } else {
                          localBackgroundProcess = false
                        }
                      },
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.background_process_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.background_process_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                      }
                      Spacer(modifier = Modifier.width(12.dp))
                      Switch(
                          checked = localBackgroundProcess,
                          onCheckedChange = { checked ->
                            if (checked) {
                              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED) {
                                  notificationPermissionLauncher.launch(
                                      Manifest.permission.POST_NOTIFICATIONS)
                                  localBackgroundProcess = true
                                } else {
                                  localBackgroundProcess = true
                                }
                              } else {
                                localBackgroundProcess = true
                              }
                            } else {
                              localBackgroundProcess = false
                            }
                          })
                    }
                  }

              Spacer(modifier = Modifier.height(16.dp))

              Button(
                  onClick = hapticOnClick { showAdvancedConfirmDialog = true },
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(12.dp),
              ) {
                Text(stringResource(R.string.apply))
              }
            }
          }
        }
      }

      if (showAdvancedConfirmDialog) {
        val dialogTitle = stringResource(R.string.confirm_changes)
        val dialogText = stringResource(R.string.advanced_settings_warning)
        val confirmBtnText = stringResource(R.string.confirm_btn)
        val cancelBtnText = stringResource(R.string.cancel_btn)

        var countdown by remember { mutableIntStateOf(5) }
        LaunchedEffect(showAdvancedConfirmDialog) {
          countdown = 5
          while (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
          }
        }
        AlertDialog(
            onDismissRequest = { showAdvancedConfirmDialog = false },
            title = { Text(dialogTitle) },
            text = {
              Text(text = dialogText, textAlign = androidx.compose.ui.text.style.TextAlign.Justify)
            },
            confirmButton = {
              Button(
                  onClick =
                      hapticOnClick {
                        viewModel.setImageResolution(localResolution)
                        viewModel.setAnalysisInstanceCount(localInstanceCount)
                        viewModel.setShowPlayPauseToggle(localShowPlayPause)
                        viewModel.setBackgroundProcessEnabled(localBackgroundProcess)
                        viewModel.setMaxTokens(localMaxTokens)
                        viewModel.setGalleryPageSize(localPageSize)
                        viewModel.applyAdvancedSettings()
                        showAdvancedConfirmDialog = false
                      },
                  enabled = countdown == 0) {
                    Text(if (countdown > 0) "$confirmBtnText ($countdown)" else confirmBtnText)
                  }
            },
            dismissButton = {
              TextButton(
                  onClick =
                      hapticOnClick {
                        localResolution = savedResolution
                        localInstanceCount = savedInstanceCount
                        localShowPlayPause = savedShowPlayPause
                        localBackgroundProcess = savedBackgroundProcess
                        localMaxTokens = savedMaxTokens
                        localPageSize = savedPageSize
                        showAdvancedConfirmDialog = false
                      }) {
                    Text(cancelBtnText)
                  }
            })
      }

      Spacer(modifier = Modifier.height(16.dp))

      // ===== External Data Sync Card =====
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF1A3333) else Color(0xFFE0F7FA),
                modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Rounded.SwapHoriz,
                    null,
                    tint = if (isDark) Color(0xFF80CBC4) else Color(0xFF00695C),
                    modifier = Modifier.size(20.dp))
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                  stringResource(R.string.external_data_sync_title),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Medium,
              )
              Text(
                  stringResource(R.string.external_data_sync_subtitle),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }

            // Sync status badge
            val badgeText =
                if (jsonSaveFolderUri != null)
                    stringResource(R.string.external_data_sync_status_active)
                else stringResource(R.string.external_data_sync_status_internal)
            val badgeBg =
                if (jsonSaveFolderUri != null) {
                  if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)
                } else {
                  if (isDark) Color(0xFF3E1F1F) else Color(0xFFFFEBEE)
                }
            val badgeColor =
                if (jsonSaveFolderUri != null) {
                  if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                } else {
                  if (isDark) Color(0xFFE57373) else Color(0xFFC62828)
                }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = badgeBg,
            ) {
              Text(
                  text = badgeText,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = badgeColor,
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          if (jsonSaveFolderUri == null) {
            // Unconnected State
            Text(
                stringResource(R.string.external_data_sync_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick =
                    hapticOnClick {
                      try {
                        jsonFolderPickerLauncher.launch(null)
                      } catch (e: Exception) {
                        e.printStackTrace()
                      }
                    },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
              Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.external_data_sync_select_folder))
            }
          } else {
            // Connected State
            Column {
              Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                          Icon(
                              Icons.Rounded.Folder,
                              null,
                              tint = MaterialTheme.colorScheme.primary,
                              modifier = Modifier.size(24.dp))
                          Spacer(modifier = Modifier.width(10.dp))
                          Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text =
                                    jsonSaveFolderName
                                        ?: stringResource(
                                            R.string.external_data_sync_selected_folder),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.external_data_sync_last_saved,
                                        jsonLastBackupTime
                                            ?: stringResource(
                                                R.string.external_data_sync_last_saved_pending)),
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                          }
                          IconButton(
                              onClick =
                                  hapticOnClick {
                                    try {
                                      jsonFolderPickerLauncher.launch(null)
                                    } catch (e: Exception) {
                                      e.printStackTrace()
                                    }
                                  },
                              modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription =
                                        stringResource(
                                            R.string.external_data_sync_change_folder_desc),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                              }
                        }
                  }

              Spacer(modifier = Modifier.height(12.dp))

              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = hapticOnClick { viewModel.syncWithExternalFolder() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)) {
                          Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(18.dp))
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(stringResource(R.string.external_data_sync_sync_now))
                        }

                    OutlinedButton(
                        onClick = hapticOnClick { viewModel.setJsonSaveFolderUri(null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error),
                        border =
                            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(vertical = 12.dp)) {
                          Icon(Icons.Rounded.LinkOff, null, modifier = Modifier.size(18.dp))
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(stringResource(R.string.external_data_sync_disconnect))
                        }
                  }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // ===== Feedback Card =====
      val uriHandler = LocalUriHandler.current
      Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF3B1929) else Color(0xFFFCE4EC),
                modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Rounded.Feedback,
                    null,
                    tint = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828),
                    modifier = Modifier.size(20.dp))
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                  stringResource(R.string.feedback),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Medium,
              )
              Text(
                  stringResource(R.string.feedback_desc),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              )
            }
          }
          Spacer(modifier = Modifier.height(16.dp))

          FilledTonalButton(
              onClick = hapticOnClick { uriHandler.openUri("https://forms.gle/KR2AsC5VifMP2WkQ9") },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(vertical = 14.dp),
          ) {
            Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.submit_feedback), fontWeight = FontWeight.Medium)
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Buy Me a Coffee
      Surface(
          onClick = hapticOnClick { uriHandler.openUri("https://buymeacoffee.com/derykcihc") },
          modifier =
              Modifier.fillMaxWidth().padding(horizontal = 16.dp).graphicsLayer {
                shadowElevation = 0.dp.toPx()
                shape = RoundedCornerShape(24.dp)
                clip = true
              },
          shape = RoundedCornerShape(24.dp),
          color = Color(0xFFFFDD00), // BMC Yellow
          shadowElevation = 0.dp,
      ) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
              Icons.Rounded.Coffee,
              contentDescription = null,
              tint = Color(0xFF0D1B2A),
              modifier = Modifier.size(24.dp),
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
              "Buy me a coffee",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.ExtraBold,
              color = Color(0xFF0D1B2A),
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(
          text = stringResource(R.string.donate_desc),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
          modifier = Modifier.padding(horizontal = 32.dp),
          lineHeight = 18.sp,
      )

      Spacer(modifier = Modifier.height(24.dp))

      val annotatedString = buildAnnotatedString {
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
          append(stringResource(R.string.have_bugs_text))
        }
        pushStringAnnotation(tag = "github", annotation = "https://github.com/deRykcihC/skarmetoo")
        withStyle(
            style =
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline)) {
              append(stringResource(R.string.github_repo_link))
            }
        pop()
      }

      Box(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          contentAlignment = Alignment.Center,
      ) {
        @Suppress("DEPRECATION")
        androidx.compose.foundation.text.ClickableText(
            text = annotatedString,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            onClick =
                hapticOnClick { offset ->
                  annotatedString
                      .getStringAnnotations(tag = "github", start = offset, end = offset)
                      .firstOrNull()
                      ?.let { annotation -> uriHandler.openUri(annotation.item) }
                },
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Privacy Policy Link
      TextButton(
          onClick =
              hapticOnClick {
                uriHandler.openUri(
                    "https://github.com/deRykcihC/skarmetoo/blob/master/PRIVACY_POLICY.md")
              },
          modifier = Modifier.align(Alignment.CenterHorizontally),
      ) {
        Text(
            stringResource(R.string.privacy_policy),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
        )
      }

      Spacer(modifier = Modifier.height(24.dp))
    }
  }

  if (showMediaFolderDialog) {
    val dlgTitle = stringResource(R.string.select_media_folders)
    val dlgNoFolders = stringResource(R.string.no_media_folders_found)
    val dlgDeselectAll = stringResource(R.string.deselect_all)
    val dlgDone = stringResource(R.string.done)

    var tempSelectedAlbums by
        remember(availableAlbums) { mutableStateOf(selectedAlbums.toMutableSet()) }

    AlertDialog(
        onDismissRequest = { showMediaFolderDialog = false },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
          Text(
              text = dlgTitle,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
          )
        },
        text = {
          Column(
              modifier =
                  Modifier.fillMaxWidth()
                      .heightIn(max = 400.dp)
                      .verticalScroll(rememberScrollState())) {
                if (availableAlbums.isEmpty()) {
                  Text(
                      text = dlgNoFolders,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else {
                  availableAlbums.forEach { album ->
                    val isSelected = album.bucketId in tempSelectedAlbums
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        color =
                            if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else Color.Transparent,
                        onClick =
                            hapticOnClick {
                              tempSelectedAlbums =
                                  tempSelectedAlbums.toMutableSet().apply {
                                    if (album.bucketId in this) remove(album.bucketId)
                                    else add(album.bucketId)
                                  }
                            },
                    ) {
                      Row(
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { _ ->
                              tempSelectedAlbums =
                                  tempSelectedAlbums.toMutableSet().apply {
                                    if (album.bucketId in this) remove(album.bucketId)
                                    else add(album.bucketId)
                                  }
                            },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                          Text(
                              text = album.name,
                              maxLines = 1,
                              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                              style = MaterialTheme.typography.bodyMedium,
                              fontWeight = FontWeight.Medium,
                          )
                          Text(
                              text = "${album.count} images",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                    }
                  }
                }
              }
        },
        confirmButton = {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            TextButton(onClick = hapticOnClick { tempSelectedAlbums = mutableSetOf<String>() }) {
              Text(dlgDeselectAll)
            }
            Button(
                onClick =
                    hapticOnClick {
                      val newlySelected = tempSelectedAlbums - selectedAlbums
                      if (newlySelected.isNotEmpty()) {
                        autoIndexPending = true
                      }
                      viewModel.applySelectedMediaAlbums(tempSelectedAlbums)
                      showMediaFolderDialog = false
                    }) {
                  Text(dlgDone)
                }
          }
        },
    )
  }
}

@Composable
private fun DetailLevelCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  OutlinedCard(
      onClick = { if (enabled) onClick() },
      modifier = modifier.height(52.dp).then(if (!enabled) Modifier.alpha(0.5f) else Modifier),
      shape = RoundedCornerShape(12.dp),
      colors =
          CardDefaults.outlinedCardColors(
              containerColor =
                  if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
              contentColor =
                  if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                  else MaterialTheme.colorScheme.onSurface,
          ),
      border =
          if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
          else CardDefaults.outlinedCardBorder(),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
          maxLines = 1,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
    }
  }
}
