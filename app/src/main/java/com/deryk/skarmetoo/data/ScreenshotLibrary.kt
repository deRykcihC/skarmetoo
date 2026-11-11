package com.deryk.skarmetoo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.deryk.skarmetoo.ai.EmbeddingGemma
import com.deryk.skarmetoo.ai.EmbeddingGemmaSkippedStore
import com.deryk.skarmetoo.ai.SearchModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes removals with index writes, including writes from other database helper instances. */
object ScreenshotLibrary {
  val lock = Any()
  private val removals = MutableStateFlow(0L)
  val removalRevision = removals.asStateFlow()

  fun markRemoved() {
    removals.value += 1
  }

  /** Preserve indexes when an image is still covered by another selected folder grant. */
  fun rebindUri(context: Context, oldUri: String, newUri: String) {
    val databases =
        listOf(
            Triple("screenshot_embeddings.db", "embeddings", "image_uri"),
            Triple("screenshot_text_embeddings.db", "text_embeddings", "image_uri"),
            Triple(SearchModel.GEMMA2.databaseName, "text_embeddings", "image_uri")) +
            DevelopmentIndexes.uriTables
    for ((name, table, column) in databases) {
      val file = context.getDatabasePath(name)
      if (!file.exists()) continue
      SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        // Older optional indexes may predate URI tracking; their variant migrates them later.
        val hasUriColumn =
            db.rawQuery("PRAGMA table_info($table)", null).use { columns ->
              val nameIndex = columns.getColumnIndexOrThrow("name")
              var found = false
              while (columns.moveToNext()) if (columns.getString(nameIndex) == column) found = true
              found
            }
        if (!hasUriColumn) continue
        val values = android.content.ContentValues().apply { put(column, newUri) }
        db.updateWithOnConflict(
            table, values, "$column = ?", arrayOf(oldUri), SQLiteDatabase.CONFLICT_REPLACE)
      }
    }
  }

  fun contains(context: Context, uri: String, entryId: Long? = null): Boolean =
      ScreenshotDatabase(context).use { db ->
        val entry = if (entryId == null) db.getEntryByImageUri(uri) else db.getEntryById(entryId)
        entry != null && entry.imageUri == uri && entry.imageHash.isNotBlank()
      }

  fun removeUnavailableImages(context: Context): Int =
      ScreenshotDatabase(context).use { db ->
        val missing =
            db.getAllEntries()
                .filter { entry ->
                  if (entry.imageUri.isBlank()) return@filter false
                  try {
                    context.contentResolver
                        .openAssetFileDescriptor(android.net.Uri.parse(entry.imageUri), "r")
                        ?.use { false } ?: true
                  } catch (_: SecurityException) {
                    true
                  } catch (_: java.io.FileNotFoundException) {
                    true
                  } catch (e: Exception) {
                    android.util.Log.w(
                        "ScreenshotLibrary", "Could not check image access: ${entry.imageUri}", e)
                    false
                  }
                }
                .map { it.imageUri }
        db.deleteEntriesByImageUris(missing)
      }

  /** Called with [lock] held after deletion and at startup to repair older orphaned indexes. */
  fun prune(context: Context, entries: List<ScreenshotEntry>, imageRemoved: Boolean = false) {
    val valid = entries.filter { it.imageUri.isNotBlank() && it.imageHash.isNotBlank() }
    val uris = valid.mapTo(hashSetOf()) { it.imageUri }
    var removed = 0
    if (context.getDatabasePath("screenshot_embeddings.db").exists()) {
      ScreenshotVectorDatabase(context).use {
        removed += pruneUris(it.writableDatabase, "embeddings", "image_uri", uris)
      }
    }
    for (model in SearchModel.entries) {
      if (!context.getDatabasePath(model.databaseName).exists()) continue
      ScreenshotTextEmbeddingDatabase(context, model).use {
        val searchable =
            valid.filter {
              model == SearchModel.GEMMA2 || EmbeddingGemma.buildSearchText(it).isNotBlank()
            }
        removed +=
            pruneUris(
                it.writableDatabase,
                "text_embeddings",
                "image_uri",
                searchable.mapTo(hashSetOf()) { entry -> entry.imageUri })
      }
    }
    removed += DevelopmentIndexes.prune(context, valid, imageRemoved)
    val ids = valid.mapTo(hashSetOf()) { it.id }
    for (model in SearchModel.entries) {
      EmbeddingGemmaSkippedStore.saveEntryIds(
          context,
          EmbeddingGemmaSkippedStore.getEntryIds(context, model).filterTo(linkedSetOf()) {
            it in ids
          },
          model)
    }
    if (removed > 0) markRemoved()
  }

  // A temporary table avoids SQLite's parameter limit for large libraries and handles an empty one.
  fun pruneUris(db: SQLiteDatabase, table: String, uriColumn: String, validUris: Set<String>): Int {
    db.beginTransaction()
    try {
      db.execSQL("CREATE TEMP TABLE IF NOT EXISTS retained_image_uris (uri TEXT PRIMARY KEY)")
      db.delete("retained_image_uris", null, null)
      db.compileStatement("INSERT OR IGNORE INTO retained_image_uris (uri) VALUES (?)").use { insert
        ->
        for (uri in validUris) {
          insert.bindString(1, uri)
          insert.executeInsert()
        }
      }
      val removed =
          db.delete(
              table,
              "$uriColumn IS NOT NULL AND $uriColumn NOT IN (SELECT uri FROM retained_image_uris)",
              null)
      db.execSQL("DROP TABLE retained_image_uris")
      db.setTransactionSuccessful()
      return removed
    } finally {
      db.endTransaction()
    }
  }
}
