package com.deryk.skarmetoo.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_MODEL
import com.deryk.skarmetoo.ai.LlmManager
import com.deryk.skarmetoo.network.LeaderboardEntry
import com.deryk.skarmetoo.network.LeaderboardMetric
import com.deryk.skarmetoo.network.LeaderboardModel
import com.deryk.skarmetoo.ui.components.SortOrderIcon
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.components.rememberSquigglePillShape
import com.deryk.skarmetoo.ui.theme.AppMotion
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import com.deryk.skarmetoo.viewmodel.AnalysisBenchmarkState
import com.deryk.skarmetoo.viewmodel.ModelType
import com.deryk.skarmetoo.viewmodel.ProcessingTimeSample
import com.deryk.skarmetoo.viewmodel.ResourceUsageSample
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val useWidePreviewFrame: Boolean = false,
    val useFixedPreviewFrameHeight: Boolean = false,
    val previewFrameHeight: androidx.compose.ui.unit.Dp? = null,
    val content: @Composable () -> Unit
)

data class OnboardingSection(
    val title: String,
    val icon: ImageVector,
    val pages: List<OnboardingPage>,
)

@OptIn(
    ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(viewModel: ScreenshotViewModel, onFinish: () -> Unit) {
  val isDark = LocalIsDarkMode.current
  val context = LocalContext.current

  val selectedModel by viewModel.selectedModel.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val isModelFound by viewModel.isModelFound.collectAsState()
  var modelOutlinePulse by remember { mutableStateOf<ModelType?>(null) }
  var modelOutlinePulseVersion by remember { mutableStateOf(0) }
  fun startModelOutlinePulse(model: ModelType) {
    modelOutlinePulse = model
    modelOutlinePulseVersion += 1
  }
  LaunchedEffect(modelOutlinePulseVersion) {
    if (modelOutlinePulse != null) {
      delay(600L)
      modelOutlinePulse = null
    }
  }
  val isDownloadingModel by viewModel.isDownloadingModel.collectAsState()
  val downloadingModelType by viewModel.downloadingModelType.collectAsState()
  val downloadProgress by viewModel.downloadProgress.collectAsState()
  val isGemma3nDownloaded by viewModel.isGemma3nDownloaded.collectAsState()
  val isGemma4Downloaded by viewModel.isGemma4Downloaded.collectAsState()
  val selectedAlbums by viewModel.selectedAlbums.collectAsState()
  val availableAlbums by viewModel.availableAlbums.collectAsState()
  val analysisLang by viewModel.analysisLanguage.collectAsState()
  val currentDetailLevel by viewModel.detailLevel.collectAsState()

  LaunchedEffect(selectedModel) {
    if (selectedModel == ModelType.GGUF) {
      viewModel.setAnalysisLanguage("en")
      if (currentDetailLevel == LlmManager.DetailLevel.COMPREHENSIVE) {
        viewModel.setDetailLevel(LlmManager.DetailLevel.DETAILED)
      }
    }
  }

  var showMoreModels by remember { mutableStateOf(false) }
  val ggufManager = remember { GgufLlmManager.getInstance(context) }
  val isGgufDownloading by ggufManager.isDownloading.collectAsState()
  val ggufDownloadProgress by ggufManager.downloadProgress.collectAsState()
  val ggufDownloadingModelName by ggufManager.downloadingModelName.collectAsState()
  val activeGgufModel by ggufManager.activeModelInfo.collectAsState()
  val isLfmDownloaded by
      produceState(initialValue = ggufManager.isModelDownloaded(LFM2_5_MODEL), isGgufDownloading) {
        value = ggufManager.isModelDownloaded(LFM2_5_MODEL)
      }

  var showHfLogin by remember { mutableStateOf(false) }
  var hfLoginModelType by remember { mutableStateOf(ModelType.GEMMA_3N) }

  val gemma3nUrl =
      "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"
  val gemma4Url =
      "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm"

  val pages =
      listOf(
          // ── Page 0: Download AI Model ──
          OnboardingPage(
              title = stringResource(R.string.select_model),
              description = stringResource(R.string.onboarding_page0_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 250.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                  val isGemma4Selected = selectedModel == ModelType.GEMMA_4 && isGemma4Downloaded
                  val isDownloadingGemma4 =
                      isDownloadingModel && downloadingModelType == ModelType.GEMMA_4
                  OnboardingSquiggleCard(
                      loading =
                          modelOutlinePulse == ModelType.GEMMA_4 ||
                              (selectedModel == ModelType.GEMMA_4 && !isModelReady &&
                                  (isModelFound || isDownloadingModel)) || isDownloadingGemma4,
                      onClick =
                          hapticOnClick {
                            startModelOutlinePulse(ModelType.GEMMA_4)
                            viewModel.setSelectedModel(ModelType.GEMMA_4)
                            if (isGemma4Downloaded) {
                              val path =
                                  context.filesDir.absolutePath + "/" + ModelType.GEMMA_4.fileName
                              viewModel.initializeModel(path, isGemma4 = true)
                            } else if (!isDownloadingModel) {
                              hfLoginModelType = ModelType.GEMMA_4
                              showHfLogin = true
                            }
                          },
                      modifier = Modifier.fillMaxWidth(),
                      cornerRadius = 14.dp,
                      colors =
                          CardDefaults.outlinedCardColors(
                              containerColor =
                                  if (isGemma4Selected) MaterialTheme.colorScheme.secondaryContainer
                                  else Color.Transparent,
                          ),
                      border =
                          if (isGemma4Selected)
                              BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                          else CardDefaults.outlinedCardBorder(),
                  ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                          Text(
                              stringResource(R.string.model_gemma4),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight =
                                  if (isGemma4Selected) FontWeight.Bold else FontWeight.Medium,
                              maxLines = 1,
                              overflow = TextOverflow.Ellipsis,
                          )
                          Surface(
                              color = (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9)),
                              shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = stringResource(R.string.recommended_tag),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = (if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                              }
                          Surface(
                              color = MaterialTheme.colorScheme.surfaceVariant,
                              shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = stringResource(R.string.tags_tag),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
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
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
                                  if (isDownloadingGemma4) {
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp),
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
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

                  val isLfmSelected =
                      selectedModel == ModelType.GGUF &&
                          activeGgufModel?.fileName == LFM2_5_MODEL.fileName
                  val isDownloadingLfm =
                      isGgufDownloading && ggufDownloadingModelName == LFM2_5_MODEL.displayName

                  OnboardingSquiggleCard(
                      loading =
                          modelOutlinePulse == ModelType.GGUF ||
                              (isLfmSelected && !isModelReady &&
                                  (isModelFound || isDownloadingModel)) || isDownloadingLfm,
                      onClick =
                          hapticOnClick {
                            startModelOutlinePulse(ModelType.GGUF)
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
                      cornerRadius = 14.dp,
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
                              stringResource(R.string.model_lfm_title),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight =
                                  if (isLfmSelected) FontWeight.Bold else FontWeight.Medium,
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
                              if (isDownloadingLfm)
                                  (if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0))
                              else if (isLfmDownloaded)
                                  (if (isDark) Color(0xFF1B3B1B) else Color(0xFFE8F5E9))
                              else MaterialTheme.colorScheme.surfaceContainerHighest,
                          modifier = Modifier.size(32.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
                                  if (isDownloadingLfm) {
                                    Text(
                                        text = "${(ggufDownloadProgress * 100).toInt()}%",
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp),
                                        fontWeight = FontWeight.Bold,
                                        color =
                                            if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
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
                }
              },

          // ── Page 1: Settings Toolbar ──
          OnboardingPage(
              title = stringResource(R.string.settings),
              description = stringResource(R.string.onboarding_page1_desc)) {
                // Exact header row from SettingsScreen with CompositionLocalProvider
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                      stringResource(R.string.settings),
                      modifier = Modifier.weight(1f),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      style = MaterialTheme.typography.headlineSmall,
                      fontWeight = FontWeight.Bold,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        modifier = Modifier.requiredWidth(156.dp).height(42.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(4.dp),
                        expandedShadowElevation = 0.dp,
                        colors =
                            FloatingToolbarDefaults.standardFloatingToolbarColors(
                                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                      Row(
                          horizontalArrangement = Arrangement.spacedBy(4.dp),
                          verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp), shape = CircleShape) {
                                  Icon(
                                      Icons.Rounded.MenuBook,
                                      contentDescription = "Tutorial",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick { viewModel.setDarkMode(!isDark) },
                                modifier = Modifier.size(34.dp), shape = CircleShape) {
                                  Icon(
                                      if (isDark) Icons.Rounded.LightMode
                                      else Icons.Rounded.DarkMode,
                                      contentDescription = "Toggle Dark Mode",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp), shape = CircleShape) {
                                  Icon(
                                      Icons.Rounded.Monitor,
                                      contentDescription = "Screen Saver",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp), shape = CircleShape) {
                                  Icon(
                                      Icons.Rounded.Language,
                                      contentDescription = "Language",
                                      modifier = Modifier.size(20.dp))
                                }
                          }
                    }
                  }
                }
              },

          // ── Page 2: Analysis Language ──
          OnboardingPage(
              title = stringResource(R.string.language),
              description = stringResource(R.string.onboarding_page2_desc)) {
                var showMoreLanguages by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                  Text(
                      stringResource(R.string.language),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(modifier = Modifier.height(6.dp))
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    // "English" selected
                    OnboardingSquiggleCard(
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("en")
                              showMoreLanguages = false
                            },
                        modifier = Modifier.weight(1f).height(52.dp),
                        cornerRadius = 12.dp,
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor =
                                    if (analysisLang == "en")
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent,
                                contentColor =
                                    if (analysisLang == "en")
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                        border =
                            if (analysisLang == "en")
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else CardDefaults.outlinedCardBorder(),
                    ) {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = stringResource(R.string.language_en),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight =
                                if (analysisLang == "en") FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                    // "More"
                    val moreLabel =
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
                        }
                    val isMoreSelected = analysisLang != "en"
                    OnboardingSquiggleCard(
                        onClick = hapticOnClick { showMoreLanguages = !showMoreLanguages },
                        modifier = Modifier.weight(1f).height(52.dp),
                        cornerRadius = 12.dp,
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor =
                                    if (isMoreSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent,
                                contentColor =
                                    if (isMoreSelected)
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                        border =
                            if (isMoreSelected)
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else CardDefaults.outlinedCardBorder(),
                    ) {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = moreLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isMoreSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                  }

                  androidx.compose.animation.AnimatedVisibility(
                      visible = showMoreLanguages,
                      enter =
                          androidx.compose.animation.expandVertically(
                              animationSpec = AppMotion.spatial()) +
                              androidx.compose.animation.fadeIn(
                                  animationSpec = AppMotion.effects()),
                      exit =
                          androidx.compose.animation.shrinkVertically(
                              animationSpec = AppMotion.linear(240)) +
                              androidx.compose.animation.fadeOut(
                                  animationSpec = AppMotion.linear(160)),
                  ) {
                    Column {
                      Spacer(modifier = Modifier.height(8.dp))
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Chinese
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("zh-rTW")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "zh-rTW")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "zh-rTW")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "zh-rTW")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_zh),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "zh-rTW") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                            // Hindi
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("hi")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "hi")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "hi")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "hi")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_hi),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "hi") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                            // Spanish
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("es")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "es")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "es")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "es")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_es),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "es") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                          }
                      Spacer(modifier = Modifier.height(8.dp))
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Arabic
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("ar")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "ar")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "ar")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "ar")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_ar),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "ar") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                            // French
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("fr")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "fr")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "fr")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "fr")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_fr),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "fr") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                            // Russian
                            OnboardingSquiggleCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("ru")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                cornerRadius = 12.dp,
                                colors =
                                    CardDefaults.outlinedCardColors(
                                        containerColor =
                                            if (analysisLang == "ru")
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent,
                                        contentColor =
                                            if (analysisLang == "ru")
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    ),
                                border =
                                    if (analysisLang == "ru")
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    else CardDefaults.outlinedCardBorder(),
                            ) {
                              Box(
                                  contentAlignment = Alignment.Center,
                                  modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.language_ru),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight =
                                            if (analysisLang == "ru") FontWeight.Bold
                                            else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                  }
                            }
                          }
                    }
                  }
                }
              },

          // ── Page 3: Analysis Detail ──
          OnboardingPage(
              title = stringResource(R.string.analysis_detail),
              description = stringResource(R.string.onboarding_page3_desc)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                  Text(
                      stringResource(R.string.analysis_detail),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(modifier = Modifier.height(6.dp))
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      // Brief
                      val isBriefSelected = currentDetailLevel == LlmManager.DetailLevel.BRIEF
                      OnboardingSquiggleCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.BRIEF)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          cornerRadius = 12.dp,
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor =
                                      if (isBriefSelected)
                                          MaterialTheme.colorScheme.secondaryContainer
                                      else Color.Transparent,
                                  contentColor =
                                      if (isBriefSelected)
                                          MaterialTheme.colorScheme.onSecondaryContainer
                                      else MaterialTheme.colorScheme.onSurface,
                              ),
                          border =
                              if (isBriefSelected)
                                  BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                              else CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.brief),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight =
                                      if (isBriefSelected) FontWeight.Bold else FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                      // Detailed
                      val isDetailedSelected = currentDetailLevel == LlmManager.DetailLevel.DETAILED
                      OnboardingSquiggleCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.DETAILED)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          cornerRadius = 12.dp,
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor =
                                      if (isDetailedSelected)
                                          MaterialTheme.colorScheme.secondaryContainer
                                      else Color.Transparent,
                                  contentColor =
                                      if (isDetailedSelected)
                                          MaterialTheme.colorScheme.onSecondaryContainer
                                      else MaterialTheme.colorScheme.onSurface,
                              ),
                          border =
                              if (isDetailedSelected)
                                  BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                              else CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.detailed),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight =
                                      if (isDetailedSelected) FontWeight.Bold
                                      else FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      // Comprehensive
                      val isComprehensiveSelected =
                          currentDetailLevel == LlmManager.DetailLevel.COMPREHENSIVE
                      val isComprehensiveEnabled = selectedModel != ModelType.GGUF
                      OnboardingSquiggleCard(
                          onClick =
                              hapticOnClick {
                                if (isComprehensiveEnabled) {
                                  viewModel.setDetailLevel(LlmManager.DetailLevel.COMPREHENSIVE)
                                }
                              },
                          modifier =
                              Modifier.weight(1f)
                                  .height(52.dp)
                                  .then(
                                      if (isComprehensiveEnabled) Modifier
                                      else Modifier.alpha(0.5f)),
                          cornerRadius = 12.dp,
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor =
                                      if (isComprehensiveSelected)
                                          MaterialTheme.colorScheme.secondaryContainer
                                      else Color.Transparent,
                                  contentColor =
                                      if (isComprehensiveSelected)
                                          MaterialTheme.colorScheme.onSecondaryContainer
                                      else MaterialTheme.colorScheme.onSurface,
                              ),
                          border =
                              if (isComprehensiveSelected)
                                  BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                              else CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.full),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight =
                                      if (isComprehensiveSelected) FontWeight.Bold
                                      else FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                      // Custom
                      val isCustomSelected = currentDetailLevel == LlmManager.DetailLevel.CUSTOM
                      OnboardingSquiggleCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.CUSTOM)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          cornerRadius = 12.dp,
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor =
                                      if (isCustomSelected)
                                          MaterialTheme.colorScheme.secondaryContainer
                                      else Color.Transparent,
                                  contentColor =
                                      if (isCustomSelected)
                                          MaterialTheme.colorScheme.onSecondaryContainer
                                      else MaterialTheme.colorScheme.onSurface,
                              ),
                          border =
                              if (isCustomSelected)
                                  BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                              else CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.custom),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight =
                                      if (isCustomSelected) FontWeight.Bold else FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                    }
                  }
                }
              },

          // ── Page 4: Select Image Folders ──
          OnboardingPage(
              title = stringResource(R.string.add_folder),
              description = stringResource(R.string.onboarding_page4_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 130.dp) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 6.dp)) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) Color(0xFF3E2A15) else Color(0xFFFFF3E0),
                            modifier = Modifier.size(40.dp),
                        ) {
                          Box(
                              contentAlignment = Alignment.Center,
                              modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Rounded.FolderOpen,
                                    null,
                                    tint = if (isDark) Color(0xFFFFAB40) else Color(0xFFE65100),
                                    modifier = Modifier.size(22.dp))
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
                              stringResource(
                                  R.string.folders_selected, selectedAlbums.size.toString()),
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                      (hsv[1] * 1.1f).coerceIn(0.5f, 0.9f),
                                      (hsv[2] * 1.2f).coerceIn(0.7f, 1.0f))
                              if (i % 2 != 0) {
                                newHsv[1] *= 0.75f
                                newHsv[2] *= 0.85f
                              }
                              Color(android.graphics.Color.HSVToColor(newHsv))
                            }
                          }

                      Row(verticalAlignment = Alignment.CenterVertically) {
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
                          // Mock bar
                          Surface(
                              modifier = Modifier.weight(1f).height(16.dp),
                              shape = RoundedCornerShape(8.dp),
                              color =
                                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {}
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
                                    // Note: In onboarding we might need a dialog too if we want it
                                    // interactive
                                    // But for now we just mirror the UI.
                                  } else {
                                    // Request permission if needed
                                  }
                                },
                            modifier =
                                Modifier.size(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(
                                            alpha = 0.7f),
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
                    }
              },

          // -- Page 5: Current Analysis Status --
          OnboardingPage(
              title = stringResource(R.string.onboarding_current_analysis_title),
              description = stringResource(R.string.onboarding_current_analysis_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 190.dp) {
                OnboardingCurrentAnalysisPreview()
              },

          // ── Page 6: Share Output ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page6_title),
              description = stringResource(R.string.onboarding_page6_desc)) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                  val previewScale = (maxWidth / 320.dp).coerceAtMost(1f)
                  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(
                        modifier = Modifier.width(320.dp * previewScale).align(Alignment.Center).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp * previewScale, Alignment.CenterHorizontally),
                    ) {
                      Surface(
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.primary,
                          contentColor = MaterialTheme.colorScheme.onPrimary,
                      ) {
                        IconButton(
                            onClick = hapticOnClick {},
                            modifier = Modifier.size(44.dp * previewScale),
                            shape = CircleShape,
                        ) {
                          Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(20.dp * previewScale))
                        }
                      }
                      ButtonGroup(
                          modifier = Modifier.width(144.dp * previewScale),
                          expandedRatio = 0.12f,
                          horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                      ) {
                        val icons = listOf(Icons.Rounded.Style, Icons.AutoMirrored.Rounded.OpenInNew, Icons.Rounded.Share)
                        val descriptions = listOf("Generate Share Card", stringResource(R.string.open_original_screenshot), "Share Original")
                        icons.forEachIndexed { index, icon ->
                          val interactionSource = remember { MutableInteractionSource() }
                          FilledTonalButton(
                              onClick = hapticOnClick {},
                              modifier = Modifier.weight(1f).height(52.dp * previewScale).animateWidth(interactionSource),
                              interactionSource = interactionSource,
                              contentPadding = PaddingValues(0.dp),
                              shapes = ButtonShapes(
                                  shape = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShape
                                    1 -> ShapeDefaults.Small
                                    else -> ButtonGroupDefaults.connectedTrailingButtonShape
                                  },
                                  pressedShape = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonPressShape
                                    1 -> ButtonGroupDefaults.connectedMiddleButtonPressShape
                                    else -> ButtonGroupDefaults.connectedTrailingButtonPressShape
                                  },
                              ),
                          ) {
                            Icon(icon, descriptions[index], modifier = Modifier.size(20.dp * previewScale))
                          }
                        }
                      }
                      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Surface(
                            modifier = Modifier.height(44.dp * previewScale)
                                .clip(CircleShape)
                                .combinedClickable(onClick = hapticOnClick {}, onDoubleClick = {}),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 1.6.dp * previewScale,
                        ) {
                          Row(
                              modifier = Modifier.padding(horizontal = 9.6.dp * previewScale),
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.spacedBy(4.8.dp * previewScale),
                          ) {
                            Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(14.4.dp * previewScale))
                            Text(
                                stringResource(R.string.done),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp * previewScale),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                          }
                        }
                      }
                    }
                  }
                }
              },

          // ── Page 7: Search & Filter ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page7_title),
              description = stringResource(R.string.onboarding_page7_desc)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                  // Search pill + sort/filter tags — matching the LegacyScreen layout
                  var isSortDescending by remember { mutableStateOf(true) }
                  LaunchedEffect(Unit) {
                    while (true) {
                      delay(800L)
                      isSortDescending = !isSortDescending
                    }
                  }
                  LazyRow(
                      modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                      horizontalArrangement =
                          Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                    item {
                      Surface(
                          shape = RoundedCornerShape(20.dp),
                          color = MaterialTheme.colorScheme.surfaceContainerHighest,
                      ) {
                        Row(
                            modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          Icon(
                              Icons.Rounded.Search,
                              null,
                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                              modifier = Modifier.size(20.dp),
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(
                              stringResource(R.string.search_placeholder),
                              style =
                                  MaterialTheme.typography.labelLarge.copy(
                                      fontWeight = FontWeight.SemiBold,
                                      color =
                                          MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                              alpha = 0.6f),
                                  ),
                              softWrap = false,
                              maxLines = 1,
                          )
                        }
                      }
                    }
                    item {
                      OnboardingSquiggleChip(
                          selected = true,
                          onClick = hapticOnClick {},
                          label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              SortOrderIcon(
                                  isSortDescending = isSortDescending,
                                  modifier = Modifier.size(16.dp),
                              )
                              Spacer(modifier = Modifier.width(4.dp))
                              Text(
                                  if (isSortDescending) stringResource(R.string.newest_first)
                                  else stringResource(R.string.oldest_first),
                                  fontWeight = FontWeight.Bold,
                              )
                            }
                          },
                          cornerRadius = 20.dp,
                          colors =
                              FilterChipDefaults.filterChipColors(
                                  selectedContainerColor =
                                      MaterialTheme.colorScheme.secondaryContainer,
                                  selectedLabelColor =
                                      MaterialTheme.colorScheme.onSecondaryContainer,
                                  selectedLeadingIconColor =
                                      MaterialTheme.colorScheme.onSecondaryContainer,
                              ),
                      )
                    }
                    items(listOf("cat", "indoor", "selfie", "food")) { tag ->
                      OnboardingSquiggleChip(
                          selected = tag == "cat",
                          onClick = hapticOnClick {},
                          label = {
                            Text(
                                tag,
                                fontWeight = FontWeight.SemiBold,
                            )
                          },
                          cornerRadius = 20.dp,
                      )
                    }
                  }
                }
              },

          // ── Page 7.1: Layout Pills (New) ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_layout_pills_title),
              description = stringResource(R.string.onboarding_layout_pills_desc)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                      var isGalleryStyle by remember { mutableStateOf(true) }
                      LaunchedEffect(Unit) {
                        while (true) {
                          delay(1800L)
                          isGalleryStyle = !isGalleryStyle
                        }
                      }

                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .height(110.dp)
                                  .clip(RoundedCornerShape(16.dp))
                                  .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                          contentAlignment = Alignment.Center) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = isGalleryStyle,
                                transitionSpec = {
                                  val direction = if (targetState) -1 else 1
                                  ((androidx.compose.animation.slideInHorizontally(
                                          animationSpec = AppMotion.timed(180), initialOffsetX = { it * direction }) +
                                      androidx.compose.animation.fadeIn(animationSpec = AppMotion.effects())) togetherWith
                                      (androidx.compose.animation.slideOutHorizontally(
                                          animationSpec = AppMotion.timed(140), targetOffsetX = { -it * direction }) +
                                      androidx.compose.animation.fadeOut(animationSpec = AppMotion.linear(160))))
                                      .using(
                                          androidx.compose.animation.SizeTransform(
                                              sizeAnimationSpec = { _, _ -> AppMotion.spatial() }))
                                },
                                label = "LayoutPreview") { isGallery ->
                                  if (isGallery) {
                                    Card(
                                        modifier = Modifier.width(180.dp).height(80.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors =
                                            CardDefaults.cardColors(
                                                containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant)) {
                                          Box(
                                              modifier = Modifier.fillMaxSize(),
                                              contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Rounded.Image,
                                                    null,
                                                    tint =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(32.dp))
                                              }
                                        }
                                  } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier =
                                                Modifier.size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant))
                                        Box(
                                            modifier =
                                                Modifier.size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant))
                                      }
                                      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier =
                                                Modifier.size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant))
                                        Box(
                                            modifier =
                                                Modifier.size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant))
                                      }
                                    }
                                  }
                                }
                          }

                      Spacer(modifier = Modifier.height(16.dp))

                      Surface(
                          modifier =
                              Modifier.width(120.dp)
                                  .border(
                                      width = 1.dp,
                                      color =
                                          MaterialTheme.colorScheme.outlineVariant.copy(
                                              alpha = 0.5f),
                                      shape = CircleShape),
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Box(modifier = Modifier.padding(4.dp).width(112.dp).height(36.dp)) {
                              val selectedOffset by
                                  androidx.compose.animation.core.animateDpAsState(
                                      targetValue = if (isGalleryStyle) 0.dp else 56.dp,
                                      animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
                                      label = "PillOffset")
                              Box(
                                  modifier =
                                      Modifier.offset(x = selectedOffset)
                                          .width(56.dp)
                                          .fillMaxHeight()
                                          .clip(CircleShape)
                                          .background(MaterialTheme.colorScheme.primary))

                              Row(
                                  modifier = Modifier.fillMaxSize(),
                                  verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier =
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .clip(CircleShape)
                                                .clickable(
                                                    onClick =
                                                        hapticOnClick { isGalleryStyle = true }),
                                        contentAlignment = Alignment.Center) {
                                          Icon(
                                              imageVector = Icons.Rounded.ViewQuilt,
                                              contentDescription = "Gallery Layout",
                                              tint =
                                                  if (isGalleryStyle)
                                                      MaterialTheme.colorScheme.onPrimary
                                                  else MaterialTheme.colorScheme.onSurfaceVariant,
                                              modifier = Modifier.size(18.dp))
                                        }
                                    Box(
                                        modifier =
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .clip(CircleShape)
                                                .clickable(
                                                    onClick =
                                                        hapticOnClick { isGalleryStyle = false }),
                                        contentAlignment = Alignment.Center) {
                                          Icon(
                                              imageVector = Icons.Rounded.GridView,
                                              contentDescription = "Grid Layout",
                                              tint =
                                                  if (!isGalleryStyle)
                                                      MaterialTheme.colorScheme.onPrimary
                                                  else MaterialTheme.colorScheme.onSurfaceVariant,
                                              modifier = Modifier.size(18.dp))
                                        }
                                  }
                            }
                          }
                    }
              },

          // ── Page 7.2: Collapsible Album Row (New) ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_hide_albums_title),
              description = stringResource(R.string.onboarding_hide_albums_desc)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                      var isAlbumRowVisible by remember { mutableStateOf(true) }
                      LaunchedEffect(Unit) {
                        while (true) {
                          delay(1800L)
                          isAlbumRowVisible = !isAlbumRowVisible
                        }
                      }

                      Row(
                          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                          horizontalArrangement =
                              Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                          verticalAlignment = Alignment.CenterVertically) {
                            OnboardingSquiggleChip(
                                selected = true,
                                onClick = hapticOnClick { isAlbumRowVisible = !isAlbumRowVisible },
                                label = {
                                  Icon(
                                      if (isAlbumRowVisible) Icons.Rounded.KeyboardArrowDown
                                      else Icons.Rounded.KeyboardArrowUp,
                                      contentDescription = null,
                                      modifier = Modifier.size(18.dp))
                                },
                                cornerRadius = 20.dp,
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor =
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor =
                                            MaterialTheme.colorScheme.onSecondaryContainer))
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                              Row(
                                  modifier = Modifier.height(32.dp).padding(horizontal = 12.dp),
                                  verticalAlignment = Alignment.CenterVertically,
                              ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.search_placeholder),
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color =
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.6f)),
                                    softWrap = false,
                                    maxLines = 1,
                                )
                              }
                            }
                          }

                      Box(
                          modifier = Modifier.fillMaxWidth().height(64.dp),
                          contentAlignment = Alignment.Center) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isAlbumRowVisible,
                                enter =
                                    androidx.compose.animation.expandVertically(
                                        animationSpec = AppMotion.spatial()) +
                                        androidx.compose.animation.fadeIn(
                                            animationSpec = AppMotion.effects()),
                                exit =
                                    androidx.compose.animation.shrinkVertically(
                                        animationSpec = AppMotion.linear(240)) +
                                        androidx.compose.animation.fadeOut(
                                            animationSpec = AppMotion.linear(160))) {
                                  Row(
                                      modifier = Modifier.fillMaxWidth(),
                                      horizontalArrangement =
                                          Arrangement.spacedBy(
                                              12.dp, Alignment.CenterHorizontally)) {
                                        repeat(3) {
                                          Card(
                                              modifier = Modifier.size(64.dp),
                                              shape = RoundedCornerShape(10.dp),
                                              colors =
                                                  CardDefaults.cardColors(
                                                      containerColor =
                                                          MaterialTheme.colorScheme
                                                              .surfaceContainerHighest)) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                                    contentAlignment = Alignment.BottomStart) {
                                                      Box(
                                                          modifier =
                                                              Modifier.fillMaxWidth()
                                                                  .height(10.dp)
                                                                  .background(
                                                                      MaterialTheme.colorScheme
                                                                          .onSurfaceVariant
                                                                          .copy(alpha = 0.2f),
                                                                      RoundedCornerShape(2.dp)))
                                                    }
                                              }
                                        }
                                      }
                                }
                          }
                    }
              },

          // -- Page 7.3: Swipe Down To Expand Album Row --
          OnboardingPage(
              title = stringResource(R.string.onboarding_expand_albums_title),
              description = stringResource(R.string.onboarding_expand_albums_desc)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                      var isAlbumDrawerExpanded by remember { mutableStateOf(false) }
                      LaunchedEffect(Unit) {
                        while (true) {
                          delay(700L)
                          isAlbumDrawerExpanded = true
                          delay(1300L)
                          isAlbumDrawerExpanded = false
                          delay(500L)
                        }
                      }
                      val drawerHeight by
                          animateDpAsState(
                              targetValue = if (isAlbumDrawerExpanded) 196.dp else 96.dp,
                              animationSpec = if (isAlbumDrawerExpanded) AppMotion.spatial() else AppMotion.linear(240),
                              label = "OnboardingAlbumDrawerHeight")
                      val swipeCueOffset by
                          animateFloatAsState(
                              targetValue = if (isAlbumDrawerExpanded) 34f else 0f,
                              animationSpec = AppMotion.spatial(),
                              label = "OnboardingAlbumSwipeCueOffset")

                      Box(
                          modifier = Modifier.fillMaxWidth().height(216.dp),
                          contentAlignment = Alignment.TopCenter) {
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(drawerHeight)
                                        .clip(RoundedCornerShape(12.dp))) {
                                  if (isAlbumDrawerExpanded) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                          repeat(2) { row ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(
                                                        12.dp, Alignment.CenterHorizontally)) {
                                                  repeat(3) { col ->
                                                    OnboardingAlbumThumbnailCard(
                                                        isSelected = row == 0 && col == 0,
                                                        isPinned = row == 0 && col == 0,
                                                        size = 64.dp, animateSelection = false)
                                                  }
                                                }
                                          }
                                        }
                                  } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(
                                                12.dp, Alignment.CenterHorizontally)) {
                                          repeat(3) { index ->
                                            OnboardingAlbumThumbnailCard(
                                                isSelected = index == 0,
                                                isPinned = index == 0,
                                                size = 64.dp, animateSelection = false)
                                          }
                                        }
                                  }
                                }

                            Surface(
                                modifier =
                                    Modifier.padding(top = 30.dp).graphicsLayer {
                                      translationY = swipeCueOffset
                                      alpha = if (isAlbumDrawerExpanded) 0.55f else 1f
                                    },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shadowElevation = 4.dp) {
                                  Icon(
                                      imageVector = Icons.Rounded.TouchApp,
                                      contentDescription = null,
                                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                      modifier = Modifier.padding(8.dp).size(24.dp))
                                }
                          }
                    }
              },

          // -- Page 7.4: Album Gestures --
          OnboardingPage(
              title = stringResource(R.string.onboarding_album_gestures_title),
              description = stringResource(R.string.onboarding_album_gestures_desc)) {
                val density = LocalDensity.current
                val itemTravelPx = with(density) { 76.dp.toPx() }
                val touchStartOffsetPx = with(density) { (-76).dp.toPx() }
                var demoStep by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                  while (true) {
                    demoStep = 0
                    delay(360L)
                    demoStep = 1
                    delay(120L)
                    demoStep = 2
                    delay(140L)
                    demoStep = 3
                    delay(120L)
                    demoStep = 4
                    delay(520L)
                    demoStep = 5
                    delay(900L)
                    demoStep = 6
                    delay(900L)
                  }
                }
                val isPinnedDemo = demoStep >= 4
                val draggedOffsetX by
                    animateFloatAsState(
                        targetValue = if (demoStep == 5) itemTravelPx else 0f,
                        animationSpec = AppMotion.timed(520),
                        label = "OnboardingAlbumGestureDraggedOffset")
                val pushedOffsetX by
                    animateFloatAsState(
                        targetValue = if (demoStep == 5) -itemTravelPx else 0f,
                        animationSpec = AppMotion.timed(520),
                        label = "OnboardingAlbumGesturePushedOffset")
                val touchOffsetX by
                    animateFloatAsState(
                        targetValue = touchStartOffsetPx + if (demoStep == 5) itemTravelPx else 0f,
                        animationSpec = AppMotion.timed(520),
                        label = "OnboardingAlbumGestureTouchOffset")
                val touchScale by
                    animateFloatAsState(
                        targetValue = if (demoStep == 1 || demoStep == 3) 0.72f else 1f,
                        animationSpec = AppMotion.fastSpatial(),
                        label = "OnboardingAlbumGestureTouchScale")
                val touchAlpha by
                    animateFloatAsState(
                        targetValue = if (demoStep == 4) 0.55f else 1f,
                        animationSpec = AppMotion.fastEffects(),
                        label = "OnboardingAlbumGestureTouchAlpha")

                Box(
                    modifier = Modifier.fillMaxWidth().height(138.dp),
                    contentAlignment = Alignment.Center) {
                      Row(
                          horizontalArrangement =
                              Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                          verticalAlignment = Alignment.Top,
                          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                            OnboardingAlbumThumbnailCard(
                                modifier =
                                    Modifier.graphicsLayer {
                                          translationX = draggedOffsetX
                                          translationY = if (demoStep == 5) 4f else 0f
                                        }
                                        .zIndex(if (demoStep == 5) 1f else 0f),
                                isSelected = true,
                                isPinned = isPinnedDemo,
                                size = 64.dp)
                            OnboardingAlbumThumbnailCard(
                                modifier = Modifier.graphicsLayer { translationX = pushedOffsetX },
                                isSelected = false,
                                isPinned = false,
                                size = 64.dp)
                            OnboardingAlbumThumbnailCard(
                                isSelected = false, isPinned = false, size = 64.dp)
                          }

                      Surface(
                          modifier =
                              Modifier.align(Alignment.TopCenter)
                                  .padding(top = 8.dp)
                                  .graphicsLayer {
                                    translationX = touchOffsetX
                                    alpha = touchAlpha
                                    scaleX = touchScale
                                    scaleY = touchScale
                                  },
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.primaryContainer,
                          shadowElevation = 4.dp) {
                            Icon(
                                imageVector = Icons.Rounded.TouchApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(8.dp).size(22.dp))
                          }
                    }
              },

          // ── Page 9: Reanalyze ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page9_title),
              description = stringResource(R.string.onboarding_page9_desc)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                      // Compact previews of the current status pills.
                      Surface(
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.secondaryContainer,
                          modifier = Modifier.height(56.dp).clip(CircleShape),
                      ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          Icon(
                              Icons.Rounded.CheckCircle,
                              null,
                              modifier = Modifier.size(18.dp),
                              tint = MaterialTheme.colorScheme.onSecondaryContainer,
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(
                              stringResource(R.string.done),
                              style = MaterialTheme.typography.labelMedium,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.onSecondaryContainer,
                          )
                        }
                      }

                      Spacer(modifier = Modifier.height(12.dp))

                      // "Analyzing" pill
                      Surface(
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.errorContainer,
                          modifier = Modifier.height(56.dp).clip(CircleShape),
                      ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          CircularProgressIndicator(
                              modifier = Modifier.size(18.dp),
                              strokeWidth = 2.dp,
                              color = MaterialTheme.colorScheme.error,
                              trackColor = MaterialTheme.colorScheme.errorContainer,
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(
                              stringResource(R.string.analyzing),
                              style = MaterialTheme.typography.labelMedium,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.error,
                          )
                        }
                      }

                      Spacer(modifier = Modifier.height(12.dp))

                      // "Pending" pill
                      Surface(
                          shape = CircleShape,
                          color = MaterialTheme.colorScheme.errorContainer,
                          modifier = Modifier.height(56.dp).clip(CircleShape),
                      ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          Icon(
                              Icons.Rounded.Schedule,
                              null,
                              modifier = Modifier.size(18.dp),
                              tint = MaterialTheme.colorScheme.error,
                          )
                          Spacer(modifier = Modifier.width(6.dp))
                          Text(
                              stringResource(R.string.pending),
                              style = MaterialTheme.typography.labelMedium,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.error,
                          )
                        }
                      }
                    }
              },

          // -- Page 10: Better Searching --
          OnboardingPage(
              title = stringResource(R.string.onboarding_extra_features_title),
              description = stringResource(R.string.onboarding_extra_features_desc)) {
                val infiniteTransition = rememberInfiniteTransition(label = "ExtraFeaturesGlow")
                val glowAlpha by
                    infiniteTransition.animateFloat(
                        initialValue = 0.55f,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = AppMotion.pulse(1400), repeatMode = RepeatMode.Reverse),
                        label = "ExtraFeaturesGlowAlpha")
                var isEmbeddingSearchMode by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                  while (true) {
                    delay(3200L)
                    isEmbeddingSearchMode = !isEmbeddingSearchMode
                  }
                }
                val modeIconScale by
                    animateFloatAsState(
                        targetValue = if (isEmbeddingSearchMode) 1.12f else 1f,
                        animationSpec = AppMotion.fastSpatial(),
                        label = "ExtraFeaturesSearchModeIconScale")
                val semanticModeAlpha by
                    animateFloatAsState(
                        targetValue = if (isEmbeddingSearchMode) 1f else 0f,
                        animationSpec = AppMotion.fastEffects(),
                        label = "ExtraFeaturesSemanticGlowAlpha")
                val semanticGlowAlpha = semanticModeAlpha * glowAlpha

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Box(
                      modifier =
                          Modifier.dispersedGlow(
                                  color = Color(0xFF2196F3),
                                  alpha = 0.20f * semanticGlowAlpha,
                                  glowRadius = 24.dp,
                                  borderRadius = 24.dp,
                                  horizontalInset = -10.dp,
                                  verticalInset = -6.dp)
                              .dispersedGlow(
                                  color = Color(0xFF2196F3),
                                  alpha = 0.38f * semanticGlowAlpha,
                                  glowRadius = 9.dp,
                                  borderRadius = 22.dp,
                                  horizontalInset = -4.dp,
                                  verticalInset = -2.dp),
                      contentAlignment = Alignment.Center,
                  ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                      Row(
                          modifier =
                              Modifier.height(40.dp)
                                  .widthIn(min = 250.dp)
                                  .padding(horizontal = 12.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Icon(
                            if (isEmbeddingSearchMode) Icons.Rounded.AutoAwesome
                            else Icons.Rounded.Search,
                            contentDescription = null,
                            tint =
                                if (isEmbeddingSearchMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.size(20.dp).graphicsLayer {
                                  scaleX = modeIconScale
                                  scaleY = modeIconScale
                                },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.onboarding_extra_features_search_hint),
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                      }
                    }
                  }
                }
              },

          // -- Page 11: Look Similar --
          OnboardingPage(
              title = stringResource(R.string.onboarding_look_similar_title),
              description = stringResource(R.string.onboarding_look_similar_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 380.dp) {
                OnboardingLookSimilarPreview()
              },

          // -- Page 12: Leaderboard --
          OnboardingPage(
              title = stringResource(R.string.onboarding_leaderboard_title),
              description = stringResource(R.string.onboarding_leaderboard_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 260.dp) {
                OnboardingLeaderboardPreview()
              },

          // -- Page 13: Analytics+ --
          OnboardingPage(
              title = stringResource(R.string.onboarding_analytics_plus_title),
              description = stringResource(R.string.onboarding_analytics_plus_desc),
              useWidePreviewFrame = true,
              useFixedPreviewFrameHeight = true,
              previewFrameHeight = 340.dp) {
                OnboardingAnalyticsPreview()
              })

  // ── Page 14: Advanced Settings ──
  val advancedSettingsPage =
      OnboardingPage(
          title = stringResource(R.string.onboarding_page10_title),
          description = stringResource(R.string.onboarding_page10_desc),
          useWidePreviewFrame = true,
          useFixedPreviewFrameHeight = true,
          previewFrameHeight = 220.dp) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
              Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Surface(
                      shape = CircleShape,
                      color = if (isDark) Color(0xFF2D1F3D) else Color(0xFFF3E5F5),
                      modifier = Modifier.size(40.dp),
                  ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                      Icon(
                          Icons.Rounded.Tune,
                          null,
                          tint = if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2),
                          modifier = Modifier.size(22.dp))
                    }
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Column {
                    Text(
                        stringResource(R.string.advanced_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.advanced_settings_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Spacer(modifier = Modifier.height(10.dp))

                // Image Resolution mock
                Column {
                  Text(
                      stringResource(R.string.image_resolution),
                      style = MaterialTheme.typography.labelLarge,
                      fontWeight = FontWeight.Bold,
                  )
                  Text(
                      stringResource(R.string.image_resolution_desc),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(modifier = Modifier.height(6.dp))

                  var resolutionValue by remember { mutableStateOf(0.5f) }
                  Slider(
                      value = resolutionValue,
                      onValueChange = { resolutionValue = it },
                      valueRange = 0f..1f)
                }
                Spacer(modifier = Modifier.height(6.dp))

                // Play/Pause toggle mock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.play_pause_toggle_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.play_pause_toggle_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Switch(checked = false, onCheckedChange = {}, enabled = false)
                    }
              }
            }
          }

  val allPages = pages + advancedSettingsPage
  val sections =
      listOf(
          OnboardingSection(
              title = stringResource(R.string.onboarding_section_ai_models),
              icon = Icons.Rounded.SmartToy,
              pages = listOf(allPages[0], allPages[2], allPages[3])),
          OnboardingSection(
              title = stringResource(R.string.onboarding_section_home_page),
              icon = Icons.Rounded.Home,
              pages =
                  listOf(
                      allPages[1],
                      allPages[4],
                      allPages[5],
                      allPages[8],
                      allPages[9],
                      allPages[10],
                      allPages[11])),
          OnboardingSection(
              title = stringResource(R.string.onboarding_section_detail_page),
              icon = Icons.Rounded.Article,
              pages = listOf(allPages[6], allPages[14])),
          OnboardingSection(
              title = stringResource(R.string.onboarding_section_search),
              icon = Icons.Rounded.Search,
              pages = listOf(allPages[7], allPages[13])),
          OnboardingSection(
              title = stringResource(R.string.onboarding_section_extra_features),
              icon = Icons.Rounded.AutoAwesome,
              pages = listOf(allPages[12], allPages[15], allPages[16], allPages[17])),
      )
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val currentSectionIndex = listState.firstVisibleItemIndex.coerceAtMost(sections.lastIndex)
  val canScrollToNextSection = currentSectionIndex < sections.lastIndex

  val onboardingRuntimePermissions = remember {
    buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        add(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        @Suppress("DEPRECATION") add(Manifest.permission.READ_EXTERNAL_STORAGE)
      }
    }
  }

  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          grants ->
        val hasMediaAccess =
            when {
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                  grants[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                      grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
                      ContextCompat.checkSelfPermission(
                          context, Manifest.permission.READ_MEDIA_IMAGES) ==
                          PackageManager.PERMISSION_GRANTED ||
                      ContextCompat.checkSelfPermission(
                          context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                          PackageManager.PERMISSION_GRANTED
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                  grants[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                      ContextCompat.checkSelfPermission(
                          context, Manifest.permission.READ_MEDIA_IMAGES) ==
                          PackageManager.PERMISSION_GRANTED
              else -> {
                @Suppress("DEPRECATION")
                grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
              }
            }

        if (hasMediaAccess) {
          viewModel.loadAlbums()
        }
      }

  LaunchedEffect(Unit) {
    val missingPermissions =
        onboardingRuntimePermissions.filter { permission ->
          ContextCompat.checkSelfPermission(context, permission) !=
              PackageManager.PERMISSION_GRANTED
        }

    if (missingPermissions.isNotEmpty()) {
      permissionLauncher.launch(missingPermissions.toTypedArray())
    } else {
      viewModel.loadAlbums()
    }
  }

  Scaffold(
      contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
      bottomBar = {
        val nextInteraction = remember { MutableInteractionSource() }
        val startInteraction = remember { MutableInteractionSource() }
        Surface(
            color = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
          Row(
              modifier =
                  Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.End) {
                FilledTonalIconButton(
                    enabled = canScrollToNextSection,
                    onClick =
                        hapticOnClick {
                          if (canScrollToNextSection) {
                            coroutineScope.launch {
                              listState.animateScrollToItem(currentSectionIndex + 1)
                            }
                          }
                        },
                    interactionSource = nextInteraction,
                    shape = rememberSquigglePillShape(nextInteraction, cornerRadius = 24.dp),
                    modifier = Modifier.size(48.dp)) {
                      Icon(
                          imageVector = Icons.Rounded.KeyboardArrowDown,
                          contentDescription = stringResource(R.string.onboarding_next))
                    }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = hapticOnClick(onFinish),
                    interactionSource = startInteraction,
                    shape = rememberSquigglePillShape(startInteraction, cornerRadius = 24.dp),
                    modifier = Modifier.height(48.dp)) {
                      Text(
                          text = stringResource(R.string.onboarding_get_started),
                          fontWeight = FontWeight.Bold)
                    }
              }
        }
      }) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = innerPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
              sections.forEach { section -> item { OnboardingSectionBlock(section = section) } }
            }
      }

  if (showHfLogin) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
          showHfLogin = false
          val cookies = CookieManager.getInstance().getCookie("https://huggingface.co")
          if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
            val url = if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl else gemma4Url
            viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
          }
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
      Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
          Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                            CookieManager.getInstance().getCookie("https://huggingface.co")
                        if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
                          val url =
                              if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl else gemma4Url
                          viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
                        }
                      }) {
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
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                  WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val loginRepoUrl =
                        when (hfLoginModelType) {
                          ModelType.GEMMA_3N ->
                              "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
                          ModelType.GEMMA_4 ->
                              "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
                          ModelType.GGUF -> ""
                          ModelType.AICORE -> ""
                          ModelType.DESKTOP -> ""
                        }
                    loadUrl(loginRepoUrl)
                  }
                })
          }
        }
      }
    }
  }
}

