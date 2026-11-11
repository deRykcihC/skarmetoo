package com.deryk.skarmetoo.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.deryk.skarmetoo.data.ScreenshotEntry

class ChatReceiptCache(context: Context) : SQLiteOpenHelper(context, "chat_receipts.db", null, 1) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE receipts (uri TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, response TEXT NOT NULL)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  private fun fingerprint(entry: ScreenshotEntry, model: String) =
      "v2|$model|${entry.imageHash}|${entry.summary.hashCode()}|${entry.note.hashCode()}"

  fun get(entry: ScreenshotEntry, model: String): String? =
      readableDatabase
          .query(
              "receipts",
              arrayOf("response"),
              "uri=? AND fingerprint=?",
              arrayOf(entry.imageUri, fingerprint(entry, model)),
              null,
              null,
              null)
          .use { if (it.moveToFirst()) it.getString(0) else null }

  fun put(entry: ScreenshotEntry, model: String, response: String) {
    writableDatabase.insertWithOnConflict(
        "receipts",
        null,
        ContentValues().apply {
          put("uri", entry.imageUri)
          put("fingerprint", fingerprint(entry, model))
          put("response", response)
        },
        SQLiteDatabase.CONFLICT_REPLACE)
  }
}
