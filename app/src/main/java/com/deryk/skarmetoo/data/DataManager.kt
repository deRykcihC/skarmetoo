package com.deryk.skarmetoo.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Handles import/export of screenshot metadata as JSON.
 * Only exports hash + summary + tags (no image data or device-specific URIs).
 */
object DataManager {

    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_HASH = "imageHash"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_TAGS = "tags"
    private const val KEY_ANALYZED_AT = "analyzedAt"
    private const val CURRENT_VERSION = 1

    /**
     * Export all analyzed entries to JSON string.
     */
    fun exportToJson(db: ScreenshotDatabase): String {
        val entries = db.getAllEntries().filter { it.analyzedAt > 0 }
        val jsonArray = JSONArray()

        for (entry in entries) {
            val obj = JSONObject().apply {
                put(KEY_HASH, entry.imageHash)
                put(KEY_SUMMARY, entry.summary)
                put(KEY_TAGS, entry.tags)
                put(KEY_ANALYZED_AT, entry.analyzedAt)
            }
            jsonArray.put(obj)
        }

        val root = JSONObject().apply {
            put(KEY_VERSION, CURRENT_VERSION)
            put(KEY_ENTRIES, jsonArray)
        }

        return root.toString(2)
    }

    /**
     * Export to a file via content URI (from SAF createDocument).
     */
    fun exportToUri(context: Context, uri: Uri, db: ScreenshotDatabase): Boolean {
        return try {
            val json = exportToJson(db)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import from a JSON file URI. Returns the number of entries imported.
     */
    fun importFromUri(context: Context, uri: Uri, db: ScreenshotDatabase): Int {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return 0
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val jsonString = reader.readText()
            reader.close()

            val root = JSONObject(jsonString)
            val version = root.optInt(KEY_VERSION, 1)
            val entries = root.getJSONArray(KEY_ENTRIES)
            var count = 0

            for (i in 0 until entries.length()) {
                val obj = entries.getJSONObject(i)
                val hash = obj.getString(KEY_HASH)
                val summary = obj.optString(KEY_SUMMARY, "")
                val tags = obj.optString(KEY_TAGS, "")
                val analyzedAt = obj.optLong(KEY_ANALYZED_AT, System.currentTimeMillis())

                db.importEntry(hash, summary, tags, analyzedAt)
                count++
            }

            count
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
}
