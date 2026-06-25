package com.deryk.skarmetoo.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.MultimodalBridge
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "GgufLlmManager"

class GgufLlmManager(private val context: Context) {
  private val _activeModelInfo = MutableStateFlow<GgufModelInfo?>(null)
  val activeModelInfo: StateFlow<GgufModelInfo?> = _activeModelInfo.asStateFlow()

  private var loadedModel: GgufModelInfo? = null
    set(value) {
      field = value
      _activeModelInfo.value = value
    }

  private val scope = CoroutineScope(Dispatchers.IO)
  private val inferMutex = Mutex()
  private val downloadMutex = Mutex()

  private val _uiState = MutableStateFlow<LlmState>(LlmState.Initial)
  val uiState: StateFlow<LlmState> = _uiState.asStateFlow()

  private val _analysisProgress = MutableStateFlow(0f)
  val analysisProgress: StateFlow<Float> = _analysisProgress.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _isDownloading = MutableStateFlow(false)
  val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

  private val _downloadingModelName = MutableStateFlow<String?>(null)
  val downloadingModelName: StateFlow<String?> = _downloadingModelName.asStateFlow()
  private val _isInferenceRunning = MutableStateFlow(false)
  val isInferenceRunning: StateFlow<Boolean> = _isInferenceRunning.asStateFlow()

  sealed class LlmState {
    data object Initial : LlmState()

    data object Loading : LlmState()

    data object Ready : LlmState()

    data object Generating : LlmState()

    data class Error(val message: String) : LlmState()
  }

  fun loadModel(modelInfo: GgufModelInfo) {
    if (modelInfo.isVision) {
      val modelPath = File(context.filesDir, modelInfo.fileName).absolutePath
      val mmprojPath = File(context.filesDir, modelInfo.mmprojFile).absolutePath
      if (!File(modelPath).exists() || !File(mmprojPath).exists()) {
        _uiState.value = LlmState.Error("Model or mmproj file missing. Re-download.")
        return
      }
      _uiState.value = LlmState.Loading
      scope.launch {
        inferMutex.withLock {
          try {
            val loaded = MultimodalBridge.initModel(modelPath, mmprojPath)
            if (loaded) {
              loadedModel = modelInfo
              _uiState.value = LlmState.Ready
              Log.d(TAG, "VLM loaded: $modelPath + $mmprojPath")
            } else {
              _uiState.value = LlmState.Error("MultimodalBridge failed to init VLM")
            }
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load VLM", e)
            _uiState.value = LlmState.Error("VLM load failed: ${e.message}")
          }
        }
      }
    } else {
      initializeModel(
          modelPath = File(context.filesDir, modelInfo.fileName).absolutePath,
          nThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
      )
      loadedModel = modelInfo
    }
  }

  fun initializeModel(
      modelPath: String,
      nThreads: Int = 4,
      maxTokens: Int = 512,
      temperature: Float = 0.7f,
  ) {
    if (!File(modelPath).exists()) {
      _uiState.value = LlmState.Error("GGUF model file not found at $modelPath")
      return
    }

    _uiState.value = LlmState.Loading
    scope.launch {
      inferMutex.withLock {
        try {
          val loaded = LlamaBridge.initGenerateModel(modelPath)
          if (!loaded) {
            _uiState.value = LlmState.Error("LlamaBridge failed to load model")
            return@withLock
          }

          LlamaBridge.updateGenerateParams(
              temperature = temperature,
              maxTokens = maxTokens,
              topP = 0.95f,
              topK = 40,
              repeatPenalty = 1.1f,
              contextLength = 4096,
              numThreads = nThreads,
              useMmap = true,
              flashAttention = false,
              batchSize = 512,
          )
          _uiState.value = LlmState.Ready
          Log.d(TAG, "GGUF model loaded via Llamatik: $modelPath")
        } catch (e: Exception) {
          Log.e(TAG, "Failed to initialize GGUF model", e)
          _uiState.value = LlmState.Error("GGUF load failed: ${e.message}")
        }
      }
    }
  }

