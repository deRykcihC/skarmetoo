package com.deryk.skarmetoo.ai

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotTextEmbeddingDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisualSearchDeviceTest {
  @Test
  fun imageAndContextAreIndexedAndRetrievedAsOneImage(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.filesDir, "visual-search-fixture-${System.nanoTime()}.png")
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.RED)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    val library = ScreenshotDatabase(context)
    val index = ScreenshotTextEmbeddingDatabase(context, SearchModel.GEMMA2)
    val backend = SearchEmbeddingModel(context, SearchModel.GEMMA2)
    val uri = android.net.Uri.fromFile(file).toString()
    val report = JSONObject()
    try {
      val draft = ScreenshotEntry(imageUri = uri, imageHash = "visual-search-fixture", summary = "A solid red square")
      val entry = draft.copy(id = library.insertEntry(draft))
      assertTrue(backend.initialize())
      val start = android.os.SystemClock.elapsedRealtime()
      val vectors = backend.embedEntry(entry) ?: error(backend.getLastError() ?: "Embedding failed")
      val visual = requireNotNull(vectors.visual)
      assertEquals(768, vectors.context.size)
      assertEquals(768, visual.size)
      assertTrue(index.saveEmbedding(entry, EmbeddingGemma.buildSearchText(entry).hashCode(), vectors.context, visual))
      val query = backend.embedQuery("A red square")!!
      val matches = index.findSimilar(query, -1f, 10000).filter { it.first.imageUri == uri }
      assertEquals(1, matches.size)
      assertNotNull(matches.single().first.visualEmbedding)
      assertEquals(1, index.getIndexedEntryCount(listOf(entry)))
      report.put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
      report.put("context_dimensions", vectors.context.size)
      report.put("visual_dimensions", visual.size)
      report.put("results_for_fixture", matches.size)
      report.put("merged_score", matches.single().second)
      library.deleteEntriesByImageUris(listOf(uri))
      assertFalse(index.saveEmbedding(entry, 0, vectors.context, vectors.visual))
      assertFalse(index.getStoredVisualHashes().containsKey(uri))
      report.put("removed_and_late_write_rejected", true)
      report.put("passed", true)
    } catch (failure: Throwable) {
      report.put("error", failure.stackTraceToString())
      throw failure
    } finally {
      library.deleteEntriesByImageUris(listOf(uri))
      backend.close()
      index.close()
      library.close()
      file.delete()
      File(context.filesDir, "visual-search-report.json").writeText(report.toString(2))
    }
  }
}
