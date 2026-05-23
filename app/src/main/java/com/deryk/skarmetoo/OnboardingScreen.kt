package com.deryk.skarmetoo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val content: @Composable () -> Unit
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

  var showMoreModels by remember { mutableStateOf(false) }
  val ggufManager = remember { GgufLlmManager.getInstance(context) }
  val isGgufDownloading by ggufManager.isDownloading.collectAsState()
  val ggufDownloadProgress by ggufManager.downloadProgress.collectAsState()
  val ggufDownloadingModelName by ggufManager.downloadingModelName.collectAsState()
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
              description = stringResource(R.string.onboarding_page0_desc)) {
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
                        FlowRow {
                          Text(
                              stringResource(R.string.model_gemma4),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight =
                                  if (isGemma4Selected) FontWeight.Bold else FontWeight.Medium,
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
                        onClick = hapticOnClick {},
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = stringResource(R.string.language_en),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                    // "More" not selected
                    OutlinedCard(
                        onClick = hapticOnClick {},
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        border = CardDefaults.outlinedCardBorder(),
                    ) {
                      Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = stringResource(R.string.language_more),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                      // Brief — not selected
                      OutlinedCard(
                          onClick = hapticOnClick {},
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor = Color.Transparent,
                                  contentColor = MaterialTheme.colorScheme.onSurface,
                              ),
                          border = CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.brief),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                      // Detailed — selected
                      OutlinedCard(
                          onClick = hapticOnClick {},
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                              ),
                          border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.detailed),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Bold,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      // Comprehensive — not selected
                      OutlinedCard(
                          onClick = hapticOnClick {},
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor = Color.Transparent,
                                  contentColor = MaterialTheme.colorScheme.onSurface,
                              ),
                          border = CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.full),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Medium,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis)
                            }
                      }
                      // Custom — not selected
                      OutlinedCard(
                          onClick = hapticOnClick {},
                          modifier = Modifier.weight(1f).height(52.dp),
                          shape = RoundedCornerShape(12.dp),
                          colors =
                              CardDefaults.outlinedCardColors(
                                  containerColor = Color.Transparent,
                                  contentColor = MaterialTheme.colorScheme.onSurface,
                              ),
                          border = CardDefaults.outlinedCardBorder(),
                      ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()) {
                              Text(
                                  stringResource(R.string.custom),
                                  style = MaterialTheme.typography.labelLarge,
                                  fontWeight = FontWeight.Medium,
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
              description = stringResource(R.string.onboarding_page4_desc)) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)) {
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

                      Spacer(modifier = Modifier.height(14.dp))

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
                  Text(
                      stringResource(R.string.details_title),
                      style = MaterialTheme.typography.titleLarge,
                      fontWeight = FontWeight.SemiBold,
                  )
                  Spacer(modifier = Modifier.weight(1f))
                  // The share button
                  IconButton(onClick = hapticOnClick {}) { Icon(Icons.Rounded.Share, "Share") }
                  Spacer(modifier = Modifier.width(4.dp))
                  // Done pill
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
                  Spacer(modifier = Modifier.width(8.dp))
                }
              },

          // ── Page 7: Search & Filter ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page7_title),
              description = stringResource(R.string.onboarding_page7_desc)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                  // Search pill + sort/filter tags — matching the GalleryScreen layout
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

          // ── Page 9: Reanalyze ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page9_title),
              description = stringResource(R.string.onboarding_page9_desc)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                      // "Done" pill — exact copy from GalleryScreen / DetailScreen
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
              })

  // ── Page 10: Advanced Settings ──
  val advancedSettingsPage =
      OnboardingPage(
          title = stringResource(R.string.onboarding_page10_title),
          description = stringResource(R.string.onboarding_page10_desc)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                shape = RoundedCornerShape(20.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
              Column(modifier = Modifier.padding(20.dp)) {
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
                Spacer(modifier = Modifier.height(16.dp))

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
                  Spacer(modifier = Modifier.height(8.dp))
                  Slider(
                      value = 0.5f,
                      onValueChange = {},
                      enabled = false,
                  )
                }
                Spacer(modifier = Modifier.height(8.dp))

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

  val pagerState = rememberPagerState(pageCount = { allPages.size })
  val coroutineScope = rememberCoroutineScope()

  val mediaPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Permission result handled — the ViewModel will pick up albums automatically
      }

  LaunchedEffect(pagerState.currentPage) {
    // Index 4 is "Select Image Folders"
    if (pagerState.currentPage == 4) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) !=
            PackageManager.PERMISSION_GRANTED) {
          mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
      }
    }
  }

  Scaffold(
      bottomBar = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
              Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.padding(start = 16.dp)) {
                    repeat(allPages.size) { iteration ->
                      val color =
                          if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

                      Box(
                          modifier =
                              Modifier.clip(CircleShape)
                                  .background(color)
                                  .size(if (pagerState.currentPage == iteration) 12.dp else 8.dp))
                    }
                  }

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedVisibility(visible = pagerState.currentPage < allPages.size - 1) {
                  TextButton(onClick = hapticOnClick(onFinish)) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }

                Button(
                    onClick =
                        hapticOnClick {
                          if (pagerState.currentPage < allPages.size - 1) {
                            coroutineScope.launch {
                              pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                          } else {
                            onFinish()
                          }
                        },
                    shape =
                        if (pagerState.currentPage == allPages.size - 1) RoundedCornerShape(20.dp)
                        else CircleShape,
                    modifier =
                        if (pagerState.currentPage == allPages.size - 1) Modifier.height(48.dp)
                        else Modifier.size(48.dp),
                    contentPadding =
                        if (pagerState.currentPage == allPages.size - 1)
                            ButtonDefaults.ContentPadding
                        else PaddingValues(0.dp)) {
                      if (pagerState.currentPage == allPages.size - 1) {
                        Text(
                            text = stringResource(R.string.onboarding_get_started),
                            fontWeight = FontWeight.Bold)
                      } else {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "Next",
                            modifier = Modifier.size(28.dp))
                      }
                    }
              }
            }
      }) { innerPadding ->
        HorizontalPager(
            state = pagerState, modifier = Modifier.fillMaxSize().padding(innerPadding)) { pageIndex
              ->
              val page = allPages[pageIndex]

              Column(
                  modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center) {
                    // The real UI element preview
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                          Box(
                              contentAlignment = Alignment.Center,
                              modifier = Modifier.padding(vertical = 24.dp)) {
                                page.content()
                              }
                        }

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp))

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = page.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 32.dp))
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
