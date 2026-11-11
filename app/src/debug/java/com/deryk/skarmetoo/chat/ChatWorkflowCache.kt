package com.deryk.skarmetoo.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotLibrary
import java.security.MessageDigest

object ChatWorkflowCacheKey {
  fun hash(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(
          "") {
            "%02x".format(it)
          }

  fun build(
      model: String,
      stage: ChatWorkflowStage,
      prompt: String,
      entry: ScreenshotEntry?,
      responseSchema: String? = null,
  ): String =
      hash(
          listOf(
                  "self-feed-v1",
                  ChatWorkflowPrompts.fingerprint,
                  model,
                  stage.name,
                  prompt,
                  responseSchema.orEmpty(),
                  entry?.imageUri.orEmpty(),
                  entry?.imageHash.orEmpty(),
                  entry?.summary.orEmpty(),
                  entry?.tags.orEmpty(),
                  entry?.note.orEmpty())
              .joinToString("\u0000"))
}

/** Exact prompt/model/image-scoped results; never reuses the legacy fixed receipt extraction. */
class ChatWorkflowCache(context: Context, databaseName: String = "chat_workflow.db") :
    SQLiteOpenHelper(context, databaseName, null, 2) {
  private val appContext = context.applicationContext

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE responses (cache_key TEXT PRIMARY KEY, response TEXT NOT NULL, used_at INTEGER NOT NULL, image_uri TEXT)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      // Old hashed keys cannot be traced back to their source image for removal.
      db.execSQL("DROP TABLE responses")
      onCreate(db)
    }
  }

  fun pruneToUris(uris: Set<String>): Int =
      ScreenshotLibrary.pruneUris(writableDatabase, "responses", "image_uri", uris)

  fun get(key: String): String? =
      readableDatabase
          .query("responses", arrayOf("response"), "cache_key=?", arrayOf(key), null, null, null)
          .use { if (it.moveToFirst()) it.getString(0) else null }

  fun put(key: String, response: String, entry: ScreenshotEntry? = null) =
      synchronized(ScreenshotLibrary.lock) {
        if (entry != null && !ScreenshotLibrary.contains(appContext, entry.imageUri, entry.id))
            return@synchronized
        writableDatabase.insertWithOnConflict(
            "responses",
            null,
            ContentValues().apply {
              put("cache_key", key)
              put("response", response)
              put("used_at", System.currentTimeMillis())
              put("image_uri", entry?.imageUri)
            },
            SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL(
            "DELETE FROM responses WHERE cache_key NOT IN (SELECT cache_key FROM responses ORDER BY used_at DESC LIMIT 3000)")
      }
}