@Composable
private fun OnboardingCurrentAnalysisPreview() {
  val infiniteTransition = rememberInfiniteTransition(label = "CurrentAnalysisPreview")
  val pulseAlpha by
      infiniteTransition.animateFloat(
          initialValue = 0.55f,
          targetValue = 1f,
          animationSpec =
              infiniteRepeatable(animation = AppMotion.pulse(900), repeatMode = RepeatMode.Reverse),
          label = "CurrentAnalysisPulseAlpha")
  val tapScale by
      infiniteTransition.animateFloat(
          initialValue = 0.92f,
          targetValue = 1.08f,
          animationSpec =
              infiniteRepeatable(animation = AppMotion.pulse(780), repeatMode = RepeatMode.Reverse),
          label = "CurrentAnalysisTapScale")

  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {

    Surface(
        modifier = Modifier.fillMaxWidth().height(112.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Row(
          modifier = Modifier.fillMaxSize().padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val analysisGlowColor = Color(0xFF35D07F)
        repeat(3) { index ->
          val isCurrentImage = index == 1
          Box(
              modifier =
                  Modifier.weight(1f)
                      .fillMaxHeight()
                      .zIndex(if (isCurrentImage) 1f else 0f)
                      .then(
                          if (isCurrentImage) {
                            Modifier.dispersedGlow(
                                    color = analysisGlowColor,
                                    alpha = 0.34f * pulseAlpha,
                                    glowRadius = 20.dp,
                                    borderRadius = 18.dp,
                                    horizontalInset = (-8).dp,
                                    verticalInset = (-8).dp,
                                )
                                .dispersedGlow(
                                    color = analysisGlowColor,
                                    alpha = 0.62f * pulseAlpha,
                                    glowRadius = 10.dp,
                                    borderRadius = 14.dp,
                                    horizontalInset = (-4).dp,
                                    verticalInset = (-4).dp,
                                )
                          } else {
                            Modifier
                          })
                      .graphicsLayer {
                        if (isCurrentImage) {
                          scaleX = 1.01f
                          scaleY = 1.01f
                        }
                      }
                      .clip(RoundedCornerShape(14.dp))
                      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                      .border(
                          width = if (isCurrentImage) 2.dp else 1.dp,
                          color =
                              if (isCurrentImage) analysisGlowColor.copy(alpha = 0.82f * pulseAlpha)
                              else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                          shape = RoundedCornerShape(14.dp)),
          )
        }
      }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error,
            shadowElevation = 2.dp,
            modifier = Modifier.height(44.dp).clip(CircleShape)
                .combinedClickable(onClick = hapticOnClick {}, onDoubleClick = {}),
        ) {
          Row(
              modifier = Modifier.padding(horizontal = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
                progress = { 0.62f },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error,
                trackColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "3",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }

        Surface(
            modifier =
                Modifier.offset(x = 12.dp, y = (-10).dp).graphicsLayer {
                  scaleX = tapScale
                  scaleY = tapScale
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 3.dp,
        ) {
          Icon(
              imageVector = Icons.Rounded.TouchApp,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(6.dp).size(16.dp))
        }
      }
    }

  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingLeaderboardPreview() {
  val metricContainer = MaterialTheme.colorScheme.secondaryContainer
  val onMetricContainer = MaterialTheme.colorScheme.onSecondaryContainer

  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.leaderboard_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.leaderboard_top_n, 10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    listOf(
            stringResource(R.string.leaderboard_phone_a) to "1.151s",
            stringResource(R.string.leaderboard_phone_b) to "13.422s",
            stringResource(R.string.leaderboard_phone_c) to "15.564s",
        )
        .forEachIndexed { index, (device, time) ->
          Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(if (index == 0) 20.dp else 16.dp),
              color =
                  if (index == 0) MaterialTheme.colorScheme.tertiaryContainer
                  else MaterialTheme.colorScheme.surfaceContainerHighest,
          ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                  modifier = Modifier.size(26.dp),
                  shape = CircleShape,
                  color =
                      if (index == 0) MaterialTheme.colorScheme.tertiary
                      else MaterialTheme.colorScheme.surfaceVariant,
              ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                  Text(
                      text = stringResource(R.string.leaderboard_rank_short, index + 1),
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      softWrap = false,
                      color =
                          if (index == 0) MaterialTheme.colorScheme.onTertiary
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                  text = device,
                  modifier = Modifier.weight(1f),
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
              )
              Text(
                  text = time,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
              )
            }
          }
        }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
          IconButton(
              onClick = hapticOnClick {},
              modifier = Modifier.size(34.dp),
              shape = CircleShape,
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(18.dp))
          }
        }
        ButtonGroup(
            modifier = Modifier.width(58.dp),
            expandedRatio = 0.12f,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
          listOf(Icons.Rounded.Info, Icons.Rounded.WarningAmber).forEachIndexed { index, icon ->
            val source = remember { MutableInteractionSource() }
            var highlighted by remember { mutableStateOf(false) }
            FilledTonalButton(
                onClick = hapticOnClick { if (index == 0) highlighted = !highlighted },
                modifier = Modifier.weight(1f).height(34.dp).animateWidth(source),
                interactionSource = source,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (highlighted) MaterialTheme.colorScheme.primary else metricContainer,
                    contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else onMetricContainer,
                ),
                shapes = if (index == 0)
                    ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape)
                    else ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
            ) {
              Icon(icon, if (index == 0) stringResource(R.string.leaderboard_rules_content_description) else "Warning", modifier = Modifier.size(16.dp))
            }
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        OnboardingLeaderboardChoice(
            choices = listOf(stringResource(R.string.leaderboard_model_all), "Gemma 4"),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        OnboardingLeaderboardChoice(
            choices = listOf(stringResource(R.string.leaderboard_metric_image_short), stringResource(R.string.leaderboard_metric_text_short)),
            containerColor = metricContainer,
            contentColor = onMetricContainer,
        )
      }
    }
  }
}

