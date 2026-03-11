package com.deryk.skarmetoo

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.Image

// =============================================
// SETTINGS SCREEN
// =============================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScreenshotViewModel, 
    onStartScreenSaver: () -> Unit,
    logoRes: Int = R.drawable.app_logo
) {
    val isModelReady by viewModel.isModelReady.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val currentDetailLevel by viewModel.detailLevel.collectAsState()
    val customPrompt by viewModel.customPrompt.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val context = LocalContext.current

    val modelPath = context.filesDir.absolutePath + "/gemma-3n-E2B-it-int4.litertlm"
    
    var useGpu by remember { mutableStateOf(false) }
    
    val isDownloadingModel by viewModel.isDownloadingModel.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    var downloadUrl by remember { mutableStateOf("https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm") }
    var showHfLogin by remember { mutableStateOf(false) }
    var showManageFoldersDialog by remember { mutableStateOf(false) }

    val isModelFound by viewModel.isModelFound.collectAsState()
    val sourceFolders by viewModel.sourceFolders.collectAsState()
    val folderImageCounts by viewModel.folderImageCounts.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkModelExists()
    }

    val totalImages = entries.size
    val analyzedImages = entries.count { it.summary.isNotBlank() }

    // Native folder picker — opens Android file explorer to pick a folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { viewModel.loadImagesFromFolder(it) }
    }



    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        // ===== Header: emoji + Settings =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = "Logo",
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        // ===== Stats cards row =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ANALYZED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$analyzedImages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("TOTAL IMAGES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF57F17), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$totalImages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF57F17))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ===== Section label =====
        Text(
            "APP SETTINGS",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ===== Source Folder Card =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFF3E0),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.FolderOpen, null, tint = Color(0xFFE65100), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Source Folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${sourceFolders.size} folder(s) selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (folderImageCounts.isNotEmpty()) {
                    val chartColors = listOf(
                        Color(0xFF5E35B1), Color(0xFF1E88E5), Color(0xFF43A047), 
                        Color(0xFFFDD835), Color(0xFFFB8C00), Color(0xFFE53935),
                        Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFF7CB342)
                    )
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp, top = 4.dp)
                    ) {
                        val totalInMap = folderImageCounts.values.sum().coerceAtLeast(1)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            folderImageCounts.entries.forEachIndexed { index, entry ->
                                val weight = entry.value.toFloat()
                                if (weight > 0) {
                                    Box(
                                        modifier = Modifier
                                            .weight(weight)
                                            .fillMaxHeight()
                                            .background(chartColors[index % chartColors.size])
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            folderImageCounts.entries.forEachIndexed { index, entry ->
                                val folderName = try {
                                    android.net.Uri.decode(android.net.Uri.parse(entry.key).lastPathSegment?.substringAfterLast(":") ?: "Folder")
                                } catch (_: Exception) {
                                    "Folder"
                                }
                                val percentage = (entry.value.toFloat() / totalInMap * 100).toInt()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(chartColors[index % chartColors.size])
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$folderName ($percentage%)", 
                                        style = MaterialTheme.typography.bodySmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showManageFoldersDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        enabled = sourceFolders.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manage Folders", fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Folder", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== AI Model card (merged with status) =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (isModelReady) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Rounded.Psychology, null,
                                tint = if (isModelReady) Color(0xFF2E7D32) else Color(0xFFE65100),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("On-device analysis model", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val statusText = when {
                        !isModelFound && !isDownloadingModel -> "No model"
                        isModelReady -> "Ready"
                        modelStatus.contains("Loading") || modelStatus.contains("Downloading") -> "Loading..."
                        else -> "Offline"
                    }
                    val statusBg = when (statusText) {
                        "Ready" -> Color(0xFFE8F5E9)
                        "Loading..." -> Color(0xFFFFF3E0)
                        "No model" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val statusColor = when (statusText) {
                        "Ready" -> Color(0xFF2E7D32)
                        "Loading..." -> Color(0xFFE65100)
                        "No model" -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusBg
                    ) {
                        Text(
                            text = statusText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Acceleration & Load Row
                Text("Acceleration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.initializeModel(modelPath, useGpu = useGpu) },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(48.dp),
                        enabled = isModelFound
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load", fontWeight = FontWeight.SemiBold)
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f).height(48.dp)) {
                        SegmentedButton(
                            selected = !useGpu,
                            onClick = { useGpu = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Text("CPU", style = MaterialTheme.typography.labelMedium)
                        }
                        SegmentedButton(
                            selected = useGpu,
                            onClick = { useGpu = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Text("GPU", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Content Analysis Depth
                Text("Analysis Detail", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    LlmManager.DetailLevel.entries.forEachIndexed { index, level ->
                        SegmentedButton(
                            selected = currentDetailLevel == level,
                            onClick = { viewModel.setDetailLevel(level) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = LlmManager.DetailLevel.entries.size)
                        ) {
                            Text(
                                text = if (level.label == "Comprehensive") "Full" else level.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (currentDetailLevel) {
                        LlmManager.DetailLevel.BRIEF -> "Quick one-sentence summary. (Fastest generation)"
                        LlmManager.DetailLevel.DETAILED -> "A few sentences covering text, UI elements, and context. (Balanced speed)"
                        LlmManager.DetailLevel.COMPREHENSIVE -> "Full uncensored description of everything visible. (Slowest generation)"
                        LlmManager.DetailLevel.CUSTOM -> "Provide your own custom prompt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                if (currentDetailLevel == LlmManager.DetailLevel.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { viewModel.setCustomPrompt(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom Prompt Instruction") },
                        placeholder = { Text("e.g. Find all names in this image") },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Download Section Integrated
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Model (Gemma 3n)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showHfLogin = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.AccountCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sign In", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = {
                            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://huggingface.co")
                            viewModel.downloadModel(downloadUrl, "", cookies, useGpu)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        enabled = !isDownloadingModel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (isDownloadingModel) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${(downloadProgress * 100).toInt()}% downloaded...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showHfLogin) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showHfLogin = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hugging Face Authorization",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showHfLogin = false }) {
                                Icon(Icons.Rounded.Close, "Close")
                            }
                        }
                        
                        Text(
                            text = "Please log in and optionally accept the model's license agreement. Once done, close this window to begin downloading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider()
                        
                        @SuppressLint("SetJavaScriptEnabled")
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { context ->
                                android.webkit.WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = android.webkit.WebViewClient()
                                    // Load the base repository URL so user can accept the license
                                    val repoUrl = downloadUrl.substringBefore("/resolve/").ifEmpty { "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm" }
                                    loadUrl(repoUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize().weight(1f)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))



        // ===== Screen Saver Card =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE8EAF6),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.Monitor, null, tint = Color(0xFF3F51B5), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Screen Saver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("To save battery while analyzing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onStartScreenSaver,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Launch Screen Saver")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ===== Import/Export Card =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE0F7FA),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.SwapHoriz, null, tint = Color(0xFF00695C), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Import / Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Transfer metadata between devices", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = false
                    ) {
                        Icon(Icons.Rounded.Upload, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export")
                    }
                    FilledTonalButton(
                        onClick = { },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = false
                    ) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import")
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buy Me a Coffee
        val uriHandler = LocalUriHandler.current
        Surface(
            onClick = { uriHandler.openUri("https://buymeacoffee.com/derykcihc") },
            modifier = Modifier
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
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Coffee,
                    contentDescription = null,
                    tint = Color(0xFF0D1B2A),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Buy me a coffee",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0D1B2A)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "If you appreciate the idea and want to help keep the app available on the Play Store, you can donate via Buy Me a Coffee. Otherwise, it's always free on GitHub!",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 32.dp),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = buildAnnotatedString {
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("Have bugs, feedback, or suggestions?\nLeave it in the ")
                }
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                    append("GitHub repository!")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable {
                    uriHandler.openUri("https://github.com/deRykcihC/skarmetoo")
                }
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // Privacy Policy Link
        TextButton(
            onClick = { uriHandler.openUri("https://github.com/deRykcihC/skarmetoo/blob/master/PRIVACY_POLICY.md") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                "Privacy Policy",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showManageFoldersDialog) {
        AlertDialog(
            onDismissRequest = { showManageFoldersDialog = false },
            title = { 
                Text(
                    text = "Manage Source Folders",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (sourceFolders.isEmpty()) {
                        Text(
                            text = "No folders currently selected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        sourceFolders.forEach { uriStr ->
                            val folderName = try {
                                android.net.Uri.decode(android.net.Uri.parse(uriStr).lastPathSegment?.substringAfterLast(":") ?: "Folder")
                            } catch (_: Exception) {
                                "Folder"
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = folderName,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    IconButton(onClick = { 
                                        viewModel.removeSourceFolder(uriStr) 
                                        if (sourceFolders.size <= 1) showManageFoldersDialog = false
                                    }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (sourceFolders.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                viewModel.clearSourceFolders()
                                showManageFoldersDialog = false
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text("Clear All")
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Button(onClick = { showManageFoldersDialog = false }) {
                        Text("Done")
                    }
                }
            }
        )
    }
}