  fun generateResponse(prompt: String, onToken: (String) -> Unit = {}): String {
    if (_uiState.value !is LlmState.Ready) {
      _uiState.value = LlmState.Error("GGUF model not initialized")
      return ""
    }

    _uiState.value = LlmState.Generating
    val result = StringBuilder()
    try {
      LlamaBridge.generateStream(
          prompt,
          object : GenStream {
            override fun onDelta(text: String) {
              result.append(text)
              onToken(text)
            }

            override fun onComplete() {
              _uiState.value = LlmState.Ready
            }

            override fun onError(message: String) {
              _uiState.value = LlmState.Error("Generation failed: $message")
            }
          })
    } catch (e: Exception) {
      Log.e(TAG, "GGUF generation failed", e)
      _uiState.value = LlmState.Error("GGUF generation failed: ${e.message}")
    }
    return result.toString()
  }

  fun analyzeScreenshot(
      bitmap: Bitmap,
      detailLevel: String = "detailed",
      customPrompt: String? = null,
      targetLanguage: String = "English",
      imageResolution: Int = 768,
      onProgress: (Float) -> Unit = {},
      onResult: (summary: String, tags: String, modelUsed: String) -> Unit,
      onError: (String) -> Unit,
  ) {
    val currentState = _uiState.value
    if (currentState !is LlmState.Ready && currentState !is LlmState.Generating) {
      onError("GGUF model not initialized (state: $currentState)")
      return
    }

    val model = loadedModel
    if (model != null && model.isVision) {
      analyzeScreenshotVision(
          bitmap,
          detailLevel,
          customPrompt,
          targetLanguage,
          imageResolution,
          onProgress,
          onResult,
          onError)
      return
    }

    scope.launch {
      inferMutex.withLock {
        _isInferenceRunning.value = true
        try {
          _analysisProgress.value = 0f
          onProgress(0.1f)

          val modelId = loadedModel?.displayName ?: "GGUF"
          val prompt = buildPrompt(detailLevel, customPrompt, targetLanguage)

          val responseResult = runTextAnalysis(prompt) { p -> onProgress(0.1f + p * 0.8f) }
          if (responseResult == null) {
            onError("Model returned no output")
            return@withLock
          }
          onProgress(0.9f)

          val parsed = parseAnalysisResult(responseResult)
          if (parsed.first.isBlank()) {
            Log.w(TAG, "Text model empty summary, raw: ${responseResult.take(200)}")
            onError("Could not parse model output")
            return@withLock
          }
          onProgress(1.0f)
          onResult(parsed.first, parsed.second, modelId)
          _analysisProgress.value = 1f
        } catch (e: Exception) {
          Log.e(TAG, "GGUF screenshot analysis failed", e)
          onError(e.message ?: "GGUF analysis failed")
        } finally {
          _isInferenceRunning.value = false
        }
      }
    }
  }