@Composable
private fun OnboardingLeaderboardChoice(choices: List<String>, containerColor: Color, contentColor: Color) {
  var selected by remember { mutableStateOf(0) }
  var expanded by remember { mutableStateOf(false) }
  val source = remember { MutableInteractionSource() }
  Box {
    FilledTonalButton(
        onClick = hapticOnClick { expanded = !expanded },
        modifier = Modifier.height(34.dp).animateContentSize(animationSpec = AppMotion.timed(240)),
        interactionSource = source,
        shape = rememberSquigglePillShape(source, cornerRadius = 17.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
      Text(choices[selected], style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(24.dp)) {
      choices.forEachIndexed { index, choice ->
        DropdownMenuItem(text = { Text(choice) }, onClick = hapticOnClick { selected = index; expanded = false })
      }
    }
  }
}

@Composable
private fun OnboardingAnalyticsPreview() {
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.tertiary
  val isDark = LocalIsDarkMode.current
  val averageContainer = if (isDark) Color(0xFF1A2E42) else Color(0xFFE3F2FD)
  val averageContent = if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)
  val fastestContainer = if (isDark) Color(0xFF173A2A) else Color(0xFFE8F5E9)
  val fastestContent = if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
  val slowestContainer = MaterialTheme.colorScheme.errorContainer
  val slowestContent = MaterialTheme.colorScheme.onErrorContainer
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
  val infiniteTransition = rememberInfiniteTransition(label = "OnboardingAnalyticsPreview")
  val latestDotAlpha by
      infiniteTransition.animateFloat(
          initialValue = 0.55f,
          targetValue = 1f,
          animationSpec =
              infiniteRepeatable(
                  animation = AppMotion.pulse(1000),
                  repeatMode = RepeatMode.Reverse,
              ),
          label = "OnboardingAnalyticsLatestDotAlpha",
      )

  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          modifier = Modifier.fillMaxHeight(),
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          contentColor = if (isDark) Color(0xFFE8E8E8) else MaterialTheme.colorScheme.onSurface,
      ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              text = stringResource(R.string.analytics_title_enabled).uppercase(),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
          )
        }
      }
      Spacer(modifier = Modifier.weight(1f))
      Surface(
          modifier = Modifier.fillMaxHeight(),
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
              imageVector = Icons.Rounded.Leaderboard,
              contentDescription = stringResource(R.string.leaderboard_title),
              modifier = Modifier.size(17.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
              text = stringResource(R.string.leaderboard_title).uppercase(),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.sp,
          )
        }
      }
    }

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(19.dp),
                ),
    ) {
      Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp)),
        ) {
          OnboardingAnalyticsMetric(
              label = stringResource(R.string.benchmark_average),
              value = stringResource(R.string.benchmark_average_value, "15.8s", 12),
              containerColor = averageContainer,
              contentColor = averageContent,
              shape = RoundedCornerShape(0.dp),
              modifier = Modifier.weight(1f),
          )
          OnboardingAnalyticsMetric(
              label = stringResource(R.string.benchmark_fastest),
              value = "1.151s",
              containerColor = fastestContainer,
              contentColor = fastestContent,
              shape = RoundedCornerShape(0.dp),
              modifier = Modifier.width(80.dp),
          )
          OnboardingAnalyticsMetric(
              label = stringResource(R.string.benchmark_slowest),
              value = "55.0s",
              containerColor = slowestContainer,
              contentColor = slowestContent,
              shape = RoundedCornerShape(0.dp),
              modifier = Modifier.width(80.dp),
          )
        }

        Column {
          Text(
              text = stringResource(R.string.benchmark_speed_title),
              modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
          )
          OnboardingAnalyticsChart(
              modifier = Modifier.fillMaxWidth().height(76.dp),
              series = listOf(listOf(0.30f, 0.46f, 0.37f, 0.55f, 0.45f, 0.62f, 0.52f) to primary),
              fillFirstSeries = true,
              latestDotAlpha = latestDotAlpha,
          )

          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 7.dp),
              horizontalArrangement = Arrangement.End,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OnboardingAnalyticsPill(
                label = stringResource(R.string.benchmark_cpu),
                value = "54%",
                color = primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(7.dp))
            OnboardingAnalyticsPill(
                label = stringResource(R.string.benchmark_ram),
                value = "63%",
                color = secondary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
          }
          OnboardingAnalyticsChart(
              modifier = Modifier.fillMaxWidth().height(82.dp),
              series =
                  listOf(
                      listOf(0.68f, 0.66f, 0.71f, 0.69f, 0.74f, 0.70f, 0.73f) to secondary,
                      listOf(0.34f, 0.40f, 0.30f, 0.44f, 0.38f, 0.48f, 0.42f) to primary),
              latestDotAlpha = latestDotAlpha,
          )
        }
      }
    }
  }
}

