package com.deryk.skarmetoo.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the installed library's actual metadata; creates only one temporary EXIF fixture. */
@RunWith(AndroidJUnit4::class)
class ChatDatePeriodsDeviceTest {
  @Test
  fun calendarPeriodsAndActualLibraryMetadata(): Unit {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val today = LocalDate.parse("2026-10-08")
    val database = ScreenshotDatabase(context)
    val repository = ChatRepository(context)
    val report =
        JSONObject()
            .put("reference_today", today)
            .put("phone_today", LocalDate.now())
            .put("timezone", ZoneId.systemDefault().id)
    try {
      val entries = database.getAllEntries().filter { ChatCandidateSelector.hasSpendingSignal(it) }
      val metadata = entries.associate { it.imageUri to repository.imageDates(it.imageUri) }
      report.put(
          "payment_images",
          JSONArray(
              entries.map { entry ->
                val dates = metadata.getValue(entry.imageUri)
                JSONObject()
                    .put("source_id", entry.id)
                    .put("summary", entry.summary)
                    .put("note", entry.note)
                    .put("taken", dates.taken)
                    .put("taken_source", dates.takenSource)
                    .put("added", dates.added)
                    .put("modified", dates.modified)
                    .put(
                        "saved_context_dates",
                        JSONArray(
                            ChatDateWindows.explicitDates(
                                    ChatCandidateSelector.text(entry), repository.numericDateOrder)
                                .map { it.toString() }))
              }))
      val cases =
          listOf(
              Triple("last month", "2026-09-01", "2026-09-30"),
              Triple("on 1 October 2026", "2026-10-01", "2026-10-01"),
              Triple("in the first week of October 2026", "2026-10-01", "2026-10-07"),
              Triple("in the fifth week of September 2026", "2026-09-29", "2026-09-30"),
              Triple("from 30 September to 2 October 2026", "2026-09-30", "2026-10-02"),
              Triple("in September 2026", "2026-09-01", "2026-09-30"),
              Triple("in September 2025", "2025-09-01", "2025-09-30"))
      report.put(
          "periods",
          JSONArray(
              cases.map { (phrase, start, end) ->
                val query =
                    ChatQuery.parse(
                        "How much did I spend " + phrase + "?", today, repository.numericDateOrder)
                assertFalse(phrase, query.unsupportedDate)
                assertEquals(phrase, start + " to " + end, query.period)
                assertTrue(query.terms.isEmpty())
                val candidates =
                    ChatCandidateSelector.select(
                        query,
                        entries,
                        emptyMap(),
                        emptyMap(),
                        metadata,
                        repository.numericDateOrder)
                JSONObject()
                    .put("question", query.question)
                    .put("period", query.period)
                    .put("candidate_sources", JSONArray(candidates.map { it.entry.id }))
              }))
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("passed", false).put("failure", e.message)
      throw e
    } finally {
      File(context.cacheDir, "chat-date-periods-device.json").writeText(report.toString(2))
      repository.close()
      database.close()
    }
  }

  @Test
  fun originalExifCaptureIsReadWithoutUsingFileModificationAsCapture() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File(context.cacheDir, "chat-date-exif-fixture.jpg")
    val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    try {
      file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
      val exif = ExifInterface(file)
      exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:30 23:30:00")
      exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
      exif.saveAttributes()
      val dates = ChatImageDateReader(context).read(Uri.fromFile(file).toString())
      val expected =
          ChatImageDateValues.exif("2026:09:30 23:30:00", "+00:00", ZoneId.systemDefault())
      assertEquals(expected, dates.taken)
      assertNull(dates.added)
      val removeDate = ExifInterface(file)
      removeDate.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, null)
      removeDate.saveAttributes()
      assertNull(ChatImageDateReader(context).read(Uri.fromFile(file).toString()).taken)
    } finally {
      image.recycle()
      file.delete()
    }
  }
}