  private fun analyzeScreenshotVision(
      bitmap: Bitmap,
      detailLevel: String,
      customPrompt: String?,
      targetLanguage: String,
      imageResolution: Int,
      onProgress: (Float) -> Unit,
      onResult: (summary: String, tags: String, modelUsed: String) -> Unit,
      onError: (String) -> Unit,
  ) {
    scope.launch {
      inferMutex.withLock {
        _isInferenceRunning.value = true
        try {
          _analysisProgress.value = 0f
          onProgress(0.1f)

          val resized = resizeBitmap(bitmap, imageResolution)
          val jpegBytes = resized.toJpegByteArray()

          // Recycle intermediate bitmap to prevent OOM
          if (resized != bitmap) resized.recycle()

          val modelId = loadedModel?.displayName ?: "GGUF-Vision"
          val levelPrompt =
              when (detailLevel.lowercase()) {
                "brief" -> "Give a one-sentence summary of this screenshot."
                "detailed" -> "Describe this screenshot in 2-3 sentences."
                "comprehensive" -> "Describe this screenshot thoroughly in one full paragraph."
                "custom" -> customPrompt ?: "Describe this screenshot."
                else -> "Describe this screenshot in 2-3 sentences."
              }
          val prompt = "$levelPrompt\nRespond in $targetLanguage."
          val wrappedPrompt = applyChatTemplate(loadedModel?.chatTemplate ?: "", prompt)

          val responseResult =
              runVisionAnalysis(jpegBytes, wrappedPrompt) { p -> onProgress(0.1f + p * 0.8f) }
          if (responseResult == null) {
            onError("VLM returned no output")
            return@withLock
          }
          onProgress(0.9f)

          val parsed = parseAnalysisResult(responseResult)
          if (parsed.first.isBlank()) {
            Log.w(TAG, "VLM empty summary, raw: ${responseResult.take(200)}")
            onError("Could not parse VLM output")
            return@withLock
          }
          onProgress(1.0f)
          onResult(parsed.first, parsed.second, modelId)
          _analysisProgress.value = 1f
        } catch (e: Exception) {
          Log.e(TAG, "VLM screenshot analysis failed", e)
          onError(e.message ?: "VLM analysis failed")
        } finally {
          _isInferenceRunning.value = false
        }
      }
    }
  }

  private suspend fun runVisionAnalysis(
      imageBytes: ByteArray,
      prompt: String,
      onTokenProgress: ((Float) -> Unit)? = null
  ): String? {
    val fullResponse = StringBuilder()
    var hasError = false
    var errorMsg = ""
    var tokenCount = 0

    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
      cont.invokeOnCancellation {
        hasError = true
        errorMsg = "VLM analysis cancelled"
      }
      try {
        MultimodalBridge.analyzeImageBytesStream(
            imageBytes,
            prompt,
            object : GenStream {
              override fun onDelta(text: String) {
                fullResponse.append(text)
                tokenCount++
                onTokenProgress?.invoke(tokenCount / (tokenCount + 20f))
              }

              override fun onComplete() {
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
              }

              override fun onError(msg: String) {
                hasError = true
                errorMsg = msg
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
              }
            })
      } catch (e: Exception) {
        hasError = true
        errorMsg = e.message ?: "VLM crashed"
        if (cont.isActive) cont.resume(Unit, onCancellation = null)
      }
    }

