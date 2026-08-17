package com.deryk.skarmetoo.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.jetbrains.skiko.hostOs

private val MiSans = FontFamily(
    Font("fonts/misans_regular.ttf", FontWeight.Normal),
    Font("fonts/misans_medium.ttf", FontWeight.Medium),
    Font("fonts/misans_bold.ttf", FontWeight.Bold),
)

private val SkarmetooTypography = Typography(
    headlineSmall = Typography().headlineSmall.copy(fontFamily = MiSans, fontWeight = FontWeight.Bold),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = MiSans, fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontFamily = MiSans),
    titleMedium = Typography().titleMedium.copy(fontFamily = MiSans),
    titleSmall = Typography().titleSmall.copy(fontFamily = MiSans),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = MiSans),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = MiSans),
    bodySmall = Typography().bodySmall.copy(fontFamily = MiSans),
    labelLarge = Typography().labelLarge.copy(fontFamily = MiSans),
    labelMedium = Typography().labelMedium.copy(fontFamily = MiSans),
    labelSmall = Typography().labelSmall.copy(fontFamily = MiSans),
)

private val SkarmetooLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    surface = Color(0xFFFDF8FF),
    surfaceVariant = Color(0xFFE7E0EC),
    surfaceContainerLow = Color(0xFFF7F2FA),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454F),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(0.dp),
        content = { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
    )
}

