package com.deryk.skarmetoo.ai

import android.graphics.Bitmap
import com.deryk.skarmetoo.ai.GgufLlmManager.LlmState
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.MultimodalBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep GGUF allocations absent throughout a separate memory-heavy operation. */
suspend fun <T> GgufLlmManager.withModelUnloaded(
    expectedModel: GgufModelInfo,
    work: suspend () -> T
): T =
    withContext(Dispatchers.IO) {
      inferMutex.withLock {
        idleUnload?.cancel()
        check(loadedModel == expectedModel) { "The GGUF model changed. Run the request again." }
        residency.release()
        work()
      }
    }

/** Shares the native inference lock with gallery analysis and model loading/release. */
suspend fun GgufLlmManager.generateChatResponse(
    prompt: String,
    bitmap: Bitmap? = null,
    expectedModel: GgufModelInfo,
    maxOutputTokens: Int = 512,
    systemPrompt: String? = null,
    visionModel: GgufModelInfo = expectedModel,
    responseSchema: String? = null,
    onToken: (String) -> Unit = {}
): String =
    withContext(Dispatchers.IO) {
      inferMutex.withLock {
        idleUnload?.cancel()
        check(loadedModel == expectedModel) { "The GGUF model changed. Run the request again." }
        check(_uiState.value is LlmState.Ready) {
          (_uiState.value as? LlmState.Error)?.message
              ?: "Load a GGUF model before running a request."
        }
        val inferenceModel = if (bitmap == null) expectedModel else visionModel
        require(bitmap == null || responseSchema == null) {
          "The vision bridge does not support constrained JSON decoding."
        }
        require(bitmap == null || inferenceModel.isVision) {
          "This GGUF model has no vision projector."
        }
        require(
            visionModel == expectedModel ||
                (bitmap != null &&
                    expectedModel.supportsLfmTextPlanning &&
                    visionModel.fileName == LFM2_5_MODEL.fileName)) {
              "Only LFM Chat can use its matching 450M vision model."
            }
        check(bitmap == null || isModelDownloaded(inferenceModel)) {
          "Download ${inferenceModel.displayName} and its matching projector first."
        }
        require(
            !expectedModel.isVision || bitmap != null || expectedModel.supportsLfmTextPlanning) {
              "The existing vision bridge requires an image. Attach one to test this model."
            }
        _isInferenceRunning.value = true
        _uiState.value = LlmState.Generating
        try {
          generateNative(
              prompt,
              bitmap,
              expectedModel,
              inferenceModel,
              maxOutputTokens,
              systemPrompt,
              responseSchema,
              onToken)
        } finally {
          _isInferenceRunning.value = false
          if (_uiState.value is LlmState.Generating) _uiState.value = LlmState.Ready
          scheduleIdleUnload()
        }
      }
    }

/**
 * Holds the native lock for the entire image batch; other work cannot reload a model between
 * images.
 */
suspend fun <T> GgufLlmManager.withVisionBatch(
    expectedModel: GgufModelInfo,
    visionModel: GgufModelInfo,
    work: suspend (VisionBatch) -> T,
): T =
    withContext(Dispatchers.IO) {
      inferMutex.withLock {
        idleUnload?.cancel()
        check(loadedModel == expectedModel) { "The GGUF model changed. Run the request again." }
        check(_uiState.value is LlmState.Ready) { "Select a GGUF model before running a request." }
        require(
            visionModel.isVision &&
                (visionModel == expectedModel ||
                    (expectedModel.supportsLfmTextPlanning && visionModel == LFM2_5_MODEL))) {
              "Only LFM Chat can use its matching 450M vision model."
            }
        check(isModelDownloaded(visionModel)) {
          "Download the vision model and its projector first."
        }
        val batch = VisionBatch(this@withVisionBatch, expectedModel, visionModel)
        _isInferenceRunning.value = true
        _uiState.value = LlmState.Generating
        try {
          // Allocation remains lazy: a fully cached batch does not load the image model.
          work(batch)
        } finally {
          try {
            // Wait for any in-flight session call even when the owning UI job is cancelled.
            withContext(NonCancellable) { batch.finish() }
            // Release 450M before a final 3B answer, cancellation, or a failed batch.
            residency.release()
          } finally {
            _isInferenceRunning.value = false
            if (_uiState.value is LlmState.Generating) _uiState.value = LlmState.Ready
          }
        }
      }
    }