    if (hasError) {
      Log.e(TAG, "VLM stream error: $errorMsg")
      return null
    }
    val result = fullResponse.toString().trim()
    if (result.isBlank()) {
      Log.w(TAG, "VLM returned empty response")
      return null
    }
    return result
  }

  private suspend fun runTextAnalysis(
      prompt: String,
      onTokenProgress: ((Float) -> Unit)? = null
  ): String? {
    val fullResponse = StringBuilder()
    var hasError = false
    var errorMsg = ""
    var tokenCount = 0

    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
      cont.invokeOnCancellation {
        hasError = true
        errorMsg = "Text analysis cancelled"
      }
      try {
        LlamaBridge.generateStream(
            prompt,
            object : GenStream {
              override fun onDelta(text: String) {
                fullResponse.append(text)
                tokenCount++
                onTokenProgress?.invoke(tokenCount / (tokenCount + 20f))
              }

              override fun onComplete() {
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
              }

              override fun onError(msg: String) {
                hasError = true
                errorMsg = msg
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
              }
            })
      } catch (e: Exception) {
        hasError = true
        errorMsg = e.message ?: "Text gen crashed"
        if (cont.isActive) cont.resume(Unit, onCancellation = null)
      }
    }

    if (hasError) {
      Log.e(TAG, "Text stream error: $errorMsg")
      return null
    }
    val result = fullResponse.toString().trim()
    if (result.isBlank()) {
      Log.w(TAG, "Text model returned empty response")
      return null
    }
    return result
  }

  private fun buildPrompt(
      detailLevel: String,
      customPrompt: String?,
      targetLanguage: String,
  ): String {
    val levelPrompt =
        when (detailLevel.lowercase()) {
          "brief" -> "Summarize the following in one short sentence."
          "detailed" -> "Summarize the following in 2-3 sentences."
          "comprehensive" -> "Summarize the following thoroughly in one full paragraph."
          "custom" -> customPrompt ?: "Summarize the following."
          else -> "Summarize the following in 2-3 sentences."
        }
    val rawPrompt = "$levelPrompt\nRespond in $targetLanguage."
    return applyChatTemplate(loadedModel?.chatTemplate ?: "", rawPrompt)
  }

  private fun parseAnalysisResult(result: String): Pair<String, String> {
    val cleaned = result.replace(Regex("```(json)?\\s*"), "").replace(Regex("```\\s*$"), "").trim()
    return cleaned to ""
  }

  fun downloadModel(
      modelInfo: GgufModelInfo,
      hfToken: String = "",
      onComplete: ((Boolean) -> Unit)? = null,
  ) {
    if (_isDownloading.value) return

    scope.launch {
      downloadMutex.withLock {
        _isDownloading.value = true
        _downloadingModelName.value = modelInfo.displayName
        _downloadProgress.value = 0f

        try {
          val destFile = File(context.filesDir, modelInfo.fileName)
          val url = "https://huggingface.co/${modelInfo.hfRepo}/resolve/main/${modelInfo.hfFile}"
          Log.d(TAG, "Downloading GGUF model from: $url")
          if (modelInfo.isVision && modelInfo.mmprojFile.isNotBlank()) {
            val totalSize = modelInfo.sizeMb.toFloat() + modelInfo.mmprojSizeMb.toFloat()
            val modelWeight = modelInfo.sizeMb.toFloat() / totalSize

            downloadFile(url, destFile, hfToken, modelWeight, 0f)

            val mmprojDest = File(context.filesDir, modelInfo.mmprojFile)
            val mmprojUrl =
                "https://huggingface.co/${modelInfo.hfRepo}/resolve/main/${modelInfo.mmprojFile}"
            Log.d(TAG, "Downloading mmproj from: $mmprojUrl")
            val mmprojWeight = modelInfo.mmprojSizeMb.toFloat() / totalSize
            downloadFile(mmprojUrl, mmprojDest, hfToken, mmprojWeight, modelWeight)
          } else {
            downloadFile(url, destFile, hfToken, 1f, 0f)
          }

          _downloadProgress.value = 1f
          Log.d(
              TAG,
              "GGUF model downloaded: ${destFile.absolutePath} (${destFile.length() / 1024 / 1024} MB)")

          val modelPath = destFile.absolutePath
          if (modelInfo.isVision) {
            val mmprojPath = File(context.filesDir, modelInfo.mmprojFile).absolutePath
            val loaded = MultimodalBridge.initModel(modelPath, mmprojPath)
            if (loaded) {
              loadedModel = modelInfo
              _uiState.value = LlmState.Ready
              Log.d(TAG, "VLM loaded via MultimodalBridge")
            } else {
              _uiState.value = LlmState.Error("MultimodalBridge failed to init VLM")
            }
          } else {
            initializeModel(
                modelPath = modelPath,
                nThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
            loadedModel = modelInfo
          }

          onComplete?.invoke(true)
        } catch (e: Exception) {
          Log.e(TAG, "GGUF model download failed", e)
          val tmp = File(context.filesDir, "${modelInfo.fileName}.tmp")
          if (tmp.exists()) tmp.delete()
          onComplete?.invoke(false)
        } finally {
          _isDownloading.value = false
          _downloadingModelName.value = null
        }
      }
    }
  }

  private fun downloadFile(
      url: String,
      destFile: File,
      token: String,
      progressWeight: Float,
      overallStart: Float
  ) {
    val tmpFile = File(context.filesDir, "${destFile.name}.tmp")
    var currentUrl = url
    var connection: java.net.HttpURLConnection
    var redirects = 0

    while (true) {
      connection = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.setRequestProperty("Accept-Encoding", "identity")
      connection.connectTimeout = 60000
      connection.readTimeout = 120000

      if (currentUrl.contains("huggingface.co") && !currentUrl.contains("cdn-lfs")) {
        if (token.isNotBlank()) {
          connection.setRequestProperty("Authorization", "Bearer $token")
        }
      }

      connection.connect()
      val status = connection.responseCode

      if (status in 300..399) {
        val newUrl =
            connection.getHeaderField("Location") ?: throw Exception("Redirect missing Location")
        currentUrl = newUrl
        redirects++
        if (redirects > 10) throw Exception("Too many redirects")
        connection.disconnect()
        continue
      }

      if (status != java.net.HttpURLConnection.HTTP_OK) {
        throw Exception("HTTP $status ${connection.responseMessage}")
      }
      break
    }

    val fileLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
    connection.inputStream.buffered(8192).use { input ->
      tmpFile.outputStream().buffered(8192).use { output ->
        val buffer = ByteArray(8192)
        var total: Long = 0
        var bytesRead: Int
        var lastUpdate = System.currentTimeMillis()

        while (input.read(buffer).also { bytesRead = it } != -1) {
          total += bytesRead
          if (fileLength > 0) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 100) {
              val modelProgress = (total.toDouble() / fileLength.toDouble()).toFloat()
              _downloadProgress.value = overallStart + modelProgress * progressWeight
              lastUpdate = now
            }
          }
          output.write(buffer, 0, bytesRead)
        }
        output.flush()
      }
    }
    connection.disconnect()

    if (destFile.exists()) destFile.delete()
    tmpFile.renameTo(destFile)
  }

  fun isModelDownloaded(modelInfo: GgufModelInfo): Boolean {
    val f = File(context.filesDir, modelInfo.fileName)
    return f.exists() && f.length() > 1L * 1024 * 1024
  }

  fun getDownloadedModels(): List<GgufModelInfo> {
    val all =
        listOf(LFM2_5_MODEL) +
            PRESET_GGUF_MODELS +
            listOfNotNull(ImportedGgufModelStore.getModelInfo(context))
    return all.filter { isModelDownloaded(it) }
  }

  fun deleteModel(modelInfo: GgufModelInfo): Boolean {
    val f = File(context.filesDir, modelInfo.fileName)
    if (f.exists()) f.delete()
    if (modelInfo.isVision && modelInfo.mmprojFile.isNotBlank()) {
      val mmprojF = File(context.filesDir, modelInfo.mmprojFile)
      if (mmprojF.exists()) mmprojF.delete()
    }
    if (loadedModel?.fileName == modelInfo.fileName) {
      try {
        LlamaBridge.shutdown()
      } catch (_: Exception) {}
      try {
        MultimodalBridge.release()
      } catch (_: Exception) {}
      loadedModel = null
      _uiState.value = LlmState.Initial
    }
    return true
  }

  private fun resizeBitmap(originalBitmap: Bitmap, maxSize: Int): Bitmap {
    val width = originalBitmap.width
    val height = originalBitmap.height
    if (width <= maxSize && height <= maxSize) return originalBitmap

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
    scope.launch { closeAndWait() }
  }

  suspend fun closeAndWait() {
    inferMutex.withLock {
      try {
        LlamaBridge.shutdown()
      } catch (_: Exception) {}
      try {
        MultimodalBridge.release()
      } catch (_: Exception) {}
      loadedModel = null
      _isInferenceRunning.value = false
      _analysisProgress.value = 0f
      _uiState.value = LlmState.Initial
    }
  }

  companion object {
    @Volatile private var instance: GgufLlmManager? = null

    fun getInstance(context: Context): GgufLlmManager {
      return instance
          ?: synchronized(this) {
            instance ?: GgufLlmManager(context.applicationContext).also { instance = it }
          }
    }
  }
}

private fun Bitmap.toJpegByteArray(): ByteArray {
  val stream = ByteArrayOutputStream()
  this.compress(Bitmap.CompressFormat.JPEG, 85, stream)
  return stream.toByteArray()
}
