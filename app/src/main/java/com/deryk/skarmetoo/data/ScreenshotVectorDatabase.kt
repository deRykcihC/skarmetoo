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

data class ScreenshotEmbedding(
    val imageHash: String,
    val imageUri: String,
    val embedding: FloatArray
)

class ScreenshotVectorDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

  companion object {
    private const val TAG = "ScreenshotVectorDB"
    private const val DB_NAME = "screenshot_embeddings.db"
    private const val DB_VERSION = 2
    private const val TABLE_EMBEDDINGS = "embeddings"
    private const val TABLE_EMBEDDINGS_MIGRATION = "embeddings_v2"

    private const val COL_HASH = "image_hash"
    private const val COL_URI = "image_uri"
    private const val COL_VECTOR = "vector_blob"
  }

  override fun onCreate(db: SQLiteDatabase) {
    createEmbeddingsTable(db, TABLE_EMBEDDINGS)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      // v1 schema keyed embeddings by hash. v2 keys by URI to support hash collisions.
      createEmbeddingsTable(db, TABLE_EMBEDDINGS_MIGRATION)
      db.execSQL(
          """
                INSERT OR REPLACE INTO $TABLE_EMBEDDINGS_MIGRATION ($COL_URI, $COL_HASH, $COL_VECTOR)
                SELECT $COL_URI, $COL_HASH, $COL_VECTOR
                FROM $TABLE_EMBEDDINGS
                WHERE $COL_URI IS NOT NULL AND $COL_URI != ''
                """
              .trimIndent())
      db.execSQL("DROP TABLE IF EXISTS $TABLE_EMBEDDINGS")
      db.execSQL("ALTER TABLE $TABLE_EMBEDDINGS_MIGRATION RENAME TO $TABLE_EMBEDDINGS")
      db.execSQL("DROP INDEX IF EXISTS idx_vector_hash")
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_vector_hash ON $TABLE_EMBEDDINGS ($COL_HASH)")
      return
    }

    db.execSQL("DROP TABLE IF EXISTS $TABLE_EMBEDDINGS")
    onCreate(db)
  }

  private fun createEmbeddingsTable(db: SQLiteDatabase, tableName: String) {
    db.execSQL(
        """
            CREATE TABLE $tableName (
                $COL_URI TEXT PRIMARY KEY,
                $COL_HASH TEXT NOT NULL,
                $COL_VECTOR BLOB NOT NULL
            )
            """
            .trimIndent())
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_vector_hash ON $tableName ($COL_HASH)")
  }

  /** Converts a FloatArray into a compact binary ByteArray for BLOB storage. */
  private fun floatArrayToBytes(floats: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(floats.size * 4).apply { order(ByteOrder.nativeOrder()) }
    for (f in floats) buffer.putFloat(f)
    return buffer.array()
  }

  /** Reconstructs a FloatArray from a SQLite BLOB ByteArray. */
  private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).apply { order(ByteOrder.nativeOrder()) }
    val floatCount = bytes.size / 4
    val floats = FloatArray(floatCount)
    for (i in 0 until floatCount) {
      floats[i] = buffer.float
    }
    return floats
  }

  /** Persists an image path and its visual embedding. */
  suspend fun saveEmbedding(hash: String, uri: String, embedding: FloatArray) =
      withContext(Dispatchers.IO) {
        try {
          if (uri.isBlank()) return@withContext
          val db = writableDatabase
          val values =
              ContentValues().apply {
                put(COL_URI, uri)
                put(COL_HASH, hash)
                put(COL_VECTOR, floatArrayToBytes(embedding))
              }
          db.insertWithOnConflict(TABLE_EMBEDDINGS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
          Log.d(TAG, "Successfully saved embedding vector for uri: $uri")
        } catch (e: Exception) {
          Log.e(TAG, "Failed to save embedding vector for uri: $uri", e)
        }
      }

  /** Returns true if an embedding exists for the given image URI. */
  suspend fun hasEmbedding(uri: String): Boolean =
      withContext(Dispatchers.IO) {
        try {
          val db = readableDatabase
          val cursor =
              db.rawQuery(
                  "SELECT 1 FROM $TABLE_EMBEDDINGS WHERE $COL_URI = ? LIMIT 1", arrayOf(uri))
          cursor.use {
            return@withContext it.moveToFirst()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error checking embedding presence", e)
          return@withContext false
        }
      }

  /** Retrieves an embedding vector by image URI. */
  suspend fun getEmbeddingByUri(uri: String): ScreenshotEmbedding? =
      withContext(Dispatchers.IO) {
        try {
          val db = readableDatabase
          val cursor =
              db.query(TABLE_EMBEDDINGS, null, "$COL_URI = ?", arrayOf(uri), null, null, null)
          cursor.use {
            if (it.moveToFirst()) {
              val hash = it.getString(it.getColumnIndexOrThrow(COL_HASH))
              val blob = it.getBlob(it.getColumnIndexOrThrow(COL_VECTOR))
              val embedding = bytesToFloatArray(blob)
              return@withContext ScreenshotEmbedding(hash, uri, embedding)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error getting embedding by uri: $uri", e)
        }
        return@withContext null
      }

  /** Deletes an embedding vector by image hash. */
  suspend fun deleteEmbeddingByHash(hash: String) =
      withContext(Dispatchers.IO) {
        try {
          val db = writableDatabase
          db.delete(TABLE_EMBEDDINGS, "$COL_HASH = ?", arrayOf(hash))
        } catch (e: Exception) {
          Log.e(TAG, "Error deleting embedding by hash: $hash", e)
        }
      }

  /** Clears all embedding records from the database. */
  suspend fun clearAll() =
      withContext(Dispatchers.IO) {
        try {
          val db = writableDatabase
          db.delete(TABLE_EMBEDDINGS, null, null)
          Log.i(TAG, "All visual embeddings successfully purged.")
        } catch (e: Exception) {
          Log.e(TAG, "Error purging embeddings database", e)
        }
      }

  /** Returns a map of all stored image hashes. */
  suspend fun getAllStoredHashes(): Set<String> =
      withContext(Dispatchers.IO) {
        val hashes = mutableSetOf<String>()
        try {
          val db = readableDatabase
          val cursor = db.query(TABLE_EMBEDDINGS, arrayOf(COL_HASH), null, null, null, null, null)
          cursor.use {
            while (it.moveToNext()) {
              hashes.add(it.getString(0))
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error fetching stored hashes", e)
        }
        return@withContext hashes
      }

  /** Returns the set of all image URIs that already have stored embeddings. */
  suspend fun getAllStoredUris(): Set<String> =
      withContext(Dispatchers.IO) {
        val uris = mutableSetOf<String>()
        try {
          val db = readableDatabase
          val cursor = db.query(TABLE_EMBEDDINGS, arrayOf(COL_URI), null, null, null, null, null)
          cursor.use {
            while (it.moveToNext()) {
              uris.add(it.getString(0))
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error fetching stored URIs", e)
        }
        return@withContext uris
      }

  /** Returns the current number of stored embedding rows. */
  suspend fun getStoredEmbeddingCount(): Int =
      withContext(Dispatchers.IO) {
        try {
          val db = readableDatabase
          val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EMBEDDINGS", null)
          cursor.use { if (it.moveToFirst()) return@withContext it.getInt(0) }
        } catch (e: Exception) {
          Log.e(TAG, "Error getting embedding row count", e)
        }
        return@withContext 0
      }

  /**
   * Executes an on-device local vector scan. Computes the dot product (Cosine Similarity) against
   * every entry in the database, returning the top matches sorted from most conceptually similar to
   * least.
   */
  suspend fun findSimilarScreenshots(
      targetQueryVector: FloatArray,
      similarityThreshold: Float = 0.50f,
      limit: Int = 30
  ): List<Pair<ScreenshotEmbedding, Float>> =
      withContext(Dispatchers.Default) {
        val allEmbeddings = mutableListOf<ScreenshotEmbedding>()

        // 1. Fetch all vectors from the local SQLite database
        withContext(Dispatchers.IO) {
          try {
            val db = readableDatabase
            val cursor = db.query(TABLE_EMBEDDINGS, null, null, null, null, null, null)
            cursor.use {
              while (it.moveToNext()) {
                val hash = it.getString(it.getColumnIndexOrThrow(COL_HASH))
                val uri = it.getString(it.getColumnIndexOrThrow(COL_URI))
                val blob = it.getBlob(it.getColumnIndexOrThrow(COL_VECTOR))
                val embedding = bytesToFloatArray(blob)
                allEmbeddings.add(ScreenshotEmbedding(hash, uri, embedding))
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error fetching vectors for similarity sweep", e)
          }
        }

        // 2. Perform Cosine Similarity (Dot product because vectors are normalized)
        allEmbeddings
            .map { entry ->
              var dotProduct = 0.0f
              val len = targetQueryVector.size.coerceAtMost(entry.embedding.size)
              val entryVector = entry.embedding
              for (i in 0 until len) {
                dotProduct += targetQueryVector[i] * entryVector[i]
              }
              Pair(entry, dotProduct)
            }
            // 3. Filter matches above the similarity threshold and sort descending
            .filter { it.second >= similarityThreshold }
            .sortedByDescending { it.second }
            .take(limit)
      }
}
