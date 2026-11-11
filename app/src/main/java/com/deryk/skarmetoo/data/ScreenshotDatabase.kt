package com.deryk.skarmetoo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ScreenshotDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
  private val appContext = context.applicationContext

  companion object {
    private const val DB_NAME = "screenshots.db"
    private const val DB_VERSION = 3
    private const val TABLE = "screenshots"

    private const val COL_ID = "id"
    private const val COL_IMAGE_URI = "image_uri"
    private const val COL_IMAGE_HASH = "image_hash"
    private const val COL_SUMMARY = "summary"
    private const val COL_TAGS = "tags"
    private const val COL_ANALYZED_AT = "analyzed_at"
    private const val COL_IS_ANALYZING = "is_analyzing"
    private const val COL_NOTE = "note"
    private const val COL_MODEL_USED = "model_used"
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_IMAGE_URI TEXT NOT NULL,
                $COL_IMAGE_HASH TEXT NOT NULL,
                $COL_SUMMARY TEXT DEFAULT '',
                $COL_TAGS TEXT DEFAULT '',
                $COL_ANALYZED_AT INTEGER DEFAULT 0,
                $COL_IS_ANALYZING INTEGER DEFAULT 0,
                $COL_NOTE TEXT DEFAULT '',
                $COL_MODEL_USED TEXT DEFAULT ''
            )
        """,
    )
    db.execSQL("CREATE INDEX idx_hash ON $TABLE ($COL_IMAGE_HASH)")
  }

  override fun onUpgrade(
      db: SQLiteDatabase,
      oldVersion: Int,
      newVersion: Int,
  ) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_NOTE TEXT DEFAULT ''")
    }
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_MODEL_USED TEXT DEFAULT ''")
    }
  }

  fun insertEntry(entry: ScreenshotEntry): Long =
      synchronized(ScreenshotLibrary.lock) {
        val db = writableDatabase
        val values =
            ContentValues().apply {
              put(COL_IMAGE_URI, entry.imageUri)
              put(COL_IMAGE_HASH, entry.imageHash)
              put(COL_SUMMARY, entry.summary)
              put(COL_TAGS, entry.tags)
              put(COL_ANALYZED_AT, entry.analyzedAt)
              put(COL_IS_ANALYZING, if (entry.isAnalyzing) 1 else 0)
              put(COL_NOTE, entry.note)
              put(COL_MODEL_USED, entry.modelUsed)
            }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }

  fun updateAnalysis(
      id: Long,
      summary: String,
      tags: String,
      modelUsed: String,
  ) {
    val db = writableDatabase
    val values =
        ContentValues().apply {
          put(COL_SUMMARY, summary)
          put(COL_TAGS, tags)
          put(COL_ANALYZED_AT, System.currentTimeMillis())
          put(COL_IS_ANALYZING, 0)
          put(COL_MODEL_USED, modelUsed)
        }
    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun setAnalyzing(
      id: Long,
      analyzing: Boolean,
  ) {
    val db = writableDatabase
    val values = ContentValues().apply { put(COL_IS_ANALYZING, if (analyzing) 1 else 0) }
    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun resetAllAnalyzingFlags() {
    val db = writableDatabase
    val values = ContentValues().apply { put(COL_IS_ANALYZING, 0) }
    db.update(TABLE, values, null, null)
  }

  /** Queue only these existing images, preserving their context and notes until success. */
  fun markForReanalysis(ids: Set<Long>): Int =
      synchronized(ScreenshotLibrary.lock) {
        if (ids.isEmpty()) return@synchronized 0
        val db = writableDatabase
        db.beginTransaction()
        try {
          val values =
              ContentValues().apply {
                put(COL_ANALYZED_AT, 0L)
                put(COL_IS_ANALYZING, 0)
              }
          var changed = 0
          for (id in ids) changed += db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
          db.setTransactionSuccessful()
          changed
        } finally {
          db.endTransaction()
        }
      }

  fun updateSummary(
      id: Long,
      summary: String,
  ) {
    val db = writableDatabase
    val values = ContentValues().apply { put(COL_SUMMARY, summary) }
    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun updateTags(
      id: Long,
      tags: String,
  ) {
    val db = writableDatabase
    val values = ContentValues().apply { put(COL_TAGS, tags) }
    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun updateNote(
      id: Long,
      note: String,
  ) {
    val db = writableDatabase
    val values = ContentValues().apply { put(COL_NOTE, note) }
    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
  }

  fun deleteEntry(id: Long) {
    deleteMatching("$COL_ID = ?", arrayOf(id.toString()))
  }

  fun deleteAllEntries() {
    deleteMatching(null, null)
  }

  private fun deleteMatching(selection: String?, args: Array<String>?): Int =
      synchronized(ScreenshotLibrary.lock) {
        val deleted = writableDatabase.delete(TABLE, selection, args)
        ScreenshotLibrary.prune(appContext, getAllEntries(), imageRemoved = deleted > 0)
        if (deleted > 0) ScreenshotLibrary.markRemoved()
        deleted
      }

  fun pruneOrphanedIndexes() =
      synchronized(ScreenshotLibrary.lock) { ScreenshotLibrary.prune(appContext, getAllEntries()) }

  fun rebindImageUri(id: Long, newUri: String) =
      synchronized(ScreenshotLibrary.lock) {
        val entry = getEntryById(id) ?: return@synchronized
        if (entry.imageUri == newUri) return@synchronized
        val values = ContentValues().apply { put(COL_IMAGE_URI, newUri) }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        ScreenshotLibrary.rebindUri(appContext, entry.imageUri, newUri)
      }

  fun removeSourceFolders(removedFolders: Set<String>, remainingFolders: Set<String>): Int =
      synchronized(ScreenshotLibrary.lock) {
        val removedUris = mutableListOf<String>()
        for (entry in getAllEntries()) {
          if (removedFolders.none { ScreenshotSourceScope.isWithinTree(entry.imageUri, it) })
              continue
          val retainedFolder =
              remainingFolders.firstOrNull {
                ScreenshotSourceScope.isWithinTree(entry.imageUri, it)
              }
          if (retainedFolder == null) {
            removedUris.add(entry.imageUri)
          } else {
            val uri =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    android.net.Uri.parse(retainedFolder),
                    android.provider.DocumentsContract.getDocumentId(
                        android.net.Uri.parse(entry.imageUri)))
            rebindImageUri(entry.id, uri.toString())
          }
        }
        deleteEntriesByImageUris(removedUris)
      }

  fun getEntryByImageUri(imageUri: String): ScreenshotEntry? {
    val cursor =
        readableDatabase.query(
            TABLE,
            null,
            "$COL_IMAGE_URI = ?",
            arrayOf(imageUri),
            null,
            null,
            null,
        )
    return cursor.use { if (it.moveToFirst()) cursorToEntry(it) else null }
  }

  fun deleteEntriesByImageUris(imageUris: Collection<String>): Int =
      synchronized(ScreenshotLibrary.lock) {
        if (imageUris.isEmpty()) return@synchronized 0
        val db = writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
          for (uri in imageUris) {
            deleted += db.delete(TABLE, "$COL_IMAGE_URI = ?", arrayOf(uri))
          }
          db.setTransactionSuccessful()
        } finally {
          db.endTransaction()
        }
        ScreenshotLibrary.prune(appContext, getAllEntries(), imageRemoved = deleted > 0)
        if (deleted > 0) ScreenshotLibrary.markRemoved()
        deleted
      }

  fun deleteEntriesWithEmptyHash(): Int {
    return deleteMatching("TRIM($COL_IMAGE_HASH) = ''", null)
  }

  fun getEntryByHash(hash: String): ScreenshotEntry? {
    val cursor =
        readableDatabase.query(
            TABLE,
            null,
            "$COL_IMAGE_HASH = ?",
            arrayOf(hash),
            null,
            null,
            null,
        )
    return cursor.use { if (it.moveToFirst()) cursorToEntry(it) else null }
  }

  fun getEntryById(id: Long): ScreenshotEntry? {
    val cursor =
        readableDatabase.query(
            TABLE,
            null,
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        )
    return cursor.use { if (it.moveToFirst()) cursorToEntry(it) else null }
  }

  fun getAllEntries(): List<ScreenshotEntry> {
    val entries = mutableListOf<ScreenshotEntry>()
    val cursor =
        readableDatabase.query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "$COL_ID DESC",
        )
    cursor.use {
      while (it.moveToNext()) {
        entries.add(cursorToEntry(it))
      }
    }
    return entries
  }

  fun searchEntries(query: String): List<ScreenshotEntry> {
    val entries = mutableListOf<ScreenshotEntry>()
    val searchQuery = "%$query%"
    val cursor =
        readableDatabase.query(
            TABLE,
            null,
            "$COL_SUMMARY LIKE ? OR $COL_TAGS LIKE ? OR $COL_NOTE LIKE ?",
            arrayOf(searchQuery, searchQuery, searchQuery),
            null,
            null,
            "$COL_ID DESC",
        )
    cursor.use {
      while (it.moveToNext()) {
        entries.add(cursorToEntry(it))
      }
    }
    return entries
  }

  fun getEntryCount(): Int {
    val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
    return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
  }

  fun getAnalyzedCount(): Int {
    val cursor =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_ANALYZED_AT > 0",
            null,
        )
    return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
  }

  /**
   * Import entry by hash. If hash exists, update metadata. If hash doesn't exist, insert with empty
   * URI (unlinked).
   */
  fun importEntry(
      hash: String,
      summary: String,
      tags: String,
      analyzedAt: Long,
      note: String = "",
      modelUsed: String = "",
  ) {
    val existing = getEntryByHash(hash)
    if (existing != null) {
      // Update ALL existing entries with this hash in the database
      val values =
          ContentValues().apply {
            put(COL_SUMMARY, summary)
            put(COL_TAGS, tags)
            put(COL_ANALYZED_AT, analyzedAt)
            put(COL_NOTE, note)
            put(COL_MODEL_USED, modelUsed)
          }
      writableDatabase.update(TABLE, values, "$COL_IMAGE_HASH = ?", arrayOf(hash))
    } else {
      // Insert new unlinked entry
      val entry =
          ScreenshotEntry(
              imageUri = "",
              imageHash = hash,
              summary = summary,
              tags = tags,
              analyzedAt = analyzedAt,
              note = note,
              modelUsed = modelUsed,
          )
      insertEntry(entry)
    }
  }

  fun applyLanResult(
      hash: String,
      summary: String,
      tags: String,
      analyzedAt: Long,
      note: String,
      modelUsed: String,
      allowedEntryIds: Set<Long>? = null,
      preserveNotes: Boolean = false,
  ) {
    val values =
        ContentValues().apply {
          put(COL_SUMMARY, summary)
          put(COL_TAGS, tags)
          put(COL_ANALYZED_AT, analyzedAt)
          put(COL_IS_ANALYZING, 0)
          if (!preserveNotes) put(COL_NOTE, note)
          put(COL_MODEL_USED, modelUsed)
        }
    if (allowedEntryIds == null) {
      writableDatabase.update(TABLE, values, "$COL_IMAGE_HASH = ?", arrayOf(hash))
    } else {
      for (id in allowedEntryIds) writableDatabase.update(
          TABLE, values, "$COL_IMAGE_HASH = ? AND $COL_ID = ?", arrayOf(hash, id.toString()))
    }
  }

  /** Link an image URI to an existing hash entry (for imported entries). */
  fun linkImageToHash(
      hash: String,
      imageUri: String,
  ) {
    val values = ContentValues().apply { put(COL_IMAGE_URI, imageUri) }
    writableDatabase.update(TABLE, values, "$COL_IMAGE_HASH = ?", arrayOf(hash))
  }

  private fun cursorToEntry(cursor: Cursor): ScreenshotEntry {
    val noteIdx = cursor.getColumnIndex(COL_NOTE)
    val modelUsedIdx = cursor.getColumnIndex(COL_MODEL_USED)
    return ScreenshotEntry(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
        imageUri = cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_URI)),
        imageHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_HASH)),
        summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUMMARY)),
        tags = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS)),
        analyzedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ANALYZED_AT)),
        isAnalyzing = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_ANALYZING)) == 1,
        note = if (noteIdx >= 0) cursor.getString(noteIdx) ?: "" else "",
        modelUsed = if (modelUsedIdx >= 0) cursor.getString(modelUsedIdx) ?: "" else "",
    )
  }
}
