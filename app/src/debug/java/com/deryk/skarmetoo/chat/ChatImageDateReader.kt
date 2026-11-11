package com.deryk.skarmetoo.chat

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.time.ZoneId

/** Reads metadata only; opening an EXIF header does not decode pixels or run a vision model. */
class ChatImageDateReader(private val context: Context) {
  fun read(uriText: String): ChatImageDates {
    val uri = Uri.parse(uriText)
    val zone = ZoneId.systemDefault()
    fun column(name: String, seconds: Boolean) =
        runCatching {
              context.contentResolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0))
                    ChatImageDateValues.epoch(cursor.getLong(0), seconds, zone)
                else null
              }
            }
            .getOrNull()
    val taken = column(MediaStore.Images.Media.DATE_TAKEN, false)
    val original =
        if (taken == null)
            runCatching {
                  context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    ChatImageDateValues.exif(
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                        zone)
                  }
                }
                .getOrNull()
        else null
    return ChatImageDates(
        taken = taken ?: original,
        takenSource =
            if (taken != null) "Capture date stored with the image"
            else "Original photo date stored in the file",
        added = column(MediaStore.Images.Media.DATE_ADDED, true),
        modified =
            column(MediaStore.Images.Media.DATE_MODIFIED, true)
                ?: column(DocumentsContract.Document.COLUMN_LAST_MODIFIED, false))
  }
}
