package com.deryk.skarmetoo.ai.agentic

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Explicitly imports and indexes only the five files requested by the user. */
class AgenticImportScreenshotsDeviceTest {
  @Test
  fun importAndIndex(): Unit = runBlocking(Dispatchers.IO) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val names = InstrumentationRegistry.getArguments().getString("filenames")?.split(",") ?: listOf("10724.jpg", "10723.jpg", "10721.jpg", "10580.jpg", "10559.jpg")
    val database = ScreenshotDatabase(context)
    val pipeline = AgenticPipeline(context)
    val imported = mutableListOf<ScreenshotEntry>()
    val records = JSONArray()
    try {
      for (name in names) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.query(collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(name, "Pictures/Screenshots/"), null)!!.use { cursor ->
          check(cursor.moveToFirst()) { "Missing screenshot $name in MediaStore" }
          val found = ContentUris.withAppendedId(collection, cursor.getLong(0))
          check(!cursor.moveToNext()) { "Ambiguous duplicate name $name" }
          found
        }
        val hash = context.contentResolver.openInputStream(uri)!!.use { EmbeddingGemma2.sha256(it) }
        val existing = database.getEntryByImageUri(uri.toString()) ?: database.getEntryByHash(hash)
        val entry = if (existing != null) {
          if (existing.imageUri != uri.toString()) database.linkImageToHash(hash, uri.toString())
          database.getEntryByHash(hash)!!
        } else {
          val id = database.insertEntry(ScreenshotEntry(imageUri=uri.toString(), imageHash=hash))
          database.getEntryById(id)!!
        }
        imported.add(entry)
        records.put(JSONObject().put("filename",name).put("uri",uri.toString()).put("hash",hash).put("entry_id",entry.id))
      }
      pipeline.indexImages(imported)
      val indexed = pipeline.index.images()
      assertEquals(names.size, imported.count { entry -> indexed.any { it.uri == entry.imageUri && it.id == entry.imageHash } })
      File(context.filesDir,"import-screenshots-report.json").writeText(JSONObject()
          .put("imported",records).put("verified_indexed",names.size).put("library_entries",database.getAllEntries().size)
          .put("index_count",indexed.size).toString(2))
    } finally {
      pipeline.close()
      pipeline.index.close()
      database.close()
    }
  }
}
