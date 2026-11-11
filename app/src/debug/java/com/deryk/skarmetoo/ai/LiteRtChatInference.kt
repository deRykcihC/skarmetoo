package com.deryk.skarmetoo.ai

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock

/** Request-owned conversation; serialized with single-engine screenshot analysis. */
suspend fun LlmManager.generateChatResponse(
    prompt: String,
    bitmap: Bitmap? = null,
    onToken: (String) -> Unit = {},
): String =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
      check(acquireInferenceSlot()) { "Load a Gemma model in Settings first." }
      try {
        engineMutex.withLock {
          val activeEngine = engine ?: error("Model is no longer available")
          val chat =
              activeEngine.createConversation(
                  ConversationConfig(
                      samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2)))
          try {
            val contents = mutableListOf<Content>()
            if (bitmap != null) {
              val resized = resizeBitmap(bitmap, 1536)
              try {
                contents.add(Content.ImageBytes(resized.toJpegByteArray()))
              } finally {
                if (resized !== bitmap) resized.recycle()
              }
            }
            contents.add(Content.Text(prompt))
            // Keep the engine locked until native completion, even if the UI cancels the request.
            suspendCoroutine<String> { continuation ->
              val finished = java.util.concurrent.atomic.AtomicBoolean(false)
              val result = StringBuilder()
              try {
                chat.sendMessageAsync(
                    Contents.of(contents),
                    object : MessageCallback {
                      override fun onMessage(message: Message) {
                        val token = message.toString()
                        result.append(token)
                        onToken(token)
                      }

                      override fun onDone() {
                        if (finished.compareAndSet(false, true))
                            continuation.resume(result.toString().trim())
                      }

                      override fun onError(throwable: Throwable) {
                        if (finished.compareAndSet(false, true))
                            continuation.resumeWithException(throwable)
                      }
                    })
              } catch (e: Exception) {
                if (finished.compareAndSet(false, true)) continuation.resumeWithException(e)
              }
            }
          } finally {
            chat.close()
          }
        }
      } finally {
        releaseInferenceSlot()
      }
    }

private fun Bitmap.toJpegByteArray(): ByteArray {
  val stream = ByteArrayOutputStream()
  this.compress(Bitmap.CompressFormat.JPEG, 85, stream)
  return stream.toByteArray()
}
