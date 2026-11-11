package com.deryk.skarmetoo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.deryk.skarmetoo.ai.EmbeddingGemma
import com.deryk.skarmetoo.ai.SearchModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScreenshotTextEmbedding(
    val imageUri: String,
    val entryId: Long,
    val contentHash: Int,
    val embedding: FloatArray,
    val visualEmbedding: FloatArray? = null,
)

class ScreenshotTextEmbeddingDatabase(
    context: Context,
    val model: SearchModel = SearchModel.ORIGINAL
) : SQLiteOpenHelper(context, model.databaseName, null, DB_VERSION) {

  private val appContext = context.applicationContext

  private var lastError: String? = null

  companion object {
    private const val TAG = "ScreenshotTextEmbeddingDB"
    private const val DB_NAME = "screenshot_text_embeddings.db"
    private const val DB_VERSION = 2
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
                $COL_VECTOR BLOB NOT NULL,
                visual_vector BLOB,
                visual_hash TEXT
            )
            """
            .trimIndent())
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS idx_text_embedding_entry ON $TABLE_EMBEDDINGS ($COL_ENTRY_ID)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE $TABLE_EMBEDDINGS ADD COLUMN visual_vector BLOB")
      db.execSQL("ALTER TABLE $TABLE_EMBEDDINGS ADD COLUMN visual_hash TEXT")
    }
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
      embedding: FloatArray,
      visualEmbedding: FloatArray? = null
  ): Boolean =
      withContext(Dispatchers.IO) {
        synchronized(ScreenshotLibrary.lock) {
          if (entry.imageUri.isBlank()) return@withContext false
          if (!ScreenshotLibrary.contains(appContext, entry.imageUri, entry.id))
              return@withContext false
          try {
            val values =
                ContentValues().apply {
                  put(COL_URI, entry.imageUri)
                  put(COL_ENTRY_ID, entry.id)
                  put(COL_CONTENT_HASH, contentHash)
                  put(COL_VECTOR, floatArrayToBytes(embedding))
                  if (visualEmbedding != null) {
                    require(model == SearchModel.GEMMA2)
                    require(visualEmbedding.size == 768 && visualEmbedding.all { it.isFinite() })
                    put("visual_vector", floatArrayToBytes(visualEmbedding))
                    put("visual_hash", entry.imageHash)
                  }
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

  suspend fun getStoredVisualHashes(): Map<String, String> =
      withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        readableDatabase
            .rawQuery(
                "SELECT image_uri, visual_hash FROM text_embeddings WHERE visual_vector IS NOT NULL",
                null)
            .use { cursor ->
              while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
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

  /** Only count fresh embeddings belonging to the same current library as the displayed total. */
  suspend fun getIndexedEntryCount(entries: List<ScreenshotEntry>): Int {
    val hashes = getStoredContentHashes()
    val visuals = if (model == SearchModel.GEMMA2) getStoredVisualHashes() else emptyMap()
    return entries
        .distinctBy { it.imageUri }
        .count { entry ->
          val text = EmbeddingGemma.buildSearchText(entry)
          entry.imageUri.isNotBlank() &&
              entry.imageHash.isNotBlank() &&
              (text.isNotBlank() || model == SearchModel.GEMMA2) &&
              hashes[entry.imageUri] == text.hashCode() &&
              (model != SearchModel.GEMMA2 || visuals[entry.imageUri] == entry.imageHash)
        }
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
                        visualEmbedding =
                            cursor.getColumnIndexOrThrow("visual_vector").let { column ->
                              if (cursor.isNull(column)) null
                              else bytesToFloatArray(cursor.getBlob(column))
                            },
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
            .map { record ->
              val contextScore =
                  if (record.embedding.isEmpty()) -1f
                  else cosineSimilarity(queryVector, record.embedding)
              val visualScore =
                  record.visualEmbedding?.let { cosineSimilarity(queryVector, it) } ?: -1f
              record to maxOf(contextScore, visualScore)
            }
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(limit)
      }

  private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    if (left.size != right.size) return 0f
    val size = left.size
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
