package com.deryk.skarmetoo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ScreenshotDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "screenshots.db"
        private const val DB_VERSION = 2
        private const val TABLE = "screenshots"

        private const val COL_ID = "id"
        private const val COL_IMAGE_URI = "image_uri"
        private const val COL_IMAGE_HASH = "image_hash"
        private const val COL_SUMMARY = "summary"
        private const val COL_TAGS = "tags"
        private const val COL_ANALYZED_AT = "analyzed_at"
        private const val COL_IS_ANALYZING = "is_analyzing"
        private const val COL_NOTE = "note"
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
                $COL_NOTE TEXT DEFAULT ''
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
    }

    fun insertEntry(entry: ScreenshotEntry): Long {
        val db = writableDatabase
        val values =
            ContentValues().apply {
                put(COL_IMAGE_URI, entry.imageUri)
                put(COL_IMAGE_HASH, entry.imageHash)
                put(COL_SUMMARY, entry.summary)
                put(COL_TAGS, entry.tags)
                put(COL_ANALYZED_AT, entry.analyzedAt)
                put(COL_IS_ANALYZING, if (entry.isAnalyzing) 1 else 0)
            }
        return db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateAnalysis(
        id: Long,
        summary: String,
        tags: String,
    ) {
        val db = writableDatabase
        val values =
            ContentValues().apply {
                put(COL_SUMMARY, summary)
                put(COL_TAGS, tags)
                put(COL_ANALYZED_AT, System.currentTimeMillis())
                put(COL_IS_ANALYZING, 0)
            }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun setAnalyzing(
        id: Long,
        analyzing: Boolean,
    ) {
        val db = writableDatabase
        val values =
            ContentValues().apply {
                put(COL_IS_ANALYZING, if (analyzing) 1 else 0)
            }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun resetAllAnalyzingFlags() {
        val db = writableDatabase
        val values =
            ContentValues().apply {
                put(COL_IS_ANALYZING, 0)
            }
        db.update(TABLE, values, null, null)
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
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun deleteAllEntries() {
        writableDatabase.delete(TABLE, null, null)
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
        return cursor.use {
            if (it.moveToFirst()) cursorToEntry(it) else null
        }
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
        return cursor.use {
            if (it.moveToFirst()) cursorToEntry(it) else null
        }
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
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getAnalyzedCount(): Int {
        val cursor =
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE $COL_ANALYZED_AT > 0",
                null,
            )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Import entry by hash. If hash exists, update metadata.
     * If hash doesn't exist, insert with empty URI (unlinked).
     */
    fun importEntry(
        hash: String,
        summary: String,
        tags: String,
        analyzedAt: Long,
    ) {
        val existing = getEntryByHash(hash)
        if (existing != null) {
            // Update existing entry with imported metadata
            val values =
                ContentValues().apply {
                    put(COL_SUMMARY, summary)
                    put(COL_TAGS, tags)
                    put(COL_ANALYZED_AT, analyzedAt)
                }
            writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(existing.id.toString()))
        } else {
            // Insert new unlinked entry
            val entry =
                ScreenshotEntry(
                    imageUri = "",
                    imageHash = hash,
                    summary = summary,
                    tags = tags,
                    analyzedAt = analyzedAt,
                )
            insertEntry(entry)
        }
    }

    /**
     * Link an image URI to an existing hash entry (for imported entries).
     */
    fun linkImageToHash(
        hash: String,
        imageUri: String,
    ) {
        val values =
            ContentValues().apply {
                put(COL_IMAGE_URI, imageUri)
            }
        writableDatabase.update(TABLE, values, "$COL_IMAGE_HASH = ?", arrayOf(hash))
    }

    private fun cursorToEntry(cursor: Cursor): ScreenshotEntry {
        val noteIdx = cursor.getColumnIndex(COL_NOTE)
        return ScreenshotEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            imageUri = cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_URI)),
            imageHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_IMAGE_HASH)),
            summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUMMARY)),
            tags = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS)),
            analyzedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ANALYZED_AT)),
            isAnalyzing = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_ANALYZING)) == 1,
            note = if (noteIdx >= 0) cursor.getString(noteIdx) ?: "" else "",
        )
    }
}
