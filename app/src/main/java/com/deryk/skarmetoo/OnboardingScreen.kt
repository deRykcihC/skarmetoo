package com.deryk.skarmetoo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.ui.theme.LocalIsDarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
    val content: @Composable () -> Unit
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(viewModel: ScreenshotViewModel, onFinish: () -> Unit) {
  val isDark = LocalIsDarkMode.current
  val pages =
      listOf(
          // ── Page 0: Download AI Model ──
          OnboardingPage(
              title = stringResource(R.string.select_model),
              description = stringResource(R.string.onboarding_page0_desc)) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                  // Gemma 3n card (not selected state)
                  OutlinedCard(
                      onClick = {},
                      modifier = Modifier.fillMaxWidth(),
                      shape = RoundedCornerShape(14.dp),
                      colors =
                          CardDefaults.outlinedCardColors(
                              containerColor = Color.Transparent,
                          ),
                      border = CardDefaults.outlinedCardBorder(),
                  ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.model_gemma3n),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.model_gemma3n_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Surface(
                          shape = RoundedCornerShape(8.dp),
                          color = MaterialTheme.colorScheme.surfaceContainerHighest,
                          modifier = Modifier.size(32.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
                                  Icon(
                                      imageVector = Icons.Rounded.Download,
                                      contentDescription = "Download Model",
                                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                      modifier = Modifier.size(20.dp))
                                }
                          }
                    }
                  }

                  Spacer(modifier = Modifier.height(8.dp))

                  // Gemma 4 card (selected state)
                  OutlinedCard(
                      onClick = {},
                      modifier = Modifier.fillMaxWidth(),
                      shape = RoundedCornerShape(14.dp),
                      colors =
                          CardDefaults.outlinedCardColors(
                              containerColor = MaterialTheme.colorScheme.secondaryContainer,
                          ),
                      border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                  ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.model_gemma4),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.model_gemma4_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Surface(
                          shape = RoundedCornerShape(8.dp),
                          color = MaterialTheme.colorScheme.surfaceContainerHighest,
                          modifier = Modifier.size(32.dp)) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()) {
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
                            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                              Icon(
                                  Icons.Rounded.MenuBook,
                                  contentDescription = "Tutorial",
                                  modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { viewModel.setDarkMode(!isDark) },
                                modifier = Modifier.size(34.dp)) {
                                  Icon(
                                      if (isDark) Icons.Rounded.LightMode
                                      else Icons.Rounded.DarkMode,
                                      contentDescription = "Toggle Dark Mode",
                                      modifier = Modifier.size(20.dp))
                                }
                            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                              Icon(
                                  Icons.Rounded.Monitor,
                                  contentDescription = "Screen Saver",
                                  modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
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
                        onClick = {},
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
                        onClick = {},
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
                          onClick = {},
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
                          onClick = {},
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
                          onClick = {},
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
                          onClick = {},
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
                FilledTonalButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                ) {
                  Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(stringResource(R.string.add_media_folder), fontWeight = FontWeight.Medium)
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
                  IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                  Text(
                      stringResource(R.string.details_title),
                      style = MaterialTheme.typography.titleLarge,
                      fontWeight = FontWeight.SemiBold,
                  )
                  Spacer(modifier = Modifier.weight(1f))
                  // The share button
                  IconButton(onClick = {}) { Icon(Icons.Rounded.Share, "Share") }
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

          // ── Page 7: Search Images ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page7_title),
              description = stringResource(R.string.onboarding_page7_desc)) {
                // Exact search bar from GalleryScreen
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    readOnly = true,
                    leadingIcon = {
                      Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.outline)
                    },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor =
                                MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor =
                                MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                )
              },

          // ── Page 8: Filter Pills ──
          OnboardingPage(
              title = stringResource(R.string.onboarding_page8_title),
              description = stringResource(R.string.onboarding_page8_desc)) {
                // Exact filter chips from GalleryScreen — sort chip cycles automatically
                var isSortDescending by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                  while (true) {
                    delay(800L)
                    isSortDescending = !isSortDescending
                  }
                }
                LazyRow(
                    modifier = Modifier.padding(vertical = 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  item {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isSortDescending) Icons.Rounded.South else Icons.Rounded.North,
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
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedLeadingIconColor =
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    )
                  }
                  items(listOf("cat", "indoor", "selfie", "food")) { tag ->
                    FilterChip(
                        selected = tag == "cat",
                        onClick = {},
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
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
  val context = LocalContext.current

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
                  TextButton(onClick = onFinish) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }

                Button(
                    onClick = {
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
                        tonalElevation = 1.dp,
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
}
