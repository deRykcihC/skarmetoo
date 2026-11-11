package com.deryk.skarmetoo.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
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
class AlbumReanalysisTest {
  private lateinit var db: ScreenshotDatabase

  @Before
  fun setup() {
    val context: Context = RuntimeEnvironment.getApplication()
    db = ScreenshotDatabase(context)
  }

  @After
  fun close() {
    db.close()
  }

  private fun saved(uri: String, hash: String = uri) =
      ScreenshotEntry(
          imageUri = uri,
          imageHash = hash,
          summary = "Saved description",
          tags = "saved",
          note = "My note",
          analyzedAt = 123,
          modelUsed = "Previous model")

  private fun insert(entry: ScreenshotEntry) = entry.copy(id = db.insertEntry(entry))

  @Test
  fun visualAndContextMatchesReturnOneImageAndVisualOnlyImagesSurvivePruning() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val modernModel = com.deryk.skarmetoo.ai.SearchModel.GEMMA2
    val entry = insert(saved("content://images/dual-vectors"))
    val visualOnly =
        insert(ScreenshotEntry(imageUri = "content://images/visual-only", imageHash = "visual"))
    val query = FloatArray(768).apply { this[0] = 1f }
    val other = FloatArray(768).apply { this[1] = 1f }
    ScreenshotTextEmbeddingDatabase(context, modernModel).use { index ->
      assertTrue(
          index.saveEmbedding(
              entry,
              com.deryk.skarmetoo.ai.EmbeddingGemma.buildSearchText(entry).hashCode(),
              other,
              query))
      assertTrue(index.saveEmbedding(visualOnly, 0, floatArrayOf(), query))
      val matches = index.findSimilar(query, 0.9f)
      assertEquals(
          setOf(entry.imageUri, visualOnly.imageUri), matches.map { it.first.imageUri }.toSet())
      assertEquals(2, matches.size)
      assertEquals(2, index.getIndexedEntryCount(listOf(entry, visualOnly)))
      synchronized(ScreenshotLibrary.lock) {
        ScreenshotLibrary.prune(context, listOf(entry, visualOnly))
      }
      assertEquals(2, index.getStoredEmbeddingCount())
      assertTrue(
          index.saveEmbedding(
              entry,
              com.deryk.skarmetoo.ai.EmbeddingGemma.buildSearchText(entry).hashCode(),
              query,
              other))
      assertEquals(1, index.findSimilar(query, 0.9f).count { it.first.imageUri == entry.imageUri })
      db.deleteEntriesByImageUris(listOf(entry.imageUri))
      assertEquals(1, index.getStoredEmbeddingCount())
      assertFalse(index.saveEmbedding(entry, 0, other, query))
      index.clearAll()
      assertTrue(index.getStoredVisualHashes().isEmpty())
    }
  }

  @Test
  fun versionOneContextIndexMigratesWithoutLosingVectors() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val model = com.deryk.skarmetoo.ai.SearchModel.GEMMA2
    val file = context.getDatabasePath(model.databaseName)
    file.parentFile!!.mkdirs()
    android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
      old.execSQL(
          "CREATE TABLE text_embeddings (image_uri TEXT PRIMARY KEY, entry_id INTEGER NOT NULL, content_hash INTEGER NOT NULL, vector_blob BLOB NOT NULL)")
      old.execSQL(
          "INSERT INTO text_embeddings VALUES ('content://images/old', 1, 123, X'0000803F00000000')")
      old.version = 1
    }
    ScreenshotTextEmbeddingDatabase(context, model).use { index ->
      assertEquals(1, index.getStoredEmbeddingCount())
      assertEquals(123, index.getStoredContentHashes()["content://images/old"])
      assertTrue(index.getStoredVisualHashes().isEmpty())
      assertEquals(1, index.findSimilar(floatArrayOf(1f, 0f), 0.9f).size)
    }
  }

  @Test
  fun searchModelsKeepSeparateIndexesAndBothArePrunedOnRemoval() = runBlocking {
    val context = RuntimeEnvironment.getApplication()
    val entry = insert(saved("content://images/model-separation"))
    ScreenshotTextEmbeddingDatabase(context).use { original ->
      ScreenshotTextEmbeddingDatabase(context, com.deryk.skarmetoo.ai.SearchModel.GEMMA2).use {
          modern ->
        assertTrue(original.saveEmbedding(entry, 123, floatArrayOf(1f, 0f)))
        assertEquals(0, modern.getStoredEmbeddingCount())
        assertTrue(modern.saveEmbedding(entry, 123, floatArrayOf(0f, 1f)))
        modern.clearAll()
        assertEquals(1, original.getStoredEmbeddingCount())
        assertTrue(modern.saveEmbedding(entry, 123, floatArrayOf(0f, 1f)))
        db.deleteEntriesByImageUris(listOf(entry.imageUri))
        assertEquals(0, original.getStoredEmbeddingCount())
        assertEquals(0, modern.getStoredEmbeddingCount())
        assertFalse(modern.saveEmbedding(entry, 123, floatArrayOf(0f, 1f)))
      }
    }
  }

  @Test
  fun freshEmbeddingReplacesTheOldVectorEvenWhenContextHashIsUnchanged() = runBlocking {
    val selected = insert(saved("content://images/reindex-one"))
    val other = insert(saved("content://images/reindex-two"))
    ScreenshotTextEmbeddingDatabase(RuntimeEnvironment.getApplication()).use { index ->
      assertTrue(index.saveEmbedding(selected, 123, floatArrayOf(1f, 0f)))
      assertTrue(index.saveEmbedding(other, 456, floatArrayOf(1f, 0f)))
      assertTrue(index.saveEmbedding(selected, 123, floatArrayOf(0f, 1f)))
      val records =
          index.findSimilar(floatArrayOf(1f, 0f), similarityThreshold = -1f).associate {
            it.first.imageUri to it.first
          }
      assertEquals(2, index.getStoredEmbeddingCount())
      assertArrayEquals(floatArrayOf(0f, 1f), records.getValue(selected.imageUri).embedding, 0f)
      assertArrayEquals(floatArrayOf(1f, 0f), records.getValue(other.imageUri).embedding, 0f)
      assertEquals(123, records.getValue(selected.imageUri).contentHash)
    }
  }

  @Test
  fun scopeIncludesSavedMediaAndSafEntriesButNotNeverAnalyzedImages() {
    val media = saved("content://media/external/images/media/1").copy(id = 1)
    val saf =
        saved(
                "content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2FReceipts%2Fone.jpg")
            .copy(id = 2)
    val fresh =
        ScreenshotEntry(
            id = 3,
            imageUri = "content://media/external/images/media/3",
            imageHash = "new",
            note = "Only a user note")
    val ids =
        AlbumReanalysisScope.entryIds(
            listOf(media, saf, fresh),
            setOf(media.imageUri, fresh.imageUri),
            setOf("primary:Pictures/Receipts/one.jpg"))
    assertEquals(setOf(1L, 2L), ids)
  }

  @Test
  fun sameHashInAnotherAlbumIsNotSelected() {
    val selected = saved("content://media/external/images/media/1", "same-dhash").copy(id = 1)
    val other = saved("content://media/external/images/media/2", "same-dhash").copy(id = 2)
    assertEquals(
        setOf(1L),
        AlbumReanalysisScope.entryIds(
            listOf(selected, other), setOf(selected.imageUri), emptySet()))
  }

  @Test
  fun identicalNamesOnDifferentVolumesStaySeparate() {
    val primary =
        saved("content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fone.jpg")
            .copy(id = 1)
    val sd =
        saved("content://com.android.externalstorage.documents/document/ABCD-1234%3ADCIM%2Fone.jpg")
            .copy(id = 2)
    val identity =
        AlbumReanalysisScope.documentIdForPath(
            "/storage/ABCD-1234/DCIM/one.jpg", "/storage/emulated/0")
    assertEquals("abcd-1234:DCIM/one.jpg", identity)
    assertEquals(
        setOf(2L),
        AlbumReanalysisScope.entryIds(
            listOf(primary, sd), emptySet(), setOf(requireNotNull(identity))))
    assertEquals(
        "primary:DCIM/one.jpg",
        AlbumReanalysisScope.documentIdForPath(
            "/storage/emulated/0/DCIM/one.jpg", "/storage/emulated/0"))
    assertNull(
        AlbumReanalysisScope.documentIdForPath(
                "/storage/emulated/01/DCIM/one.jpg", "/storage/emulated/0")
            ?.takeIf { it.startsWith("primary:") })
  }

  @Test
  fun queuePreservesContextAndNotesAndDoesNotResetOtherAlbums() {
    val selected = insert(saved("content://images/one"))
    val other = insert(saved("content://images/two"))
    db.setAnalyzing(selected.id, true)
    assertEquals(1, db.markForReanalysis(setOf(selected.id, 999999)))
    assertEquals(selected.copy(analyzedAt = 0), db.getEntryById(selected.id))
    assertEquals(other, db.getEntryById(other.id))
    assertTrue(requireNotNull(db.getEntryById(selected.id)).isDesktopAnalysisCandidate())
  }

  @Test
  fun scopedDesktopResultCannotOverwriteAnUnselectedHashDuplicateOrUserNotes() {
    val selected = insert(saved("content://images/one", "same"))
    val other = insert(saved("content://images/two", "same"))
    db.markForReanalysis(setOf(selected.id))
    db.applyLanResult(
        "same",
        "New context",
        "new",
        456,
        "",
        "Desktop",
        allowedEntryIds = setOf(selected.id),
        preserveNotes = true)
    assertEquals("New context", db.getEntryById(selected.id)?.summary)
    assertEquals("My note", db.getEntryById(selected.id)?.note)
    assertEquals(other, db.getEntryById(other.id))
    assertFalse(requireNotNull(db.getEntryById(selected.id)).isDesktopAnalysisCandidate())
  }

  @Test
  fun largeAlbumsAvoidSqlParameterLimitsAndDeletedRowsStayDeleted() {
    val entries = (1..1100).map { insert(saved("content://images/$it")) }
    val removed = entries.first()
    db.deleteEntry(removed.id)
    assertEquals(1099, db.markForReanalysis(entries.mapTo(hashSetOf()) { it.id }))
    assertNull(db.getEntryById(removed.id))
    assertTrue(db.getAllEntries().all { it.analyzedAt == 0L && it.summary == "Saved description" })
  }
}
