package com.deryk.skarmetoo.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

enum class SearchModel(val label: String, val databaseName: String) {
  ORIGINAL("EmbeddingGemma · 300M", "screenshot_text_embeddings.db"),
  GEMMA2("EmbeddingGemma 2 · 440M", "search_embeddinggemma2_e301f74d_768_text_v1.db");

  fun available(context: Context): Boolean =
      if (this == ORIGINAL) EmbeddingGemma.hasRequiredFiles(context)
      else EmbeddingGemma2Backend(context).available

  companion object {
    fun selected(context: Context): SearchModel =
        entries.firstOrNull {
          it.name == context.getSharedPreferences("search_model", 0).getString("selected", null)
        } ?: ORIGINAL

    fun select(context: Context, model: SearchModel) {
      context
          .getSharedPreferences("search_model", 0)
          .edit()
          .putString("selected", model.name)
          .apply()
    }
  }
}

/** A fixed model identity for an entire indexing/search operation. */
class SearchEmbeddingModel(private val context: Context, val kind: SearchModel) {
  private val original = if (kind == SearchModel.ORIGINAL) EmbeddingGemma(context) else null
  private val modern = if (kind == SearchModel.GEMMA2) EmbeddingGemma2Backend(context) else null
  private var error: String? = null

  suspend fun initialize(): Boolean =
      attempt {
        original?.initialize()
            ?: run {
              modern!!.initialize()
              true
            }
      } ?: false

  suspend fun embed(text: String): FloatArray? = embedQuery(text)

  suspend fun embedQuery(text: String): FloatArray? = attempt {
    original?.embedQuery(text) ?: if (modern != null) modern.embedQuery(text) else null
  }

  suspend fun embedDocument(text: String): FloatArray? = attempt {
    original?.embedDocument(text) ?: if (modern != null) modern.embedDocument(text) else null
  }

  data class EntryVectors(val context: FloatArray, val visual: FloatArray?)

  suspend fun embedEntry(entry: com.deryk.skarmetoo.data.ScreenshotEntry): EntryVectors? = attempt {
    val text = EmbeddingGemma.buildSearchText(entry)
    val textVector =
        if (text.isBlank() && modern != null) floatArrayOf()
        else embedDocument(text) ?: error(getLastError() ?: "Context embedding failed")
    val visual =
        if (modern != null) {
          val bytes =
              kotlinx.coroutines.withContext(Dispatchers.IO) {
                val uri = android.net.Uri.parse(entry.imageUri)
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                  android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode image" }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val options =
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap =
                    context.contentResolver.openInputStream(uri)?.use {
                      android.graphics.BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Cannot read image")
                try {
                  java.io.ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                  }
                } finally {
                  bitmap.recycle()
                }
              }
          modern.image(bytes)
        } else null
    EntryVectors(textVector, visual)
  }

  private suspend fun <T> attempt(block: suspend () -> T): T? =
      try {
        error = null
        block()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (failure: Exception) {
        error = failure.localizedMessage
        null
      }

  fun getLastError(): String? = error ?: original?.getLastError()

  fun close() {
    original?.close()
    modern?.let { runBlocking(Dispatchers.IO) { it.close() } }
  }
}
