package com.deryk.skarmetoo.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmbeddingGemma2BackendDeviceTest {
  @Test
  fun textImageAndCombinedInference(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val backend = EmbeddingGemma2Backend(context)
    val report = JSONObject()
    fun validate(vector: FloatArray) {
      assertEquals(768, vector.size)
      assertTrue(vector.all { it.isFinite() })
      val norm = vector.sumOf { it.toDouble() * it }
      assertEquals(1.0, norm, 0.02)
    }
    suspend fun measured(name: String, block: suspend () -> FloatArray): FloatArray {
      val start = SystemClock.elapsedRealtime()
      val vector = block()
      report.put(name + "_ms", SystemClock.elapsedRealtime() - start)
      validate(vector)
      return vector
    }
    try {
      val start = SystemClock.elapsedRealtime()
      backend.initialize()
      report.put("initialize_ms", SystemClock.elapsedRealtime() - start)
      val query = measured("query") { backend.embedQuery("A red square") }
      val matching = measured("document") { backend.embedDocument("A solid red square on a white background") }
      val unrelated = measured("unrelated_document") { backend.embedDocument("Instructions for cooking pasta in boiling water") }
      val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(Color.RED)
      val bytes = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
      }
      bitmap.recycle()
      val image = measured("image") { backend.image(bytes) }
      measured("combined") { backend.embedImageAndText(bytes, "A solid red square") }
      val batch = backend.images(listOf(bytes, bytes))
      assertEquals(2, batch.size)
      batch.forEach { validate(it) }
      report.put("batch_count", batch.size)
      val matchScore = EmbeddingGemma2Backend.cosine(query, matching)
      val unrelatedScore = EmbeddingGemma2Backend.cosine(query, unrelated)
      report.put("matching_text_score", matchScore)
      report.put("unrelated_text_score", unrelatedScore)
      report.put("text_image_score", EmbeddingGemma2Backend.cosine(query, image))
      assertTrue("Matching text should rank above unrelated text", matchScore > unrelatedScore)
      backend.close()
      backend.initialize()
      measured("query_after_reopen") { backend.embedQuery("A red square") }
      report.put("passed", true)
    } catch (failure: Throwable) {
      report.put("error", failure.stackTraceToString())
      throw failure
    } finally {
      backend.close()
      File(context.filesDir, "embedding2-backend-smoke.json").writeText(report.toString(2))
    }
  }
}
