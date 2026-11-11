package com.deryk.skarmetoo.ai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.MultimodalBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "GgufLlmManager"

class GgufLlmManager(private val context: Context) {
  private val _activeModelInfo = MutableStateFlow<GgufModelInfo?>(null)
  val activeModelInfo: StateFlow<GgufModelInfo?> = _activeModelInfo.asStateFlow()

  internal var loadedModel: GgufModelInfo? = null
    set(value) {
      field = value
      _activeModelInfo.value = value
    }

  private val scope = CoroutineScope(Dispatchers.IO)
  internal val inferMutex = Mutex()
  private val downloadMutex = Mutex()
  internal var idleUnload: Job? = null
  // Selection survives unloading; only a request allocates a native model/context.
  private val _residentModel = MutableStateFlow<GgufResidentModel?>(null)
  val residentModel: StateFlow<GgufResidentModel?> = _residentModel.asStateFlow()
  internal val residency =
      GgufModelResidency(
          loadText = { model ->
            Log.d(TAG, "Loading text on demand: ${model.displayName}")
            LlamaBridge.initGenerateModel(File(context.filesDir, model.fileName).absolutePath)
          },
          loadVision = { model ->
            Log.d(TAG, "Loading vision on demand: ${model.displayName}")
            MultimodalBridge.initModel(
                File(context.filesDir, model.fileName).absolutePath,
                File(context.filesDir, model.mmprojFile).absolutePath)
          },
          releaseText = {
            Log.d(TAG, "Unloading text context")
            LlamaBridge.shutdown()
          },
          releaseVision = {
            Log.d(TAG, "Unloading vision context and projector")
            MultimodalBridge.release()
          },
          onChanged = { _residentModel.value = it },
      )

  internal val _uiState = MutableStateFlow<LlmState>(LlmState.Initial)
  val uiState: StateFlow<LlmState> = _uiState.asStateFlow()

  private val _analysisProgress = MutableStateFlow(0f)
  val analysisProgress: StateFlow<Float> = _analysisProgress.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _isDownloading = MutableStateFlow(false)
  val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

  private val _downloadingModelName = MutableStateFlow<String?>(null)
  val downloadingModelName: StateFlow<String?> = _downloadingModelName.asStateFlow()
  internal val _isInferenceRunning = MutableStateFlow(false)
  val isInferenceRunning: StateFlow<Boolean> = _isInferenceRunning.asStateFlow()