@Composable
private fun OnboardingAnalyticsChart(
    series: List<Pair<List<Float>, Color>>,
    modifier: Modifier = Modifier,
    fillFirstSeries: Boolean = false,
    latestDotAlpha: Float = 1f,
) {
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
  BoxWithConstraints(modifier = modifier) {
    val plotTop = 8.dp
    val plotBottom = (maxHeight - 8.dp).coerceAtLeast(plotTop)
    val plotHeight = plotBottom - plotTop
    val dotRadius = 3.dp

    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
      val top = 2.dp.toPx()
      val bottom = size.height - 2.dp.toPx()
      // Keep the plot and its filled area edge-to-edge; endpoint dots are overlaid separately.
      val horizontalInset = 0f
      val plotWidth = size.width
      repeat(3) { index ->
        val y = top + (bottom - top) * index / 2f
        drawLine(
            color = gridColor,
            start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
        )
      }

      series.forEachIndexed { seriesIndex, (values, color) ->
        if (values.isNotEmpty()) {
          fun x(index: Int): Float =
              if (values.size == 1) size.width / 2f
              else horizontalInset + plotWidth * index / (values.size - 1f)

          fun y(value: Float): Float = bottom - value.coerceIn(0f, 1f) * (bottom - top)

          val linePath =
              Path().apply {
                moveTo(x(0), y(values.first()))
                values.drop(1).forEachIndexed { index, value -> lineTo(x(index + 1), y(value)) }
              }
          if (fillFirstSeries && seriesIndex == 0) {
            val fillPath =
                Path().apply {
                  addPath(linePath)
                  lineTo(size.width, bottom)
                  lineTo(0f, bottom)
                  close()
                }
            drawPath(fillPath, color = color.copy(alpha = 0.10f))
          }
          drawPath(
              linePath,
              color = color,
              style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
          )
        }
      }
    }

    series.forEach { (values, color) ->
      if (values.isNotEmpty()) {
        val markerY = plotBottom - plotHeight * values.last().coerceIn(0f, 1f)
        val horizontalPlacement =
            if (values.size == 1) {
              Modifier.align(Alignment.TopCenter)
            } else {
              Modifier.align(Alignment.TopEnd).offset(x = dotRadius)
            }
        Box(
            modifier =
                Modifier.size(dotRadius * 2f)
                    .then(horizontalPlacement)
                    .offset(y = markerY - dotRadius)
                    .zIndex(2f)
                    .background(color.copy(alpha = latestDotAlpha), CircleShape),
        )
      }
    }
  }
}

