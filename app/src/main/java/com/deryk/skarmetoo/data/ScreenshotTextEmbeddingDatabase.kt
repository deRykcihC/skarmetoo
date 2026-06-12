package com.deryk.skarmetoo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScreenshotTextEmbedding(
    val imageUri: String,
    val entryId: Long,
    val contentHash: Int,
    val embedding: FloatArray,
)

class ScreenshotTextEmbeddingDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

  private var lastError: String? = null

  companion object {
    private const val TAG = "ScreenshotTextEmbeddingDB"
    private const val DB_NAME = "screenshot_text_embeddings.db"
    private const val DB_VERSION = 1
    private const val TABLE_EMBEDDINGS = "text_embeddings"

    private const val COL_URI = "image_uri"
    private const val COL_ENTRY_ID = "entry_id"
    private const val COL_CONTENT_HASH = "content_hash"
    private const val COL_VECTOR = "vector_blob"
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        """
            CREATE TABLE $TABLE_EMBEDDINGS (
                $COL_URI TEXT PRIMARY KEY,
                $COL_ENTRY_ID INTEGER NOT NULL,
                $COL_CONTENT_HASH INTEGER NOT NULL,
                $COL_VECTOR BLOB NOT NULL
            )
            """
            .trimIndent())
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS idx_text_embedding_entry ON $TABLE_EMBEDDINGS ($COL_ENTRY_ID)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("DROP TABLE IF EXISTS $TABLE_EMBEDDINGS")
    onCreate(db)
  }

  private fun floatArrayToBytes(floats: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(floats.size * 4).apply { order(ByteOrder.nativeOrder()) }
    for (value in floats) buffer.putFloat(value)
    return buffer.array()
  }

  private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).apply { order(ByteOrder.nativeOrder()) }
    val floats = FloatArray(bytes.size / 4)
    for (i in floats.indices) floats[i] = buffer.float
    return floats
  }

  suspend fun saveEmbedding(
      entry: ScreenshotEntry,
      contentHash: Int,
      embedding: FloatArray
  ): Boolean =
      withContext(Dispatchers.IO) {
        if (entry.imageUri.isBlank()) return@withContext false
        try {
          val values =
              ContentValues().apply {
                put(COL_URI, entry.imageUri)
                put(COL_ENTRY_ID, entry.id)
                put(COL_CONTENT_HASH, contentHash)
                put(COL_VECTOR, floatArrayToBytes(embedding))
              }
          val rowId =
              writableDatabase.insertWithOnConflict(
                  TABLE_EMBEDDINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
          if (rowId == -1L) {
            lastError = "SQLite insert returned -1 for ${entry.imageUri}"
            Log.e(TAG, lastError.orEmpty())
            false
          } else {
            lastError = null
            true
          }
        } catch (e: Exception) {
          lastError = e.localizedMessage ?: e.javaClass.simpleName
          Log.e(TAG, "Failed to save text embedding for ${entry.imageUri}", e)
          false
        }
      }

  fun getLastError(): String? = lastError

  suspend fun getStoredContentHashes(): Map<String, Int> =
      withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Int>()
        try {
          readableDatabase
              .query(
                  TABLE_EMBEDDINGS,
                  arrayOf(COL_URI, COL_CONTENT_HASH),
                  null,
                  null,
                  null,
                  null,
                  null)
              .use { cursor ->
                while (cursor.moveToNext()) {
                  result[cursor.getString(0)] = cursor.getInt(1)
                }
              }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load text embedding hashes", e)
        }
        result
      }

  suspend fun getStoredEmbeddingCount(): Int =
      withContext(Dispatchers.IO) {
        try {
          readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_EMBEDDINGS", null).use { cursor ->
            if (cursor.moveToFirst()) return@withContext cursor.getInt(0)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to count text embeddings", e)
        }
        0
      }

  suspend fun clearAll() =
      withContext(Dispatchers.IO) {
        try {
          writableDatabase.delete(TABLE_EMBEDDINGS, null, null)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to clear text embeddings", e)
        }
      }

  suspend fun findSimilar(
      queryVector: FloatArray,
      similarityThreshold: Float = 0.45f,
      limit: Int = 120,
  ): List<Pair<ScreenshotTextEmbedding, Float>> =
      withContext(Dispatchers.Default) {
        val records = mutableListOf<ScreenshotTextEmbedding>()
        withContext(Dispatchers.IO) {
          try {
            readableDatabase.query(TABLE_EMBEDDINGS, null, null, null, null, null, null).use {
                cursor ->
              while (cursor.moveToNext()) {
                records.add(
                    ScreenshotTextEmbedding(
                        imageUri = cursor.getString(cursor.getColumnIndexOrThrow(COL_URI)),
                        entryId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ENTRY_ID)),
                        contentHash = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CONTENT_HASH)),
                        embedding =
                            bytesToFloatArray(
                                cursor.getBlob(cursor.getColumnIndexOrThrow(COL_VECTOR))),
                    ))
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch text embeddings", e)
          }
        }

        records
            .map { record -> record to cosineSimilarity(queryVector, record.embedding) }
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(limit)
      }

  private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    val size = minOf(left.size, right.size)
    if (size == 0) return 0f

    var dot = 0f
    var leftNorm = 0f
    var rightNorm = 0f
    for (i in 0 until size) {
      dot += left[i] * right[i]
      leftNorm += left[i] * left[i]
      rightNorm += right[i] * right[i]
    }
    if (leftNorm == 0f || rightNorm == 0f) return 0f
    return dot / kotlin.math.sqrt(leftNorm * rightNorm)
  }
}