  init {
    context.applicationContext.registerComponentCallbacks(
        object : ComponentCallbacks2 {
          override fun onConfigurationChanged(newConfig: Configuration) = Unit

          override fun onLowMemory() {
            scope.launch { releaseInBackground() }
          }

          override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
              scope.launch { releaseInBackground() }
            }
          }
        })
  }

  /** Called under inferMutex. A subsequent request cancels this idle release before loading. */
  internal fun scheduleIdleUnload() {
    idleUnload?.cancel()
    idleUnload =
        scope.launch {
          delay(15_000)
          releaseInBackground()
        }
  }

  private suspend fun releaseInBackground() {
    try {
      unloadWhenIdle()
    } catch (e: Exception) {
      Log.e(TAG, "Could not unload the idle GGUF context", e)
      _uiState.value = LlmState.Error("Could not release model memory. Please reload the model.")
    }
  }

  sealed class LlmState {
    data object Initial : LlmState()

    data object Loading : LlmState()

    data object Ready : LlmState()

    data object Generating : LlmState()

    data class Error(val message: String) : LlmState()
  }

  fun loadModel(modelInfo: GgufModelInfo) {
    if (!isModelDownloaded(modelInfo)) {
      _uiState.value = LlmState.Error("Model or projector missing. Re-download.")
      return
    }
    _uiState.value = LlmState.Loading
    scope.launch {
      inferMutex.withLock {
        try {
          idleUnload?.cancel()
          residency.release()
          loadedModel = modelInfo
          _uiState.value = LlmState.Ready
          Log.d(TAG, "Selected for on-demand loading: ${modelInfo.displayName}")
        } catch (e: Exception) {
          _uiState.value = LlmState.Error("Could not release the previous model: ${e.message}")
        }
      }
    }
  }

  /** Free native allocations without clearing the selected model or downloaded files. */
  suspend fun unloadWhenIdle(expectedModel: GgufModelInfo? = null) =
      withContext(Dispatchers.IO) {
        inferMutex.withLock {
          if (expectedModel == null || loadedModel == expectedModel) residency.release()
        }
      }

  /** Must be called inside inferMutex; release the previous bridge before initialization. */
  internal fun prepareTextContext(model: GgufModelInfo, maxOutputTokens: Int) {
    val lfm = model.supportsLfmTextPlanning
    // The upstream text stream prefills in one batch, so batch capacity covers the context.
    LlamaBridge.updateGenerateParams(
        temperature = if (lfm) 0.20f else 0.7f,
        maxTokens = maxOutputTokens.coerceIn(1, 640),
        topP = if (lfm) 0.90f else 0.95f,
        topK = if (lfm) 50 else 40,
        repeatPenalty = if (lfm) 1.00f else 1.10f,
        contextLength = TEXT_CONTEXT_TOKENS,
        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        useMmap = true,
        flashAttention = false,
        batchSize = TEXT_CONTEXT_TOKENS,
    )
    residency.ensure(model, GgufBridge.TEXT)
  }

  internal fun prepareVisionContext(model: GgufModelInfo) {
    residency.ensure(model, GgufBridge.VISION)
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
        idleUnload?.cancel()
        try {
          _analysisProgress.value = 0f
          onProgress(0.1f)

          val model = loadedModel ?: error("Select a GGUF model first.")
          prepareTextContext(model, 512)
          val modelId = model.displayName
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
          scheduleIdleUnload()
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
        idleUnload?.cancel()
        try {
          val model = loadedModel ?: error("Load a vision model before image analysis.")
          check(model.isVision) { "The model changed. Run image analysis again." }
          prepareVisionContext(model)
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
          scheduleIdleUnload()
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

    suspendCoroutine<Unit> { cont ->
      val completed = AtomicBoolean(false)
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
                if (completed.compareAndSet(false, true)) cont.resume(Unit)
              }

              override fun onError(message: String) {
                hasError = true
                errorMsg = message
                if (completed.compareAndSet(false, true)) cont.resume(Unit)
              }
            })
      } catch (e: Exception) {
        hasError = true
        errorMsg = e.message ?: "VLM crashed"
        if (completed.compareAndSet(false, true)) cont.resume(Unit)
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

    suspendCoroutine<Unit> { cont ->
      val completed = AtomicBoolean(false)
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
                if (completed.compareAndSet(false, true)) cont.resume(Unit)
              }

              override fun onError(message: String) {
                hasError = true
                errorMsg = message
                if (completed.compareAndSet(false, true)) cont.resume(Unit)
              }
            })
      } catch (e: Exception) {
        hasError = true
        errorMsg = e.message ?: "Text gen crashed"
        if (completed.compareAndSet(false, true)) cont.resume(Unit)
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

          // Activation callbacks select and load through the shared inference lock.
          if (onComplete != null) {
            onComplete(true)
          } else {
            loadModel(modelInfo)
          }
        } catch (e: Exception) {
          Log.e(TAG, "GGUF model download failed", e)
          val tmp = File(context.filesDir, "${modelInfo.fileName}.tmp")
          if (tmp.exists()) tmp.delete()
          val mmprojTmp = File(context.filesDir, "${modelInfo.mmprojFile}.tmp")
          if (mmprojTmp.exists()) mmprojTmp.delete()
          _uiState.value = LlmState.Error("Model download failed: ${e.message}")
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
    val modelExists = f.exists() && f.length() > 1L * 1024 * 1024
    if (!modelInfo.isVision) return modelExists
    val mmproj = File(context.filesDir, modelInfo.mmprojFile)
    return modelExists && mmproj.exists() && mmproj.length() > 1L * 1024 * 1024
  }

  fun getDownloadedModels(): List<GgufModelInfo> {
    val all =
        listOf(LFM2_5_MODEL) +
            PRESET_GGUF_MODELS +
            listOfNotNull(ImportedGgufModelStore.getModelInfo(context))
    return all.filter { isModelDownloaded(it) }
  }

  suspend fun deleteModel(modelInfo: GgufModelInfo): Boolean =
      withContext(Dispatchers.IO) {
        inferMutex.withLock {
          // The selected planner can currently have a different vision model resident.
          if (loadedModel?.fileName == modelInfo.fileName ||
              _residentModel.value?.model?.fileName == modelInfo.fileName)
              residency.release()
          val f = File(context.filesDir, modelInfo.fileName)
          var deleted = !f.exists() || f.delete()
          if (modelInfo.isVision && modelInfo.mmprojFile.isNotBlank()) {
            val mmprojF = File(context.filesDir, modelInfo.mmprojFile)
            deleted = (!mmprojF.exists() || mmprojF.delete()) && deleted
          }
          if (loadedModel?.fileName == modelInfo.fileName) {
            loadedModel = null
            _uiState.value = LlmState.Initial
          }
          deleted
        }
      }

  internal fun resizeBitmap(originalBitmap: Bitmap, maxSize: Int): Bitmap {
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

  suspend fun closeAndWait() =
      withContext(Dispatchers.IO) {
        inferMutex.withLock {
          idleUnload?.cancel()
          residency.release()
          loadedModel = null
          _isInferenceRunning.value = false
          _analysisProgress.value = 0f
          _uiState.value = LlmState.Initial
        }
      }

  companion object {
    const val TEXT_CONTEXT_TOKENS = 4096
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