@Composable
private fun OnboardingAnalyticsMetric(
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
) {
  Surface(modifier = modifier, shape = shape, color = containerColor) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
      Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Medium,
          color = contentColor.copy(alpha = 0.76f),
      )
      Spacer(modifier = Modifier.height(3.dp))
      Text(
          text = value,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = if (LocalIsDarkMode.current) contentColor else Color.Black,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun OnboardingAnalyticsPill(
    label: String,
    value: String,
    color: Color,
    contentColor: Color,
) {
  Row(modifier = Modifier.clip(CircleShape)) {
    Box(modifier = Modifier.background(color)) {
      Text(
          text = label,
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = contentColor,
      )
    }
    Box(modifier = Modifier.background(contentColor)) {
      Text(
          text = value,
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = color,
      )
    }
  }
}

/** Uses the same filter row and expandable entry cards as the real leaderboard screen. */
@Composable
private fun OnboardingLeaderboardPreviewExact() {
  val density = LocalDensity.current
  val previewDensity =
      remember(density) {
        Density(density = density.density * 0.70f, fontScale = density.fontScale * 0.70f)
      }
  val previewEntries = remember {
    listOf(
        onboardingPreviewLeaderboardEntry(
            rank = 1,
            manufacturer = "OnePlus",
            model = "CPH2653",
            durationSeconds = 1.151,
        ),
        onboardingPreviewLeaderboardEntry(
            rank = 2,
            manufacturer = "samsung",
            model = "SM-A175F",
            durationSeconds = 13.422,
        ),
        onboardingPreviewLeaderboardEntry(
            rank = 3,
            manufacturer = "OPPO",
            model = "OPD2409",
            durationSeconds = 15.564,
        ),
    )
  }

  CompositionLocalProvider(LocalDensity provides previewDensity) {
    Column(modifier = Modifier.fillMaxWidth()) {
      LeaderboardFilterRow(
          selectedModel = LeaderboardModel.GEMMA_4,
          onSelectModel = {},
          selectedMetric = LeaderboardMetric.FASTEST_TIME_PER_IMAGE,
          onSelectMetric = {},
          onToggleRules = {},
      )
      Spacer(modifier = Modifier.height(6.dp))
      Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        LeaderboardEntryCard(
            entry = previewEntries[0],
            metric = LeaderboardMetric.FASTEST_TIME_PER_IMAGE,
            isCurrentDevice = true,
            expanded = false,
            onToggle = {},
        )
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = stringResource(R.string.leaderboard_top_n, 10),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LeaderboardEntryCard(
            entry = previewEntries[1],
            metric = LeaderboardMetric.FASTEST_TIME_PER_IMAGE,
            isCurrentDevice = false,
            expanded = false,
            onToggle = {},
        )
        LeaderboardEntryCard(
            entry = previewEntries[2],
            metric = LeaderboardMetric.FASTEST_TIME_PER_IMAGE,
            isCurrentDevice = false,
            expanded = false,
            onToggle = {},
        )
      }
    }
  }
}

