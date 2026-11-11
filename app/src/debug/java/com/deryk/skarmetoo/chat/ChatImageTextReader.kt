package com.deryk.skarmetoo.chat

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Bundled on-device reading aid, invoked only for candidates accepted by the image verifier. */
class ChatImageTextReader : AutoCloseable {
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  suspend fun read(bitmap: Bitmap): String = suspendCoroutine { continuation ->
    // Wait for the native task to finish before the caller can recycle its bitmap or close us.
    recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(
        Executor { it.run() }) { task ->
          if (task.isSuccessful)
              continuation.resume(
                  ChatTextReadingOrder.text(
                      task.result.textBlocks
                          .flatMap { it.lines }
                          .mapNotNull { line ->
                            line.boundingBox?.let {
                              ChatTextLine(line.text, it.left, it.top, it.bottom)
                            }
                          }))
          else
              continuation.resumeWithException(
                  task.exception ?: IllegalStateException("Image text could not be read."))
        }
  }

  override fun close() = recognizer.close()
}
