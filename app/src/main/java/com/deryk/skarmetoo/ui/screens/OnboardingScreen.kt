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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_MODEL
import com.deryk.skarmetoo.ai.LlmManager
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import com.deryk.skarmetoo.viewmodel.ModelType
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
    ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(viewModel: ScreenshotViewModel, onFinish: () -> Unit) {
  val isDark = LocalIsDarkMode.current
  val context = LocalContext.current

  val selectedModel by viewModel.selectedModel.collectAsState()
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
                  OutlinedCard(
                      onClick =
                          hapticOnClick {
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
                      shape = RoundedCornerShape(14.dp),
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
                  Spacer(modifier = Modifier.width(10.dp))
                  Text(
                      stringResource(R.string.settings),
                      style = MaterialTheme.typography.headlineSmall,
                      fontWeight = FontWeight.Bold,
                  )
                  Spacer(modifier = Modifier.width(16.dp))
                  Spacer(modifier = Modifier.weight(1f))
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
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp)) {
                                  Icon(
                                      Icons.Rounded.MenuBook,
                                      contentDescription = "Tutorial",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick { viewModel.setDarkMode(!isDark) },
                                modifier = Modifier.size(34.dp)) {
                                  Icon(
                                      if (isDark) Icons.Rounded.LightMode
                                      else Icons.Rounded.DarkMode,
                                      contentDescription = "Toggle Dark Mode",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp)) {
                                  Icon(
                                      Icons.Rounded.Monitor,
                                      contentDescription = "Screen Saver",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(
                                onClick = hapticOnClick {}, modifier = Modifier.size(34.dp)) {
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
                    OutlinedCard(
                        onClick =
                            hapticOnClick {
                              viewModel.setAnalysisLanguage("en")
                              showMoreLanguages = false
                            },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
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
                    OutlinedCard(
                        onClick = hapticOnClick { showMoreLanguages = !showMoreLanguages },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
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
                            // Chinese
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("zh-rTW")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("hi")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("es")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("ar")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("fr")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                            OutlinedCard(
                                onClick =
                                    hapticOnClick {
                                      viewModel.setAnalysisLanguage("ru")
                                      showMoreLanguages = false
                                    },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp),
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
                      OutlinedCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.BRIEF)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
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
                      OutlinedCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.DETAILED)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
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
                      OutlinedCard(
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
                          shape = RoundedCornerShape(12.dp),
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
                      OutlinedCard(
                          onClick =
                              hapticOnClick {
                                viewModel.setDetailLevel(LlmManager.DetailLevel.CUSTOM)
                              },
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
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
                // Exact header row from DetailScreen
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  IconButton(onClick = hapticOnClick {}) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                  }
                  Spacer(modifier = Modifier.weight(1f))
                  // The rendered share output button
                  IconButton(onClick = hapticOnClick {}, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Style, "Generate Share Card")
                  }
                  Spacer(modifier = Modifier.width(4.dp))
                  // The original screenshot share button
                  IconButton(onClick = hapticOnClick {}, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Share, "Share Original")
                  }
                  Spacer(modifier = Modifier.width(4.dp))
                  // Done pill
                  Surface(
                      shape = RoundedCornerShape(16.dp),
                      color = MaterialTheme.colorScheme.secondaryContainer,
                      modifier =
                          Modifier.clip(RoundedCornerShape(16.dp))
                              .combinedClickable(
                                  onDoubleClick = {},
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
                  Spacer(modifier = Modifier.width(8.dp))
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
                      FilterChip(
                          selected = true,
                          onClick = hapticOnClick {},
                          label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              Icon(
                                  if (isSortDescending) Icons.Rounded.South
                                  else Icons.Rounded.North,
                                  null,
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
                          shape = RoundedCornerShape(20.dp),
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
                      FilterChip(
                          selected = tag == "cat",
                          onClick = hapticOnClick {},
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
                                targetState = isGalleryStyle, label = "LayoutPreview") { isGallery
                                  ->
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
                                      animationSpec =
                                          androidx.compose.animation.core.spring(
                                              dampingRatio = 0.8f, stiffness = 300f),
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
                            FilterChip(
                                selected = true,
                                onClick = hapticOnClick { isAlbumRowVisible = !isAlbumRowVisible },
                                label = {
                                  Icon(
                                      if (isAlbumRowVisible) Icons.Rounded.KeyboardArrowDown
                                      else Icons.Rounded.KeyboardArrowUp,
                                      contentDescription = null,
                                      modifier = Modifier.size(18.dp))
                                },
                                shape = RoundedCornerShape(20.dp),
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
                                    androidx.compose.animation.expandVertically() +
                                        androidx.compose.animation.fadeIn(),
                                exit =
                                    androidx.compose.animation.shrinkVertically() +
                                        androidx.compose.animation.fadeOut()) {
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
                              animationSpec = tween(360, easing = FastOutSlowInEasing),
                              label = "OnboardingAlbumDrawerHeight")
                      val swipeCueOffset by
                          animateFloatAsState(
                              targetValue = if (isAlbumDrawerExpanded) 34f else 0f,
                              animationSpec = tween(360, easing = FastOutSlowInEasing),
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
                                                        size = 64.dp)
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
                                                size = 64.dp)
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
                        animationSpec = tween(520, easing = FastOutSlowInEasing),
                        label = "OnboardingAlbumGestureDraggedOffset")
                val pushedOffsetX by
                    animateFloatAsState(
                        targetValue = if (demoStep == 5) -itemTravelPx else 0f,
                        animationSpec = tween(520, easing = FastOutSlowInEasing),
                        label = "OnboardingAlbumGesturePushedOffset")
                val touchOffsetX by
                    animateFloatAsState(
                        targetValue =
                            touchStartOffsetPx + if (demoStep == 5) itemTravelPx else 0f,
                        animationSpec = tween(520, easing = FastOutSlowInEasing),
                        label = "OnboardingAlbumGestureTouchOffset")
                val touchScale by
                    animateFloatAsState(
                        targetValue = if (demoStep == 1 || demoStep == 3) 0.72f else 1f,
                        animationSpec = tween(90, easing = FastOutSlowInEasing),
                        label = "OnboardingAlbumGestureTouchScale")
                val touchAlpha by
                    animateFloatAsState(
                        targetValue = if (demoStep == 4) 0.55f else 1f,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
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
                                modifier =
                                    Modifier.graphicsLayer { translationX = pushedOffsetX },
                                isSelected = false,
                                isPinned = false,
                                size = 64.dp)
                            OnboardingAlbumThumbnailCard(
                                isSelected = false,
                                isPinned = false,
                                size = 64.dp)
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
                      // "Done" pill — exact copy from LegacyScreen / DetailScreen
                      Surface(
                          shape = RoundedCornerShape(16.dp),
                          color = MaterialTheme.colorScheme.secondaryContainer,
                          modifier = Modifier.clip(RoundedCornerShape(16.dp)),
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

                      Spacer(modifier = Modifier.height(12.dp))

                      // "Analyzing" pill
                      Surface(
                          shape = RoundedCornerShape(16.dp),
                          color = MaterialTheme.colorScheme.errorContainer,
                          modifier = Modifier.clip(RoundedCornerShape(16.dp)),
                      ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                          CircularProgressIndicator(
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

                      Spacer(modifier = Modifier.height(12.dp))

                      // "Pending" pill
                      Surface(
                          shape = RoundedCornerShape(16.dp),
                          color = MaterialTheme.colorScheme.errorContainer,
                          modifier = Modifier.clip(RoundedCornerShape(16.dp)),
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
                                animation = tween(1400, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse),
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
                        animationSpec = tween(durationMillis = 220),
                        label = "ExtraFeaturesSearchModeIconScale")
                val semanticGlowAlpha by
                    animateFloatAsState(
                        targetValue = if (isEmbeddingSearchMode) glowAlpha else 0f,
                        animationSpec = tween(durationMillis = 220),
                        label = "ExtraFeaturesSemanticGlowAlpha")

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
                        shape = RoundedCornerShape(22.dp),
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
              })

  // ── Page 12: Advanced Settings ──
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
              pages = listOf(allPages[12], allPages[15])),
      )
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val currentSectionIndex = listState.firstVisibleItemIndex.coerceAtMost(sections.lastIndex)
  val canScrollToNextSection = currentSectionIndex < sections.lastIndex

  val onboardingRuntimePermissions =
      remember {
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
          ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

    if (missingPermissions.isNotEmpty()) {
      permissionLauncher.launch(missingPermissions.toTypedArray())
    } else {
      viewModel.loadAlbums()
    }
  }

  Scaffold(
      bottomBar = {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .navigationBarsPadding()
                      .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    modifier = Modifier.size(48.dp)) {
                      Icon(
                          imageVector = Icons.Rounded.KeyboardArrowDown,
                          contentDescription = stringResource(R.string.onboarding_next))
                    }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = hapticOnClick(onFinish),
                    shape = RoundedCornerShape(20.dp),
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
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
              sections.forEach { section ->
                item { OnboardingSectionBlock(section = section) }
              }
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
              infiniteRepeatable(
                  animation = tween(900, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse),
          label = "CurrentAnalysisPulseAlpha")
  val tapScale by
      infiniteTransition.animateFloat(
          initialValue = 0.92f,
          targetValue = 1.08f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(780, easing = FastOutSlowInEasing),
                  repeatMode = RepeatMode.Reverse),
          label = "CurrentAnalysisTapScale")

  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Spacer(modifier = Modifier.weight(1f))
      Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {},
        ) {
          Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
                progress = { 0.62f },
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error,
                trackColor = MaterialTheme.colorScheme.errorContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                stringResource(R.string.items_left, "3"),
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
                              if (isCurrentImage)
                                  analysisGlowColor.copy(alpha = 0.82f * pulseAlpha)
                              else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                          shape = RoundedCornerShape(14.dp)),
          )
        }
      }
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
  BoxWithConstraints(modifier = modifier.fillMaxWidth().animateContentSize()) {
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
) {
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
                  .clip(RoundedCornerShape(12.dp))
                  .background(backgroundColor)
                  .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
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
            modifier =
                Modifier.align(Alignment.TopStart)
                    .size(18.dp),
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
                animationSpec = tween(durationMillis = 180),
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
          animationSpec = tween(durationMillis = 980, easing = FastOutSlowInEasing),
      )
      delay(1500L)
    }
  }

  val progress = pullProgress.value.coerceIn(0f, 1f)
  val showSimilar = progress >= 0.98f
  val detailAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 0.62f else 1f,
          animationSpec = tween(durationMillis = 220),
          label = "LookSimilarDetailAlpha",
      )
  val loaderAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 0f else 1f,
          animationSpec = tween(durationMillis = 180),
          label = "LookSimilarLoaderAlpha",
      )
  val similarAlpha by
      animateFloatAsState(
          targetValue = if (showSimilar) 1f else 0f,
          animationSpec = tween(durationMillis = 220),
          label = "LookSimilarResultsAlpha",
      )
  val similarOffsetY by
      animateFloatAsState(
          targetValue = if (showSimilar) 0f else -18f,
          animationSpec = tween(durationMillis = 260),
          label = "LookSimilarResultsOffset",
      )
  val similarAreaHeight by
      animateDpAsState(
          targetValue = if (showSimilar) 126.dp else 68.dp,
          animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
          label = "LookSimilarAreaHeight",
      )

  Surface(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = 24.dp)
              .wrapContentHeight()
              .animateContentSize(),
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
