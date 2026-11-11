package com.deryk.skarmetoo.data

import android.app.Application
import android.content.Context
import android.net.Uri
import com.deryk.skarmetoo.ai.EmbeddingGemma
import com.deryk.skarmetoo.ai.EmbeddingGemmaSkippedStore
import com.deryk.skarmetoo.ai.agentic.AgenticImage
import com.deryk.skarmetoo.ai.agentic.AgenticIndex
import com.deryk.skarmetoo.ai.agentic.EmbeddingGemma2
import com.deryk.skarmetoo.chat.ChatWorkflowCache
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class ScreenshotLibraryTest {
  private lateinit var context: Context
  private lateinit var db: ScreenshotDatabase
  private lateinit var text: ScreenshotTextEmbeddingDatabase
  private lateinit var visual: ScreenshotVectorDatabase
  private lateinit var agentic: AgenticIndex
  private lateinit var chat: ChatWorkflowCache

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    db = ScreenshotDatabase(context)
    text = ScreenshotTextEmbeddingDatabase(context)
    visual = ScreenshotVectorDatabase(context)
    agentic = AgenticIndex(context)
    chat = ChatWorkflowCache(context)
  }

  @After
  fun close() {
    chat.close()
    agentic.close()
    visual.close()
    text.close()
    db.close()
  }

  private fun entry(uri: String, hash: String = uri): ScreenshotEntry {
    val entry =
        ScreenshotEntry(
            imageUri = uri,
            imageHash = hash,
            summary = "Saved analysis $uri",
            tags = "receipt",
            note = "Saved note",
            analyzedAt = 10)
    return entry.copy(id = db.insertEntry(entry))
  }

  private fun index(entry: ScreenshotEntry) = runBlocking {
    assertTrue(
        text.saveEmbedding(
            entry, EmbeddingGemma.buildSearchText(entry).hashCode(), floatArrayOf(1f, 0f)))
    visual.saveEmbedding(entry.imageHash, entry.imageUri, floatArrayOf(1f, 0f))
    agentic.put(
        AgenticImage(
            "bytes-${entry.id}",
            entry.id,
            entry.imageUri,
            "bytes-${entry.id}",
            null,
            FloatArray(EmbeddingGemma2.DIMENSIONS),
            "Saved OCR",
            10,
            1))
    chat.put("response-${entry.id}", "Saved extraction", entry)
  }

  @Test
  fun removingOneDuplicateKeepsTheOtherCopyAndItsAnalysis() = runBlocking {
    val removed = entry("content://images/one", "same-dhash")
    val retained = entry("content://images/two", "same-dhash")
    index(removed)
    index(retained)
    EmbeddingGemmaSkippedStore.saveEntryIds(context, setOf(removed.id, retained.id))

    db.deleteEntry(removed.id)

    assertNull(db.getEntryById(removed.id))
    assertEquals(retained, db.getEntryById(retained.id))
    assertEquals(setOf(retained.imageUri), text.getStoredContentHashes().keys)
    assertEquals(setOf(retained.imageUri), visual.getAllStoredUris())
    assertEquals(listOf(retained.imageUri), agentic.images().map { it.uri })
    assertNull(chat.get("response-${removed.id}"))
    assertEquals("Saved extraction", chat.get("response-${retained.id}"))
    assertEquals(setOf(retained.id), EmbeddingGemmaSkippedStore.getEntryIds(context))
  }

  @Test
  fun removingFolderALeavesFolderBAndItsIndexesUntouched() = runBlocking {
    val root = "content://com.android.externalstorage.documents/tree/primary%3A"
    val folderA = "${root}A"
    val folderB = "${root}B"
    val a = entry("$folderA/document/primary%3AA%2Fone.jpg")
    val b = entry("$folderB/document/primary%3AB%2Ftwo.jpg")
    index(a)
    index(b)

    assertEquals(1, db.removeSourceFolders(setOf(folderA), setOf(folderB)))

    assertEquals(listOf(b), db.getAllEntries())
    assertEquals(setOf(b.imageUri), text.getStoredContentHashes().keys)
    assertEquals(setOf(b.imageUri), visual.getAllStoredUris())
    assertEquals(listOf(b.imageUri), agentic.images().map { it.uri })
  }

  @Test
  fun overlappingFolderRetainsAnalysisAndRebindsAllIndexesToItsGrant() = runBlocking {
    val parent = "content://com.android.externalstorage.documents/tree/primary%3APictures"
    val child = "$parent%2FReceipts"
    val original = entry("$parent/document/primary%3APictures%2FReceipts%2Fone.jpg")
    index(original)

    assertEquals(0, db.removeSourceFolders(setOf(parent), setOf(child)))
    val expectedUri = "$child/document/primary%3APictures%2FReceipts%2Fone.jpg"
    val retained = requireNotNull(db.getEntryById(original.id))
    assertEquals(original.copy(imageUri = expectedUri), retained)
    assertEquals(setOf(expectedUri), text.getStoredContentHashes().keys)
    assertEquals(setOf(expectedUri), visual.getAllStoredUris())
    assertEquals(listOf(expectedUri), agentic.images().map { it.uri })
    chat.pruneToUris(setOf(expectedUri))
    assertEquals("Saved extraction", chat.get("response-${original.id}"))
  }

  @Test
  fun deletedFileIsPurgedWhileReadableFileKeepsItsAnalysis() = runBlocking {
    val gone = File(context.filesDir, "gone.jpg").apply { writeText("image") }
    val kept = File(context.filesDir, "kept.jpg").apply { writeText("image") }
    val removed = entry(Uri.fromFile(gone).toString())
    val retained = entry(Uri.fromFile(kept).toString())
    index(removed)
    index(retained)
    assertTrue(gone.delete())

    assertEquals(1, ScreenshotLibrary.removeUnavailableImages(context))
    assertEquals(listOf(retained), db.getAllEntries())
    assertEquals(setOf(retained.imageUri), text.getStoredContentHashes().keys)
    assertEquals(setOf(retained.imageUri), visual.getAllStoredUris())
    assertNull(chat.get("response-${removed.id}"))
  }

  @Test
  fun lateIndexWritesCannotRecreateRemovedImageData() = runBlocking {
    val removed = entry("content://images/gone")
    index(removed)
    val revision = ScreenshotLibrary.removalRevision.value
    db.deleteEntry(removed.id)

    assertFalse(text.saveEmbedding(removed, 1, floatArrayOf(1f)))
    visual.saveEmbedding(removed.imageHash, removed.imageUri, floatArrayOf(1f))
    agentic.put(
        AgenticImage(
            "late",
            removed.id,
            removed.imageUri,
            "late",
            null,
            FloatArray(EmbeddingGemma2.DIMENSIONS),
            "Late OCR",
            10,
            1))
    chat.put("late", "Late extraction", removed)
    agentic.saveRun("old question", 0, JSONObject().put("ocr", "Late OCR"), revision)

    assertEquals(0, text.getStoredEmbeddingCount())
    assertEquals(0, visual.getStoredEmbeddingCount())
    assertTrue(agentic.images().isEmpty())
    assertNull(chat.get("late"))
    assertEquals(0, count(agentic.readableDatabase, "runs"))
  }

  @Test
  fun startupRepairPurgesOrphanedVectorsAndCachedFacts() = runBlocking {
    val removed = entry("content://images/orphan")
    val kept = entry("content://images/kept")
    index(removed)
    index(kept)
    // Reproduce data written by an older app version which only deleted gallery rows.
    db.writableDatabase.delete("screenshots", "id=?", arrayOf(removed.id.toString()))
    agentic.writableDatabase.execSQL(
        "INSERT INTO verifications VALUES ('bytes-${removed.id}', 'old', 'plan', '{}', 0)")
    agentic.writableDatabase.execSQL(
        "INSERT INTO payment_facts VALUES ('bytes-${removed.id}', 'old', '{}', 0)")
    agentic.saveRun("old question", 0, JSONObject().put("ocr", "Old OCR"))

    db.pruneOrphanedIndexes()

    assertEquals(setOf(kept.imageUri), text.getStoredContentHashes().keys)
    assertEquals(setOf(kept.imageUri), visual.getAllStoredUris())
    assertEquals(listOf(kept.imageUri), agentic.images().map { it.uri })
    assertEquals(0, count(agentic.readableDatabase, "verifications"))
    assertEquals(0, count(agentic.readableDatabase, "payment_facts"))
    assertEquals(0, count(agentic.readableDatabase, "runs"))
  }

  @Test
  fun clearingLibraryRemovesAllDerivedDataAndHashRecords() = runBlocking {
    val image = entry("content://images/one")
    index(image)
    chat.put("plan", "Image independent plan")
    db.deleteAllEntries()
    assertTrue(db.getAllEntries().isEmpty())
    assertEquals(0, text.getStoredEmbeddingCount())
    assertEquals(0, visual.getStoredEmbeddingCount())
    assertTrue(agentic.images().isEmpty())
    assertNull(chat.get("response-${image.id}"))
    assertEquals("Image independent plan", chat.get("plan"))
  }

  @Test
  fun countOnlyIncludesFreshEmbeddingsForCurrentSearchableImages() = runBlocking {
    val image = entry("content://images/one")
    index(image)
    assertEquals(1, text.getIndexedEntryCount(db.getAllEntries()))
    db.updateSummary(image.id, "Updated analysis")
    assertEquals(0, text.getIndexedEntryCount(db.getAllEntries()))
    assertEquals(0, text.getIndexedEntryCount(emptyList()))
  }

  @Test
  fun oldUntraceableChatCacheIsClearedOnMigration() {
    context.openOrCreateDatabase("old_chat.db", 0, null).use { old ->
      old.execSQL(
          "CREATE TABLE responses (cache_key TEXT PRIMARY KEY, response TEXT NOT NULL, used_at INTEGER NOT NULL)")
      old.execSQL("INSERT INTO responses VALUES ('old', 'Saved image context', 0)")
      old.version = 1
    }
    ChatWorkflowCache(context, "old_chat.db").use { migrated ->
      assertNull(migrated.get("old"))
      migrated.put("new", "New plan")
      assertEquals("New plan", migrated.get("new"))
    }
  }

  private fun count(database: android.database.sqlite.SQLiteDatabase, table: String): Int =
      database.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        it.moveToFirst()
        it.getInt(0)
      }
}
