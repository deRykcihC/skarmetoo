package com.deryk.skarmetoo

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScreenshotViewModel,
    onStartScreenSaver: () -> Unit,
    logoRes: Int = R.drawable.app_logo,
) {
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val currentDetailLevel by viewModel.detailLevel.collectAsState()
    val customPrompt by viewModel.customPrompt.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val context = LocalContext.current

    val isDownloadingModel by viewModel.isDownloadingModel.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val isGemma3nDownloaded by viewModel.isGemma3nDownloaded.collectAsState()
    val isGemma4Downloaded by viewModel.isGemma4Downloaded.collectAsState()
    val downloadingModelType by viewModel.downloadingModelType.collectAsState()

    val modelPath = context.filesDir.absolutePath + "/" + selectedModel.fileName

    val gemma3nUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"
    val gemma4Url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    var showHfLogin by remember { mutableStateOf(false) }
    var hfLoginModelType by remember { mutableStateOf(ModelType.GEMMA_3N) }
    var showManageFoldersDialog by remember { mutableStateOf(false) }
    var isHfLoggedIn by remember { mutableStateOf(false) }

    val isModelFound by viewModel.isModelFound.collectAsState()
    val sourceFolders by viewModel.sourceFolders.collectAsState()
    val folderImageCounts by viewModel.folderImageCounts.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkModelExists()
        val cookies = android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
        isHfLoggedIn = !cookies.isNullOrEmpty()
    }

    val totalImages = entries.size
    val analyzedImages = entries.count { it.summary.isNotBlank() }

    // Native folder picker — opens Android file explorer to pick a folder
    val folderPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            treeUri?.let { viewModel.loadImagesFromFolder(it) }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
            IconButton(onClick = {
                val nextLang =
                    when (currentLanguage) {
                        "en" -> "zh-rTW"
                        else -> "en"
                    }
                viewModel.setAppLanguage(nextLang)
            }) {
                Icon(Icons.Rounded.Language, contentDescription = "Language")
            }
        }

        // ===== Stats cards row =====
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.analyzed),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1565C0),
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$analyzedImages",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1565C0),
                    )
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.total_images),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF57F17),
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$totalImages",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF57F17),
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFF3E0),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = Color(0xFFE65100), modifier = Modifier.size(22.dp))
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
                            stringResource(R.string.folders_selected, sourceFolders.size.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (folderImageCounts.isNotEmpty()) {
                    val chartColors =
                        listOf(
                            Color(0xFF5E35B1),
                            Color(0xFF1E88E5),
                            Color(0xFF43A047),
                            Color(0xFFFDD835),
                            Color(0xFFFB8C00),
                            Color(0xFFE53935),
                            Color(0xFF8E24AA),
                            Color(0xFF00ACC1),
                            Color(0xFF7CB342),
                        )

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp, top = 4.dp),
                    ) {
                        val totalInMap = folderImageCounts.values.sum().coerceAtLeast(1)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                        ) {
                            folderImageCounts.entries.forEachIndexed { index, entry ->
                                val weight = entry.value.toFloat()
                                if (weight > 0) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .weight(weight)
                                                .fillMaxHeight()
                                                .background(chartColors[index % chartColors.size]),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            folderImageCounts.entries.forEachIndexed { index, entry ->
                                val folderName =
                                    try {
                                        android.net.Uri.decode(
                                            android.net.Uri.parse(entry.key).lastPathSegment?.substringAfterLast(":") ?: "Folder",
                                        )
                                    } catch (_: Exception) {
                                        "Folder"
                                    }
                                val percentage = (entry.value.toFloat() / totalInMap * 100).toInt()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(chartColors[index % chartColors.size]),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$folderName ($percentage%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showManageFoldersDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        enabled = sourceFolders.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.manage_folders), fontWeight = FontWeight.Medium)
                    }

                    FilledTonalButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
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
                        Text(stringResource(R.string.add_folder), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== AI Model card (merged with status) =====
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (isModelReady) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Rounded.Psychology,
                                null,
                                tint = if (isModelReady) Color(0xFF2E7D32) else Color(0xFFE65100),
                                modifier = Modifier.size(22.dp),
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val statusText =
                        when {
                            !isModelFound && !isDownloadingModel -> stringResource(R.string.status_no_model)
                            isModelReady -> stringResource(R.string.ready)
                            modelStatus.contains(
                                "Loading",
                            ) || modelStatus.contains("Downloading") -> stringResource(R.string.status_loading)
                            else -> stringResource(R.string.status_offline)
                        }
                    val statusBg =
                        when {
                            isModelReady -> Color(0xFFE8F5E9)
                            modelStatus.contains("Loading") || modelStatus.contains("Downloading") -> Color(0xFFFFF3E0)
                            !isModelFound && !isDownloadingModel -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    val statusColor =
                        when {
                            isModelReady -> Color(0xFF2E7D32)
                            modelStatus.contains("Loading") || modelStatus.contains("Downloading") -> Color(0xFFE65100)
                            !isModelFound && !isDownloadingModel -> MaterialTheme.colorScheme.onErrorContainer
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

                val isGemma3nSelected = selectedModel == ModelType.GEMMA_3N
                val isDownloadingGemma3n = isDownloadingModel && downloadingModelType == ModelType.GEMMA_3N
                OutlinedCard(
                    onClick = {
                        viewModel.setSelectedModel(ModelType.GEMMA_3N)
                        if (isGemma3nDownloaded) {
                            val path = context.filesDir.absolutePath + "/" + ModelType.GEMMA_3N.fileName
                            viewModel.initializeModel(path, isGemma4 = false)
                        } else if (!isDownloadingModel) {
                            hfLoginModelType = ModelType.GEMMA_3N
                            showHfLogin = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isGemma3nSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    border = if (isGemma3nSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_gemma3n),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isGemma3nSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.model_gemma3n_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDownloadingGemma3n) Color(0xFFFFF3E0) else if (isGemma3nDownloaded) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (isDownloadingGemma3n) {
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100),
                                    )
                                } else if (isGemma3nDownloaded) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Downloaded",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Download Model",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val isGemma4Selected = selectedModel == ModelType.GEMMA_4
                val isDownloadingGemma4 = isDownloadingModel && downloadingModelType == ModelType.GEMMA_4
                OutlinedCard(
                    onClick = {
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
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isGemma4Selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    border = if (isGemma4Selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.model_gemma4),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isGemma4Selected) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.model_gemma4_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isDownloadingGemma4) Color(0xFFFFF3E0) else if (isGemma4Downloaded) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (isDownloadingGemma4) {
                                    Text(
                                        text = "${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100),
                                    )
                                } else if (isGemma4Downloaded) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Downloaded",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Download Model",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Analysis Language Row
                Text(
                    stringResource(R.string.language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                val analysisLang by viewModel.analysisLanguage.collectAsState()
                var showMoreLanguages by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.language_en),
                        selected = analysisLang == "en",
                        onClick = { 
                            viewModel.setAnalysisLanguage("en")
                            showMoreLanguages = false
                        },
                    )
                    DetailLevelCard(
                        modifier = Modifier.weight(1f),
                        label = if (analysisLang != "en" && !showMoreLanguages) {
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
                        onClick = { showMoreLanguages = !showMoreLanguages },
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showMoreLanguages,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_zh), selected = analysisLang == "zh-rTW", onClick = { viewModel.setAnalysisLanguage("zh-rTW"); showMoreLanguages = false })
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_hi), selected = analysisLang == "hi", onClick = { viewModel.setAnalysisLanguage("hi"); showMoreLanguages = false })
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_es), selected = analysisLang == "es", onClick = { viewModel.setAnalysisLanguage("es"); showMoreLanguages = false })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_ar), selected = analysisLang == "ar", onClick = { viewModel.setAnalysisLanguage("ar"); showMoreLanguages = false })
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_fr), selected = analysisLang == "fr", onClick = { viewModel.setAnalysisLanguage("fr"); showMoreLanguages = false })
                            DetailLevelCard(modifier = Modifier.weight(1f), label = stringResource(R.string.language_ru), selected = analysisLang == "ru", onClick = { viewModel.setAnalysisLanguage("ru"); showMoreLanguages = false })
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                onClick = { viewModel.setDetailLevel(level) },
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
                                onClick = { viewModel.setDetailLevel(level) },
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

                if (currentDetailLevel == LlmManager.DetailLevel.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { viewModel.setCustomPrompt(it) },
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
                    )
                }


            }
        }

        if (showHfLogin) {
            val loginRepoUrl = when (hfLoginModelType) {
                ModelType.GEMMA_3N -> "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"
                ModelType.GEMMA_4 -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
            }
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    showHfLogin = false
                    val cookies = android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
                    isHfLoggedIn = !cookies.isNullOrEmpty()
                    if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
                        val url = if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl else gemma4Url
                        viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
                    }
                },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
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
                            IconButton(onClick = {
                                showHfLogin = false
                                val cookies = android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
                                isHfLoggedIn = !cookies.isNullOrEmpty()
                                if (!cookies.isNullOrEmpty() && !isDownloadingModel) {
                                    val url = if (hfLoginModelType == ModelType.GEMMA_3N) gemma3nUrl else gemma4Url
                                    viewModel.downloadModel(url, "", cookies, false, hfLoginModelType)
                                }
                            }) {
                                Icon(Icons.Rounded.Close, "Close")
                            }
                        }

                        Text(
                            text = "Please log in and optionally accept the model's license agreement. Once done, close this window to begin downloading.",
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
        Spacer(modifier = Modifier.height(16.dp))

        // ===== Screen Saver Card =====
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE8EAF6),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.Monitor, null, tint = Color(0xFF3F51B5), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.screen_saver),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.to_save_battery_while_analyzing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onStartScreenSaver,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.launch_screen_saver))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ===== Import/Export Card =====
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE0F7FA),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.SwapHoriz, null, tint = Color(0xFF00695C), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.import_export),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.transfer_metadata_between_devices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = false,
                    ) {
                        Icon(Icons.Rounded.Upload, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.export))
                    }
                    FilledTonalButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = false,
                    ) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.import_btn))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buy Me a Coffee
        val uriHandler = LocalUriHandler.current
        Surface(
            onClick = { uriHandler.openUri("https://buymeacoffee.com/derykcihc") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        shadowElevation = 6.dp.toPx()
                        shape = RoundedCornerShape(24.dp)
                        clip = true
                        spotShadowColor = Color(0xFFFFDD00).copy(alpha = 0.5f)
                        ambientShadowColor = Color(0xFFFFDD00).copy(alpha = 0.5f)
                    },
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFFFDD00), // BMC Yellow
            shadowElevation = 6.dp,
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

        val annotatedString =
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(stringResource(R.string.have_bugs_text))
                }
                pushStringAnnotation(tag = "github", annotation = "https://github.com/deRykcihC/skarmetoo")
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
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
                style = MaterialTheme.typography.bodySmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "github", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy Policy Link
        TextButton(
            onClick = { uriHandler.openUri("https://github.com/deRykcihC/skarmetoo/blob/master/PRIVACY_POLICY.md") },
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

    if (showManageFoldersDialog) {
        val dlgTitle = stringResource(R.string.manage_source_folders)
        val dlgNoFolders = stringResource(R.string.no_folders_selected)
        val dlgFallback = stringResource(R.string.meta_file)
        val dlgRemove = stringResource(R.string.remove)
        val dlgClearAll = stringResource(R.string.clear_all)
        val dlgDone = stringResource(R.string.done)

        AlertDialog(
            onDismissRequest = { showManageFoldersDialog = false },
            title = {
                Text(
                    text = dlgTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (sourceFolders.isEmpty()) {
                        Text(
                            text = dlgNoFolders,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sourceFolders.forEach { uriStr ->
                            val folderName =
                                try {
                                    android.net.Uri.decode(
                                        android.net.Uri.parse(uriStr).lastPathSegment?.substringAfterLast(":") ?: dlgFallback,
                                    )
                                } catch (_: Exception) {
                                    dlgFallback
                                }
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = folderName,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    IconButton(onClick = {
                                        viewModel.removeSourceFolder(uriStr)
                                        if (sourceFolders.size <= 1) showManageFoldersDialog = false
                                    }) {
                                        Icon(Icons.Rounded.Close, contentDescription = dlgRemove, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (sourceFolders.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.clearSourceFolders()
                                showManageFoldersDialog = false
                            },
                            colors =
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        ) {
                            Text(dlgClearAll)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { showManageFoldersDialog = false }) {
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
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
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