class VisionBatch
internal constructor(
    private val manager: GgufLlmManager,
    private val expectedModel: GgufModelInfo,
    private val visionModel: GgufModelInfo,
) {
  private var active = true
  private val streamMutex = Mutex()

  internal suspend fun finish() = streamMutex.withLock { active = false }

  suspend fun generate(
      prompt: String,
      bitmap: Bitmap,
      maxOutputTokens: Int = 512,
      onToken: (String) -> Unit = {},
  ): String =
      streamMutex.withLock {
        check(active) { "The image batch has already finished." }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        manager.generateNative(
            prompt, bitmap, expectedModel, visionModel, maxOutputTokens, null, null, onToken)
      }
}

/** Called only while the manager's native lock is owned. */
private suspend fun GgufLlmManager.generateNative(
    prompt: String,
    bitmap: Bitmap?,
    expectedModel: GgufModelInfo,
    inferenceModel: GgufModelInfo,
    maxOutputTokens: Int,
    systemPrompt: String?,
    responseSchema: String?,
    onToken: (String) -> Unit,
): String {
  if (bitmap == null) {
    prepareTextContext(expectedModel, maxOutputTokens)
  } else {
    prepareVisionContext(inferenceModel)
  }
  val bytes =
      bitmap?.let { original ->
        val resized = resizeBitmap(original, 1024)
        try {
          resized.toJpegByteArray()
        } finally {
          if (resized !== original) resized.recycle()
        }
      }
  val result = StringBuilder()
  // Wait for native completion even if the UI coroutine is cancelled. Releasing the lock
  // early would allow another request or model shutdown to use the same native context.
  suspendCoroutine<Unit> { continuation ->
    val completed = AtomicBoolean(false)
    val callback =
        object : GenStream {
          override fun onDelta(text: String) {
            result.append(text)
            onToken(text)
          }

          override fun onComplete() {
            if (completed.compareAndSet(false, true)) continuation.resume(Unit)
          }

          override fun onError(message: String) {
            if (completed.compareAndSet(false, true))
                continuation.resumeWithException(IllegalStateException(message))
          }
        }
    try {
      // The bundled native grammar sampler aborts on LFM tokens (SIGABRT). Keep
      // the schema as a prompt contract and validate the response in Chat instead.
      val request =
          if (responseSchema == null) prompt
          else
              "$prompt\n\nOUTPUT JSON SCHEMA:\n$responseSchema\nReturn only JSON matching this schema."
      val wrapped =
          if (expectedModel.isVision && bitmap == null)
              LlamaBridge.applyChatTemplate(
                  listOfNotNull(systemPrompt?.let { "system" to it }, "user" to request), true)
                  ?: ((systemPrompt?.let { "<|im_start|>system\n$it<|im_end|>\n" } ?: "") +
                      applyChatTemplate(expectedModel.chatTemplate, request))
          else applyChatTemplate(inferenceModel.chatTemplate, request)
      if (bytes != null) MultimodalBridge.analyzeImageBytesStream(bytes, wrapped, callback)
      else LlamaBridge.generateStream(wrapped, callback)
    } catch (e: Exception) {
      if (completed.compareAndSet(false, true)) continuation.resumeWithException(e)
    }
  }
  return result.toString().trim().also {
    check(it.isNotBlank()) { "The model returned no output." }
  }
}

fun GgufLlmManager.generateResponse(prompt: String, onToken: (String) -> Unit = {}): String =
    runBlocking {
      generateChatResponse(
          prompt,
          expectedModel = loadedModel ?: error("Select a GGUF model first."),
          onToken = onToken)
    }

private fun Bitmap.toJpegByteArray(): ByteArray {
  val stream = ByteArrayOutputStream()
  this.compress(Bitmap.CompressFormat.JPEG, 85, stream)
  return stream.toByteArray()
}