private fun onboardingPreviewLeaderboardEntry(
    rank: Int,
    manufacturer: String,
    model: String,
    durationSeconds: Double,
) =
    LeaderboardEntry(
        rank = rank,
        metricScore = null,
        deviceId = "onboarding-preview-$rank",
        entryId = "onboarding-preview-$rank",
        benchmarkVersion = "1.0",
        durationMillis = (durationSeconds * 1000).toLong(),
        durationSeconds = durationSeconds,
        analyzedImageWidthPixels = 2_048,
        analyzedImageHeightPixels = 1_536,
        analyzedImagePixelCount = 3_145_728L,
        generatedTextCharacterCount = 239,
        deviceManufacturer = manufacturer,
        deviceModel = model,
        androidSdk = 35,
        appId = null,
        appVersionCode = null,
        appVersionName = null,
        modelUsed = "Gemma 4",
        recordedAt = null,
        recordedAtClientMillis = null,
        snapshotAt = null,
    )

/** Uses the actual Analytics+ header and benchmark card, with a smaller density for the preview. */
@Composable
private fun OnboardingAnalyticsPreviewExact() {
  val density = LocalDensity.current
  val previewDensity =
      remember(density) {
        Density(density = density.density * 0.72f, fontScale = density.fontScale * 0.72f)
      }
  val previewBenchmark = remember {
    AnalysisBenchmarkState(
        processingTimes =
            listOf(
                ProcessingTimeSample(0L, 1_900L),
                ProcessingTimeSample(1L, 2_400L),
                ProcessingTimeSample(2L, 1_650L),
                ProcessingTimeSample(3L, 2_800L),
                ProcessingTimeSample(4L, 2_100L),
                ProcessingTimeSample(5L, 2_550L),
                ProcessingTimeSample(6L, 2_250L),
            ),
        resourceUsage =
            listOf(
                ResourceUsageSample(0L, 44f, 62f),
                ResourceUsageSample(1L, 49f, 66f),
                ResourceUsageSample(2L, 46f, 64f),
                ResourceUsageSample(3L, 54f, 70f),
                ResourceUsageSample(4L, 51f, 68f),
                ResourceUsageSample(5L, 57f, 73f),
                ResourceUsageSample(6L, 53f, 69f),
            ),
    )
  }

  CompositionLocalProvider(LocalDensity provides previewDensity) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
          modifier =
              Modifier.fillMaxWidth()
                  .height(34.dp)
                  .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
            modifier = Modifier.fillMaxHeight(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Box(
              modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = stringResource(R.string.analytics_title_enabled),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxHeight(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Row(
              modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
                imageVector = Icons.Rounded.Leaderboard,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.leaderboard_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
          }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            modifier = Modifier.fillMaxHeight(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Box(
              modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = stringResource(R.string.analytics_show_latest),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      AnalysisBenchmarkCard(
          benchmark = previewBenchmark,
          activeProcessingStartTimes = emptyList(),
          liveResetKey = 0,
          onSpeedLiveViewChanged = {},
          onResourceLiveViewChanged = {},
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      )
    }
  }
}

@Composable
private fun OnboardingSectionBlock(section: OnboardingSection, modifier: Modifier = Modifier) {
  Surface(
      modifier = modifier.fillMaxWidth().widthIn(max = 980.dp),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      tonalElevation = 0.dp,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = section.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(23.dp))
              }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
          }

          section.pages.forEachIndexed { index, page ->
            OnboardingSectionPageRow(page = page)
            if (index < section.pages.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
          }
        }
  }
}

