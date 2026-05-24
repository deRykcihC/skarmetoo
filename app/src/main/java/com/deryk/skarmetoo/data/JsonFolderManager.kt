package com.deryk.skarmetoo.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the synchronization and read/write operations for the single JSON backup file
 * (skarmetoo_backup_yyyy-MM-dd_HHmmss.json) stored in an external system directory selected by the
 * user.
 */
object JsonFolderManager {
  private const val TAG = "JsonFolderManager"
  private const val BACKUP_PREFIX = "skarmetoo_backup_"
  private const val BACKUP_SUFFIX = ".json"
  private const val KEY_VERSION = "version"
  private const val KEY_ENTRIES = "entries"
  private const val KEY_HASH = "imageHash"
  private const val KEY_SUMMARY = "summary"
  private const val KEY_TAGS = "tags"
  private const val KEY_NOTE = "note"
  private const val KEY_ANALYZED_AT = "analyzedAt"
  private const val KEY_MODEL_USED = "modelUsed"
  private const val CURRENT_VERSION = 1

  private fun getBackupFilename(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    val timestamp = sdf.format(Date())
    return "$BACKUP_PREFIX$timestamp$BACKUP_SUFFIX"
  }

  private fun findBackupFile(rootDir: DocumentFile): DocumentFile? {
    return try {
      val files = rootDir.listFiles()
      files.find {
        it.isFile &&
            it.name?.startsWith(BACKUP_PREFIX) == true &&
            it.name?.endsWith(BACKUP_SUFFIX) == true
      }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Writes all analyzed entries from the SQLite database to a single JSON backup file in the
   * selected external folder directory.
   */
  fun saveDatabaseToFolder(context: Context, treeUri: Uri, db: ScreenshotDatabase): String? {
    return try {
      val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
      if (!rootDir.exists() || !rootDir.isDirectory) {
        Log.e(TAG, "Root directory does not exist or is not a directory")
        return null
      }

      // Delete all old backup files to avoid duplicate files in the folder
      try {
        val files = rootDir.listFiles()
        for (file in files) {
          if (file.isFile &&
              file.name?.startsWith(BACKUP_PREFIX) == true &&
              file.name?.endsWith(BACKUP_SUFFIX) == true) {
            file.delete()
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to clean up old backup files", e)
      }

      val newFilename = getBackupFilename()
      val backupFile = rootDir.createFile("application/json", newFilename)
      if (backupFile == null) {
        Log.e(TAG, "Failed to create backup file: $newFilename")
        return null
      }

      val entries = db.getAllEntries().filter { it.analyzedAt > 0 }.distinctBy { it.imageHash }
      val jsonArray = JSONArray()

      for (entry in entries) {
        val obj =
            JSONObject().apply {
              put(KEY_HASH, entry.imageHash)
              put(KEY_SUMMARY, entry.summary)
              put(KEY_TAGS, entry.tags)
              put(KEY_NOTE, entry.note)
              put(KEY_ANALYZED_AT, entry.analyzedAt)
              put(KEY_MODEL_USED, entry.modelUsed)
            }
        jsonArray.put(obj)
      }

      val rootObj =
          JSONObject().apply {
            put(KEY_VERSION, CURRENT_VERSION)
            put(KEY_ENTRIES, jsonArray)
          }

      context.contentResolver.openOutputStream(backupFile.uri)?.use { outputStream ->
        outputStream.write(rootObj.toString(2).toByteArray(Charsets.UTF_8))
      }
      Log.d(TAG, "Successfully exported ${entries.size} entries to $newFilename")
      newFilename
    } catch (e: Exception) {
      Log.e(TAG, "Error writing database to external folder", e)
      null
    }
  }

  /**
   * Imports all entries from the skarmetoo_backup_*.json file inside the selected folder into the
   * SQLite database. Returns the number of imported/updated entries.
   */
  fun importEntriesFromFolder(context: Context, treeUri: Uri, db: ScreenshotDatabase): Int {
    return try {
      val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
      if (!rootDir.exists() || !rootDir.isDirectory) return 0

      val backupFile = findBackupFile(rootDir) ?: return 0
      val inputStream = context.contentResolver.openInputStream(backupFile.uri) ?: return 0
      val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
      val jsonString = reader.readText()
      reader.close()

      val root = JSONObject(jsonString)
      val entries = root.getJSONArray(KEY_ENTRIES)
      var count = 0

      for (i in 0 until entries.length()) {
        val obj = entries.getJSONObject(i)
        val hash = obj.getString(KEY_HASH)
        val summary = obj.optString(KEY_SUMMARY, "")
        val tags = obj.optString(KEY_TAGS, "")
        val note = obj.optString(KEY_NOTE, "")
        val analyzedAt = obj.optLong(KEY_ANALYZED_AT, 0L)
        val modelUsed = obj.optString(KEY_MODEL_USED, "")

        if (hash.isNotEmpty() && analyzedAt > 0L) {
          db.importEntry(hash, summary, tags, analyzedAt, note, modelUsed)
          count++
        }
      }
      Log.d(TAG, "Successfully imported $count entries from ${backupFile.name}")
      count
    } catch (e: Exception) {
      Log.e(TAG, "Error importing entries from external folder", e)
      0
    }
  }

  /**
   * Performs a bidirectional sync between the database and the external backup file.
   * 1. Reads the backup file from the external folder and imports any new/updated entries.
   * 2. Writes all analyzed database entries (including the newly imported ones) back to the backup
   *    file. Returns a Pair of (importedCount, exportedCount).
   */
  fun syncDatabaseWithFolder(
      context: Context,
      treeUri: Uri,
      db: ScreenshotDatabase
  ): Pair<Int, Int> {
    val imported = importEntriesFromFolder(context, treeUri, db)
    val entries = db.getAllEntries().filter { it.analyzedAt > 0 }
    val filename = saveDatabaseToFolder(context, treeUri, db)
    val exported = if (filename != null) entries.size else 0
    return Pair(imported, exported)
  }
}