@Composable
private fun StatusPill(connected: Boolean, busy: Boolean, text: String) {
    val bg = when {
        connected -> Color(0xFFE8F5E9)
        busy -> Color(0xFFFFF3E0)
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val fg = when {
        connected -> Color(0xFF2E7D32)
        busy -> Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(12.dp), color = bg) {
        Text(
            text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg
        )
    }
}

private data class UiProgress(val sent: Int, val processed: Int, val total: Int, val failed: Int)

@Composable
private fun ProgressCard(progress: UiProgress?) {
    SectionCard {
        Text("Progress", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val proc = progress?.processed ?: 0
        val tot = progress?.total ?: 0
        val pct = if (tot > 0) (proc * 100 / tot).coerceIn(0, 100) else 0
        LinearProgressIndicator(
            progress = { if (tot == 0) 0f else proc.toFloat() / tot },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(28.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(if (tot == 0) "Idle" else "$pct%  •  $proc / $tot", style = MaterialTheme.typography.bodySmall)
    }
}

private fun JobInfo.toUiProgress() = UiProgress(sent = sent, processed = processed, total = total, failed = failed)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkarmetooDesktopApp() {
    val scope = rememberCoroutineScope()
    var lmUrl by remember { mutableStateOf("http://127.0.0.1:1234/v1") }
    var mobileIp by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<String>()) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected") }
    var busy by remember { mutableStateOf(false) }
    var lmConnected: Boolean? by remember { mutableStateOf(null) }
    var lmChecking by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf("Ready. Connect to your phone to start.")) }
    var progress by remember { mutableStateOf<UiProgress?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    val isEasterEgg = remember { Random.nextFloat() < 0.069f }
    val logoRes = if (isEasterEgg) "images/app_logo_rainbow.png" else "images/app_logo.png"

    val client = remember { LanClient() }
    val lmClient = remember(lmUrl) { LmStudioClient(lmUrl) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    fun appendLog(s: String) {
        logLines = (logLines + s).takeLast(600)
        scope.launch {
            runCatching {
                val last = logLines.lastIndex
                if (last >= 0) {
                    val count = listState.layoutInfo.totalItemsCount
                    val target = if (count > 0) last.coerceIn(0, count - 1) else last
                    listState.animateScrollToItem(target)
                }
            }
        }
    }

    MaterialTheme(colorScheme = SkarmetooLight, typography = SkarmetooTypography) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showGuide) {
                        IconButton(onClick = { showGuide = false }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Image(painterResource(logoRes), contentDescription = "Skarmetoo", modifier = Modifier.size(36.dp))
                    if (!showGuide) {
                        Text("Skarmetoo Desktop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!showGuide) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                            IconButton(onClick = { showGuide = true }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.MenuBook, contentDescription = "Guide", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                if (showGuide) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionCard {
                            Text("Setup LMStudio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("1. Install LMStudio: https://lmstudio.ai", style = MaterialTheme.typography.bodySmall)
                                Text("2. Download a vision-capable model — recommended: google/gemma-4-e2b (any quant). In Discover, confirm CAPABILITIES shows Vision and FORMAT is GGUF.", style = MaterialTheme.typography.bodySmall)
                                Text("Under Download Options choose GGUF → pick a quant: Q4_K_M (4.41 GB, balanced), Q6_K (4.83 GB), or Q8_0 (5.95 GB). Any Gemma 4 E2B quant works — choose by your RAM/VRAM.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Image(painterResource("images/guide_gemma_download.png"), contentDescription = "Gemma 4 E2B vision download options", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
                                Text("3. After downloading, open the Load tab. Set Context Length to 8192 (model supports up to 131072) and GPU Offload to match your hardware (e.g. 30 on a 8 GB VRAM machine). Lower GPU Offload if you hit VRAM limits; raise it for faster inference. Other settings can stay default unless you know your specs.", style = MaterialTheme.typography.bodySmall)
                                Image(painterResource("images/guide_lmstudio_load.png"), contentDescription = "LMStudio Load tab settings", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
                                Text("4. Load the model, then open Developer → Local Server and Start Server on http://127.0.0.1:1234/v1.", style = MaterialTheme.typography.bodySmall)
                                Text("5. In this app keep Endpoint as http://127.0.0.1:1234/v1, press Refresh, and choose the loaded vision model.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        SectionCard {
                            Text("Connect your phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("1. Put the phone and desktop on the same Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                            Text("2. On the phone: Skarmetoo → Settings → Desktop connection → enable the server.", style = MaterialTheme.typography.bodySmall)
                            Text("3. Copy the IP shown on the phone and paste it into Mobile IP.", style = MaterialTheme.typography.bodySmall)
                            Text("4. Press Connect.", style = MaterialTheme.typography.bodySmall)
                        }
                        SectionCard {
                            Text("Analyze images", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("1. Select your analysis backend as Desktop on the phone.", style = MaterialTheme.typography.bodySmall)
                            Text("2. Press Start Analysis on the desktop.", style = MaterialTheme.typography.bodySmall)
                            Text("3. The phone sends only pending images (newly discovered / not yet analyzed / Gemini Nano rejected).", style = MaterialTheme.typography.bodySmall)
                            Text("4. Watch Progress and Activity Log for real-time updates.", style = MaterialTheme.typography.bodySmall)
                        }
                        SectionCard {
                            Text("Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("• Images are transferred as JPEG (max 32 MiB). Keep both devices awake during processing.", style = MaterialTheme.typography.bodySmall)
                            Text("• Selected analysis language and detail level are taken from the phone.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val compactLayout = maxWidth < 900.dp || maxHeight < 620.dp
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("IP shown in Skarmetoo → Settings → Desktop connection") } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.Info, contentDescription = "Phone connection information", modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                StatusPill(connected = connected, busy = busy, text = if (connected) "Connected" else if (busy) "Connecting…" else "Disconnected")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = mobileIp, onValueChange = { mobileIp = it },
                                    label = { Text("Mobile IP") }, placeholder = { Text("192.168.1.20") },
                                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)
                                )
                                if (connected) {
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                client.close()
                                                withContext(Dispatchers.Main) { connected = false; status = "Disconnected"; progress = null; appendLog("Disconnected") }
                                            }
                                        },
                                        modifier = Modifier.padding(top = 6.dp).height(56.dp),
                                        shape = RoundedCornerShape(28.dp)
                                    ) { Text("Disconnect") }
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                withContext(Dispatchers.Main) { busy = true; status = "Connecting…" }
                                                runCatching { client.connect(mobileIp.trim()) }
                                                    .onSuccess { withContext(Dispatchers.Main) { connected = true; status = "Connected • ${mobileIp.trim()}"; appendLog("✓ Connected to ${mobileIp.trim()}") } }
                                                    .onFailure { e -> withContext(Dispatchers.Main) { status = "Connection failed"; appendLog("✗ ${e.message}") } }
                                                withContext(Dispatchers.Main) { busy = false }
                                            }
                                        },
                                        enabled = !busy && mobileIp.isNotBlank(),
                                        modifier = Modifier.padding(top = 6.dp).height(56.dp),
                                        shape = RoundedCornerShape(28.dp)
                                    ) { Text("Connect") }
                                }
                            }
                        }

                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Memory, null, tint = MaterialTheme.colorScheme.primary)
                                Text("LMStudio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("Local server, e.g. http://127.0.0.1:1234/v1, vision model required") } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(onClick = {}, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.Info, contentDescription = "LMStudio information", modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                StatusPill(
                                    connected = lmConnected == true,
                                    busy = lmChecking,
                                    text = when {
                                        lmChecking -> "Checking…"
                                        lmConnected == true -> "Connected"
                                        lmConnected == false -> "Disconnected"
                                        else -> "Not checked"
                                    },
                                )
                            }
                            OutlinedTextField(
                                value = lmUrl, onValueChange = { lmUrl = it },
                                label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().offset(y = (-4).dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            withContext(Dispatchers.Main) { busy = true; lmChecking = true }
                                            runCatching { LmStudioClient(lmUrl).models() }
                                                .onSuccess { names ->
                                                    withContext(Dispatchers.Main) {
                                                        lmConnected = true
                                                        models = names
                                                        if (selectedModel == null && names.isNotEmpty()) selectedModel = names.first()
                                                    }
                                                    appendLog("Found ${names.size} model(s): ${names.take(3).joinToString(", ")}${if (names.size > 3) "…" else ""}")
                                                }
                                                .onFailure { e ->
                                                    withContext(Dispatchers.Main) { lmConnected = false }
                                                    appendLog("LMStudio: ${e.message}")
                                                }
                                            withContext(Dispatchers.Main) { busy = false; lmChecking = false }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.padding(top = 6.dp).height(56.dp),
                                    shape = RoundedCornerShape(28.dp)
                                ) { Text("Refresh") }
                                if (models.isNotEmpty()) {
                                    var expanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                                        OutlinedTextField(
                                            value = selectedModel ?: "",
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("Model") },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                            modifier = Modifier.menuAnchor().weight(1f),
                                            shape = RoundedCornerShape(18.dp),
                                            singleLine = true,
                                            maxLines = 1,
                                        )
                                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            models.forEach { m ->
                                                DropdownMenuItem(text = { Text(m) }, onClick = { selectedModel = m; expanded = false })
                                            }
                                        }
                                    }
                                }
                            }
                            val isRunning = progress?.let { it.processed < it.total && it.total > 0 } == true && busy
                            if (isRunning) {
                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { client.cancelJob() }
                                            lmClient.cancelInFlight()
                                            analysisJob?.cancel(CancellationException("stop"))
                                            val st = runCatching { client.status() }.getOrNull()
                                            if (st != null) withContext(Dispatchers.Main) { progress = st.toUiProgress() }
                                            val curModel = selectedModel
                                            val unloaded = if (!curModel.isNullOrBlank()) runCatching { LmStudioClient(lmUrl).unloadModel(curModel) }.getOrDefault(false) else false
                                            withContext(Dispatchers.Main) { busy = false }
                                            appendLog(if (unloaded) "■ Stopped — model unloaded ($curModel)" else "■ Stopped — cancel sent${if (curModel != null) "; unload not confirmed ($curModel) — eject it in LMStudio" else ""}")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().requiredHeight(56.dp),
                                    shape = RoundedCornerShape(28.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                                ) { Text("Stop Analysis") }
                            } else {
                            Button(
                                onClick = {
                                    val sel = selectedModel
                                    if (sel.isNullOrBlank()) { appendLog("Select a model first (Refresh → pick model)"); return@Button }
                                    val job = scope.launch(Dispatchers.IO) {
                                        withContext(Dispatchers.Main) { busy = true; progress = UiProgress(sent = 0, processed = 0, total = 1, failed = 0) }
                                        try {
                                            val j = client.startJob()
                                            val entries = client.entries()
                                            if (entries.isEmpty()) { appendLog("No eligible images (all already analyzed or no pending items)"); return@launch }
                                            withContext(Dispatchers.Main) { progress = j.toUiProgress() }
                                            appendLog("Job ${j.jobId?.take(8) ?: "—"} • ${j.total} eligible • ${j.settings.detailLevel} / ${j.settings.languageName}")
                                            var cancelled = false
                                            for (entry in entries) {
                                                ensureActive()
                                                val live = client.status()
                                                if (!live.isRunning) { appendLog("■ Stopped remotely — halting"); cancelled = true; break }
                                                var shouldBreak = false
                                                try {
                                                    val img = client.image(entry.imageHash)
                                                    if (!client.status().isRunning) { appendLog("■ Stopped during transfer — halting"); shouldBreak = true }
                                                    else {
                                                        val (summary, tags) = lmClient.analyze(sel, img, live.settings)
                                                        withContext(Dispatchers.Main) { lmConnected = true }
                                                        if (!client.status().isRunning) { appendLog("■ Stopped before upload — discarding ${entry.imageHash.take(8)}"); shouldBreak = true }
                                                        else {
                                                            client.uploadResults(j.jobId, listOf(entry.copy(summary = summary, tags = tags, analyzedAt = System.currentTimeMillis(), modelUsed = sel)))
                                                            val p = client.status()
                                                            withContext(Dispatchers.Main) { progress = p.toUiProgress() }
                                                            appendLog("✓ ${entry.imageHash.take(8)}  ${summary.take(80)}${if (summary.length > 80) "…" else ""}  [${tags}]")
                                                        }
                                                    }
                                                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) { lmConnected = false }
                                                    val p = runCatching { client.status() }.getOrNull()
                                                    if (p != null) withContext(Dispatchers.Main) { progress = p.toUiProgress() }
                                                    appendLog("⊘ ${entry.imageHash.take(8)}  ${e.message}")
                                                    if (p != null && !p.isRunning) shouldBreak = true
                                                    else if (e is IllegalStateException && e.message?.contains("LMStudio request failed") == true) {
                                                        appendLog("■ LMStudio failed — stopping this entry")
                                                        shouldBreak = true
                                                    }
                                                }
                                                if (shouldBreak) { cancelled = true; break }
                                            }
                                            if (cancelled) return@launch
                                            val done = client.status()
                                            withContext(Dispatchers.Main) { progress = done.toUiProgress() }
                                            appendLog(if (!done.isRunning && done.processed < done.total) "■ Stopped: ${done.processed}/${done.total} processed" else "— Done: ${done.processed}/${done.total} processed")
                                        } catch (e: CancellationException) {
                                            appendLog("↻ Analysis cancelled")
                                        } catch (e: Exception) { appendLog("Processing failed: ${e.message}") }
                                        finally { withContext(NonCancellable) { busy = false } }
                                    }
                                    analysisJob = job
                                },
                                enabled = !busy && selectedModel != null && lmConnected == true,
                                modifier = Modifier.fillMaxWidth().requiredHeight(56.dp),
                                shape = RoundedCornerShape(28.dp)
                            ) { Text("Start Analysis") }
                            }
                        }

                        if (!compactLayout) {
                            ProgressCard(progress)
                        }
                    }

                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    Card(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                Row(
                                    Modifier.fillMaxWidth().height(34.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Activity Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        onClick = { logLines = emptyList() },
                                        modifier = Modifier.height(34.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("Clear") }
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(logLines) { line ->
                                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    if (compactLayout) {
                        ProgressCard(progress)
                    }
                }
                }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "LMStudio: $lmUrl" + if (!selectedModel.isNullOrBlank()) "  •  $selectedModel" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

fun main() = application {
    val state = rememberWindowState(width = 1080.dp, height = 640.dp)
    Window(onCloseRequest = ::exitApplication, title = "Skarmetoo Desktop", state = state) {
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(760, 600)
        }
        SkarmetooDesktopApp()
    }
}