@Composable
private fun OnboardingSectionPageRow(page: OnboardingPage, modifier: Modifier = Modifier) {
  BoxWithConstraints(
      modifier = modifier.fillMaxWidth().animateContentSize(animationSpec = AppMotion.spatial())) {
        val isWideRow = maxWidth >= 720.dp
        if (isWideRow) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(20.dp),
              verticalAlignment = Alignment.CenterVertically) {
                OnboardingPreviewFrame(page = page, modifier = Modifier.weight(0.95f))
                OnboardingPageCopy(page = page, modifier = Modifier.weight(1f))
              }
        } else {
          Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              horizontalAlignment = Alignment.CenterHorizontally) {
                OnboardingPreviewFrame(page = page, modifier = Modifier.fillMaxWidth())
                OnboardingPageCopy(page = page, modifier = Modifier.fillMaxWidth())
              }
        }
      }
}

@Composable
private fun OnboardingPreviewFrame(page: OnboardingPage, modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    var frameModifier =
        if (page.useWidePreviewFrame) {
          Modifier.fillMaxWidth()
        } else {
          Modifier.fillMaxWidth().widthIn(max = 420.dp)
        }

    frameModifier =
        when {
          page.previewFrameHeight != null -> frameModifier.height(page.previewFrameHeight)
          page.useFixedPreviewFrameHeight -> frameModifier.height(320.dp)
          else -> frameModifier
        }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = frameModifier) {
          Box(
              contentAlignment = Alignment.Center,
              modifier =
                  if (page.useWidePreviewFrame || page.useFixedPreviewFrameHeight) {
                    val verticalPadding = if (page.previewFrameHeight != null) 10.dp else 20.dp
                    Modifier.fillMaxSize().padding(vertical = verticalPadding)
                  } else {
                    Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                  }) {
                page.content()
              }
        }
  }
}

