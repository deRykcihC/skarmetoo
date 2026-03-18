package com.deryk.skarmetoo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "LlmManager"

class LlmManager(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val engineMutex = kotlinx.coroutines.sync.Mutex()

    private val _uiState = MutableStateFlow<LlmState>(LlmState.Initial)
    val uiState: StateFlow<LlmState> = _uiState.asStateFlow()

    private val _partialResults =
        MutableSharedFlow<Pair<String, Boolean>>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val partialResults: SharedFlow<Pair<String, Boolean>> = _partialResults.asSharedFlow()

    fun initializeModel(
        modelPath: String,
        useGpu: Boolean = false,
        maxTokens: Int = 4096,
    ) {
        if (!File(modelPath).exists()) {
            _uiState.value = LlmState.Error("Model file not found at $modelPath")
            return
        }

        _uiState.value = LlmState.Loading
        scope.launch {
            engineMutex.withLock {
                try {
                    // Close existing engine and conversation securely before initializing
                    try {
                        conversation?.close()
                        conversation = null
                        engine?.close()
                        engine = null
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing existing engine before init: ${e.message}")
                    }

                    // Use cacheDir for /data/local/tmp models (same as Gallery app)
                    val cacheDirPath = context.cacheDir.absolutePath

                    val mainBackend = if (useGpu) Backend.GPU else Backend.CPU
                    // Vision backend must be GPU for Gemma 3n
                    val visionBk = Backend.GPU

                    Log.d(TAG, "Model path: $modelPath")
                    Log.d(TAG, "Main backend: ${if (useGpu) "GPU" else "CPU"}")
                    Log.d(TAG, "Vision backend: GPU")
                    Log.d(TAG, "Max tokens: $maxTokens")
                    Log.d(TAG, "Cache dir: $cacheDirPath")

                    val engineConfig =
                        EngineConfig(
                            modelPath = modelPath,
                            backend = mainBackend,
                            visionBackend = visionBk,
                            maxNumTokens = maxTokens,
                            cacheDir = cacheDirPath,
                        )

                    Log.d(TAG, "Creating engine...")
                    val newEngine =
                        try {
                            Engine(engineConfig)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Native crash creating Engine", t)
                            throw Exception("Hardware/Native error: ${t.message}")
                        }

                    Log.d(TAG, "Initializing engine...")
                    try {
                        newEngine.initialize()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Native crash in initialize()", t)
                        throw Exception("Model load fail: ${t.message}")
                    }

                    Log.d(TAG, "Engine initialized successfully!")
                    engine = newEngine

                    _uiState.value = LlmState.Ready
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to load model", e)
                    _uiState.value = LlmState.Error("Failed to load model: ${e.message}")
                }
            }
        }
    }

    fun generateResponse(prompt: String) {
        if (engine == null || conversation == null) {
            _uiState.value = LlmState.Error("Model not initialized")
            return
        }

        _uiState.value = LlmState.Generating
        try {
            val contents = mutableListOf<Content>()
            contents.add(Content.Text(prompt))

            conversation?.sendMessageAsync(
                Contents.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        _partialResults.tryEmit(message.toString() to false)
                    }

                    override fun onDone() {
                        _partialResults.tryEmit("" to true)
                        _uiState.value = LlmState.Ready
                    }

                    override fun onError(throwable: Throwable) {
                        _uiState.value = LlmState.Error("Generation failed: ${throwable.message}")
                    }
                },
            )
        } catch (e: Exception) {
            _uiState.value = LlmState.Error("Generation failed: ${e.message}")
        }
    }

    fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
    ) {
        if (engine == null || conversation == null) {
            _uiState.value = LlmState.Error("Model not initialized")
            return
        }

        _uiState.value = LlmState.Generating
        scope.launch {
            try {
                // Reset conversation for new image query
                conversation?.close()
                val convConfig =
                    ConversationConfig(
                        samplerConfig =
                            SamplerConfig(
                                topK = 64,
                                topP = 0.95,
                                temperature = 1.0,
                            ),
                    )
                conversation = engine?.createConversation(convConfig)

                // Resize bitmap to max 768x768 maintaining aspect ratio
                val resized = resizeBitmap(bitmap, 768)
                // Ensure ARGB_8888 config
                val argbBitmap =
                    if (resized.config != Bitmap.Config.ARGB_8888) {
                        resized.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        resized
                    }

                val contents = mutableListOf<Content>()
                // Add image first (as PNG bytes)
                contents.add(Content.ImageBytes(argbBitmap.toPngByteArray()))
                // Then add text prompt
                contents.add(Content.Text(prompt))

                conversation?.sendMessageAsync(
                    Contents.of(contents),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            _partialResults.tryEmit(message.toString() to false)
                        }

                        override fun onDone() {
                            _partialResults.tryEmit("" to true)
                            _uiState.value = LlmState.Ready
                        }

                        override fun onError(throwable: Throwable) {
                            _uiState.value = LlmState.Error("Image analysis failed: ${throwable.message}")
                        }
                    },
                )
            } catch (e: Exception) {
                _uiState.value = LlmState.Error("Image analysis failed: ${e.message}")
            }
        }
    }

    /**
     * Analyze a screenshot and return structured summary + tags.
     * Closes existing conversation first (engine only supports 1 session).
     * Asks model to output JSON, with fallback parsing.
     */
    enum class DetailLevel(val label: String) {
        BRIEF("Brief"),
        DETAILED("Detailed"),
        COMPREHENSIVE("Comprehensive"),
        CUSTOM("Custom"),
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        detailLevel: DetailLevel = DetailLevel.DETAILED,
        customPrompt: String? = null,
        isChinese: Boolean = false,
        onProgress: (Float) -> Unit = {},
        onResult: (summary: String, tags: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (engine == null) {
            onError("Model not initialized")
            return
        }

        scope.launch {
            engineMutex.withLock {
                try {
                    // CRITICAL: Close any existing conversation first!
                    // Engine only supports one session at a time.
                    try {
                        conversation?.close()
                        conversation = null
                        Log.d(TAG, "Closed existing conversation")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing conversation: ${e.message}")
                    }

                    val convConfig =
                        ConversationConfig(
                            samplerConfig =
                                SamplerConfig(
                                    topK = 64,
                                    topP = 0.95,
                                    temperature = 0.7,
                                ),
                        )
                    val conv =
                        engine?.createConversation(convConfig) ?: run {
                            onError("Failed to create conversation")
                            return@withLock
                        }
                    conversation = conv
                    Log.d(TAG, "Created new conversation for analysis (level: ${detailLevel.label})")

                    val resized = resizeBitmap(bitmap, 768)
                    val argbBitmap =
                        if (resized.config != Bitmap.Config.ARGB_8888) {
                            resized.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            resized
                        }

                    val langName = if (isChinese) "Traditional Chinese" else "English"
                    val prompt =
                        when (detailLevel) {
                            DetailLevel.BRIEF ->
                                """Describe this screenshot briefly in $langName. Respond with EXACTLY this format and nothing else:
SUMMARY: [your one sentence description]
TAGS: [tag1, tag2, tag3]"""

                            DetailLevel.DETAILED ->
                                """Describe this screenshot in detail in $langName. Write 2-3 sentences covering what is shown, visible text, UI elements, and purpose. Respond with EXACTLY this format and nothing else:
SUMMARY: [your detailed 2-3 sentence description]
TAGS: [tag1, tag2, tag3, tag4, tag5]"""

                            DetailLevel.COMPREHENSIVE ->
                                """Describe this screenshot with maximum detail in $langName, using a single paragraph. Cover ALL visible text, UI elements, colors, layout, branding, and objects. Omit nothing. Respond with EXACTLY this format and nothing else:
SUMMARY: [your comprehensive paragraph describing absolutely everything visible in the image]
TAGS: [tag1, tag2, tag3, tag4, tag5, tag6, tag7, tag8]"""

                            DetailLevel.CUSTOM ->
                                """${customPrompt ?: "Describe this screenshot."} Output your summary in $langName.
Respond with EXACTLY this format and nothing else:
SUMMARY: [your response based on the instruction]
TAGS: [extracted tag1, tag2, tag3]"""
                        }

                    val estimatedTotalChars =
                        when (detailLevel) {
                            DetailLevel.BRIEF -> 150f
                            DetailLevel.DETAILED -> 400f
                            DetailLevel.COMPREHENSIVE -> 800f
                            DetailLevel.CUSTOM -> 300f
                        }

                    val contents = mutableListOf<Content>()
                    contents.add(Content.ImageBytes(argbBitmap.toPngByteArray()))
                    contents.add(Content.Text(prompt))

                    val fullResponse = StringBuilder()
                    Log.d(TAG, "Sending image for analysis...")

                    // Uses suspendCancellableCoroutine to block the Mutex until the engine is fully done,
                    // guaranteeing callbacks are completely resolved before the next coroutine opens a new session.
                    val resultPair =
                        kotlin.coroutines.suspendCoroutine<Pair<String, String>?> { continuation ->
                            var isResumed = false
                            try {
                                conv.sendMessageAsync(
                                    Contents.of(contents),
                                    object : MessageCallback {
                                        override fun onMessage(message: Message) {
                                            val token = message.toString()
                                            fullResponse.append(token)
                                            var progress = fullResponse.length / estimatedTotalChars
                                            if (progress > 0.95f) progress = 0.95f
                                            onProgress(progress)
                                        }

                                        override fun onDone() {
                                            try {
                                                onProgress(1.0f)
                                                val result = fullResponse.toString().trim()
                                                Log.d(TAG, "Raw model output length: ${result.length}")
                                                Log.d(TAG, "Raw model output (first 500): ${result.take(500)}")
                                                val parsedResult = parseAnalysisResult(result)
                                                Log.d(TAG, "Parsed - Summary: ${parsedResult.first.take(200)}")
                                                Log.d(TAG, "Parsed - Tags: ${parsedResult.second}")
                                                if (!isResumed) {
                                                    isResumed = true
                                                    continuation.resume(parsedResult)
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error in onDone parsing/returning results", e)
                                                if (!isResumed) {
                                                    isResumed = true
                                                    continuation.resumeWithException(e)
                                                }
                                            }
                                        }

                                        override fun onError(throwable: Throwable) {
                                            Log.e(TAG, "Analysis callback error: ${throwable.message}")
                                            if (!isResumed) {
                                                isResumed = true
                                                continuation.resumeWithException(throwable)
                                            }
                                        }
                                    },
                                )
                            } catch (e: Exception) {
                                if (!isResumed) {
                                    isResumed = true
                                    continuation.resumeWithException(e)
                                }
                            }
                        }

                    if (resultPair != null) {
                        onResult(resultPair.first, resultPair.second)
                    } else {
                        onError("Analysis returned null result")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot analysis failed", e)
                    onError(e.message ?: "Analysis failed")
                } finally {
                    // Ensure native loop cleanup happens BEFORE Mutex is unlocked
                    try {
                        conversation?.close()
                        conversation = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to forcefully close conversation in finally loop", e)
                    }
                }
            }
        }
    }

    private fun parseAnalysisResult(result: String): Pair<String, String> {
        var summary = ""
        var tags = ""

        // Cleanup: strip markdown code fences and common leading/trailing artifacts
        val cleaned =
            result
                .replace(Regex("```(json)?\\s*"), "")
                .replace(Regex("```\\s*$"), "")
                .trim()

        // 1. Try robust Regex extraction for "SUMMARY:" and "TAGS:"
        // This handles cases where SUMMARY and TAGS are on the same line or separated by any whitespace/newlines.
        val summaryMatch = Regex("(?i)SUMMARY\\s*:\\s*(.*?)(?=\\s*(?:TAGS\\s*:|$))", RegexOption.DOT_MATCHES_ALL).find(cleaned)
        val tagsMatch = Regex("(?i)TAGS\\s*:\\s*(.*)", RegexOption.DOT_MATCHES_ALL).find(cleaned)

        if (summaryMatch != null) {
            summary = summaryMatch.groupValues[1].trim().trim('"').trim('[', ']')
        }
        if (tagsMatch != null) {
            tags = tagsMatch.groupValues[1].trim().trim('"').trim('[', ']')
        }

        // 2. Fallback: If summary is still blank, try Regex for JSON-style keys (handles malformed JSON)
        if (summary.isBlank()) {
            val jsonSummaryMatch = Regex("(?i)\"summary\"\\s*:\\s*\"(.*?)\"").find(cleaned)
            val jsonTagsMatch = Regex("(?i)\"tags\"\\s*:\\s*\"(.*?)\"").find(cleaned)

            if (jsonSummaryMatch != null) summary = jsonSummaryMatch.groupValues[1]
            if (jsonTagsMatch != null) tags = jsonTagsMatch.groupValues[1]

            // If still nothing, check for any common description keys
            if (summary.isBlank()) {
                val altSummaryMatch = Regex("(?i)\"(?:description|content|caption)\"\\s*:\\s*\"(.*?)\"").find(cleaned)
                if (altSummaryMatch != null) summary = altSummaryMatch.groupValues[1]
            }
        }

        // 3. Final fallback: use raw output if we found nothing structured
        if (summary.isBlank() && cleaned.isNotBlank()) {
            val firstLine = cleaned.lines().firstOrNull { it.isNotBlank() } ?: ""
            summary = if (firstLine.length > 30) firstLine else cleaned.replace("\n", " ").take(1000)
            Log.d(TAG, "Using raw output fallback")
        }

        tags = cleanTags(tags)
        return summary to tags
    }

    /**
     * Extract tags from a JSON object. Handles both:
     * - String: "tag1, tag2, tag3"
     * - Array: ["tag1", "tag2", "tag3"]
     */
    private fun extractTags(
        json: org.json.JSONObject,
        key: String,
    ): String {
        val raw = json.opt(key) ?: return ""
        return when (raw) {
            is org.json.JSONArray -> {
                val tagList = mutableListOf<String>()
                for (i in 0 until raw.length()) {
                    val tag = raw.optString(i, "").trim()
                    if (tag.isNotBlank()) tagList.add(tag)
                }
                tagList.joinToString(", ")
            }
            is String -> raw
            else -> raw.toString()
        }
    }

    /**
     * Clean tags string: remove stray brackets, quotes, normalize format.
     */
    private fun cleanTags(raw: String): String {
        return raw
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")
            .replace("'", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    private fun resizeBitmap(
        originalBitmap: Bitmap,
        maxSize: Int,
    ): Bitmap {
        val width = originalBitmap.width
        val height = originalBitmap.height

        if (width <= maxSize && height <= maxSize) {
            return originalBitmap
        }

        val aspectRatio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (aspectRatio > 1) {
            newWidth = maxSize
            newHeight = (maxSize / aspectRatio).toInt()
        } else {
            newHeight = maxSize
            newWidth = (maxSize * aspectRatio).toInt()
        }

        return Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
    }

    fun close() {
        scope.launch {
            engineMutex.withLock {
                try {
                    conversation?.close()
                    conversation = null
                    engine?.close()
                    engine = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error during close: ${e.message}")
                }
            }
        }
    }

    sealed class LlmState {
        object Initial : LlmState()

        object Loading : LlmState()

        object Ready : LlmState()

        object Generating : LlmState()

        data class Error(val message: String) : LlmState()
    }

    companion object {
        @Volatile
        private var instance: LlmManager? = null

        fun getInstance(context: Context): LlmManager {
            return instance ?: synchronized(this) {
                instance ?: LlmManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}