@Composable
private fun OnboardingPageCopy(page: OnboardingPage, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = page.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold)
    Text(
        text = page.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 21.sp)
  }
}

@Composable
private fun OnboardingAlbumThumbnailCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    isPinned: Boolean,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    animateSelection: Boolean = true,
) {
  val frameInteraction = remember { MutableInteractionSource() }
  val frameShape = rememberSquigglePillShape(frameInteraction, cornerRadius = 12.dp)
  LaunchedEffect(isSelected, animateSelection) {
    if (isSelected && animateSelection) {
      val press = androidx.compose.foundation.interaction.PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
      frameInteraction.emit(press)
      delay(120)
      frameInteraction.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
    }
  }
  val badgeOverflow = 6.dp
  val visualCenterOffset = badgeOverflow / 2
  val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
  val backgroundColor =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHighest
  val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
  val selectedPlaceholderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  val labelColor =
      if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)

  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.width(size + badgeOverflow),
  ) {
    Box(modifier = Modifier.size(size + badgeOverflow), contentAlignment = Alignment.BottomEnd) {
      Box(
          modifier =
              Modifier.size(size)
                  .border(2.dp, borderColor, if (animateSelection) frameShape else RoundedCornerShape(12.dp))
                  .clip(RoundedCornerShape(12.dp))
                  .background(backgroundColor),
          contentAlignment = Alignment.Center,
      ) {
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
                val cornerShape =
                    RoundedCornerShape(
                        topStart = if (row == 0 && col == 0) 10.dp else 2.dp,
                        topEnd = if (row == 0 && col == 1) 10.dp else 2.dp,
                        bottomStart = if (row == 1 && col == 0) 10.dp else 2.dp,
                        bottomEnd = if (row == 1 && col == 1) 10.dp else 2.dp,
                    )
                Box(
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxSize()
                            .clip(cornerShape)
                            .background(
                                if (isSelected) selectedPlaceholderColor else placeholderColor))
              }
            }
          }
        }
      }

      if (isPinned) {
        Surface(
            modifier = Modifier.align(Alignment.TopStart).size(18.dp),
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

    Box(
        modifier = Modifier.width(size).offset(x = visualCenterOffset),
        contentAlignment = Alignment.Center) {
          Box(
              modifier =
                  Modifier.fillMaxWidth(0.78f)
                      .height(8.dp)
                      .clip(RoundedCornerShape(4.dp))
                      .background(labelColor),
          )
        }
    Spacer(modifier = Modifier.height(3.dp))
    Box(
        modifier = Modifier.width(size).offset(x = visualCenterOffset),
        contentAlignment = Alignment.Center) {
          Box(
              modifier =
                  Modifier.fillMaxWidth(0.34f)
                      .height(6.dp)
                      .clip(RoundedCornerShape(3.dp))
                      .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
          )
        }
  }
}

@Composable
private fun OnboardingPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
  val visibleDotCount = minOf(pageCount, 5)
  val firstVisiblePage =
      when {
        pageCount <= visibleDotCount -> 0
        currentPage <= 2 -> 0
        currentPage >= pageCount - 3 -> pageCount - visibleDotCount
        else -> currentPage - 2
      }

  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      repeat(visibleDotCount) { offset ->
        val page = firstVisiblePage + offset
        val isSelected = page == currentPage
        val dotSize by
            animateDpAsState(
                targetValue = if (isSelected) 12.dp else 7.dp,
                animationSpec = AppMotion.fastSpatial(),
                label = "OnboardingPageDotSize",
            )
        Box(
            modifier =
                Modifier.clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                    .size(dotSize))
      }
    }

    Spacer(modifier = Modifier.width(8.dp))

    Text(
        text = "${currentPage + 1}/$pageCount",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
  }
}

@Composable
private fun OnboardingLookSimilarPreview() {
  val pullProgress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    while (true) {
      pullProgress.snapTo(0f)
      delay(520L)
      pullProgress.animateTo(
          targetValue = 1f,
          animationSpec = AppMotion.timed(980),
      )
      delay(1500L)
    }
  }

  val progress = pullProgress.value.coerceIn(0f, 1f)
  val showSimilar = progress >= 0.98f
  val detailAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 0.62f else 1f,
          animationSpec = AppMotion.effects(),
          label = "LookSimilarDetailAlpha",
      )
  val loaderAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 0f else 1f,
          animationSpec = AppMotion.fastEffects(),
          label = "LookSimilarLoaderAlpha",
      )
  val similarAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 1f else 0f,
          animationSpec = AppMotion.effects(),
          label = "LookSimilarResultsAlpha",
      )
  val similarOffsetY by
      animateFloatAsState(
          targetValue = if (showSimilar) 0f else -18f,
          animationSpec = AppMotion.spatial(),
          label = "LookSimilarResultsOffset",
      )
  val similarAreaHeight by
      animateDpAsState(
          targetValue = if (showSimilar) 126.dp else 68.dp,
          animationSpec = AppMotion.spatial(),
          label = "LookSimilarAreaHeight",
      )

  Surface(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 24.dp)
              .wrapContentHeight()
              .animateContentSize(animationSpec = AppMotion.spatial()),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
  ) {
    Column(modifier = Modifier.wrapContentHeight().padding(14.dp)) {
      Column(
          modifier =
              Modifier.graphicsLayer {
                alpha = detailAlpha
                translationY = -18f * progress
              },
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
              Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
              stringResource(R.string.details_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
          )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
          Column(
              modifier = Modifier.fillMaxSize().padding(10.dp),
              verticalArrangement = Arrangement.SpaceBetween,
          ) {
            repeat(3) { index ->
              Box(
                  modifier =
                      Modifier.fillMaxWidth(if (index == 2) 0.62f else 1f)
                          .height(12.dp)
                          .clip(RoundedCornerShape(4.dp))
                          .background(
                              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OnboardingMockLine(widthFraction = 0.84f)
        Spacer(modifier = Modifier.height(5.dp))
        OnboardingMockLine(widthFraction = 0.66f)
      }

      Spacer(modifier = Modifier.height(8.dp))

      Box(
          modifier = Modifier.fillMaxWidth().height(similarAreaHeight),
          contentAlignment = Alignment.TopCenter,
      ) {
        Box(
            modifier =
                Modifier.fillMaxWidth().height(68.dp).align(Alignment.Center).graphicsLayer {
                  alpha = loaderAlpha
                },
            contentAlignment = Alignment.Center,
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                      val loaderScale = 0.86f + (progress * 0.14f)
                      scaleX = loaderScale
                      scaleY = loaderScale
                    },
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Column(
            modifier =
                Modifier.align(Alignment.TopCenter).graphicsLayer {
                  alpha = similarAlpha
                  translationY = similarOffsetY
                },
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(18.dp)) {
              Icon(
                  Icons.Rounded.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
              )
              Icon(
                  Icons.Rounded.AutoAwesome,
                  contentDescription = null,
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
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            listOf("91%", "84%", "78%", "72%").forEachIndexed { index, score ->
              OnboardingSimilarTile(
                  score = score,
                  tone = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f + index * 0.04f),
                  modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OnboardingMockLine(widthFraction: Float) {
  Box(
      modifier =
          Modifier.fillMaxWidth(widthFraction)
              .height(8.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)),
  )
}

@Composable
private fun OnboardingSimilarTile(
    score: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
  Box(
      modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(tone),
  ) {
    Box(
        modifier =
            Modifier.align(Alignment.Center)
                .fillMaxWidth(0.58f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)),
    )
    Surface(
        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(6.dp),
    ) {
      Text(
          text = score,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
          color = Color.White,
          fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun OnboardingSquiggleCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    border: BorderStroke = CardDefaults.outlinedCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  OutlinedCard(
      onClick = onClick,
      modifier = modifier,
      interactionSource = interactionSource,
      shape = rememberSquigglePillShape(interactionSource, cornerRadius, continuous = loading),
      colors = colors,
      border = border,
      content = content,
  )
}

@Composable
private fun OnboardingSquiggleChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
) {
  val interactionSource = remember { MutableInteractionSource() }
  FilterChip(
      selected = selected,
      onClick = onClick,
      label = label,
      modifier = modifier,
      interactionSource = interactionSource,
      shape = rememberSquigglePillShape(interactionSource, cornerRadius),
      colors = colors,
  )
}
