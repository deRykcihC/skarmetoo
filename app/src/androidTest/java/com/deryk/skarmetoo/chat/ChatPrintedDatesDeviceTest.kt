package com.deryk.skarmetoo.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads only explicitly supplied saved-image fixtures, without GGUF or changes to user data. */
@RunWith(AndroidJUnit4::class)
class ChatPrintedDatesDeviceTest {
  @Test
  fun labeledDatesStayReadableInTheActualSavedPayments(): Unit = runBlocking {
    val args = InstrumentationRegistry.getArguments()
    val expected =
        args.getString("expectedImageDates")?.split(',')?.associate {
          val pair = it.split('=')
          pair[0].toLong() to LocalDate.parse(pair[1])
        }
    assumeTrue("Supply source IDs and independently checked visible dates", expected != null)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val expectedPaid =
        args
            .getString("expectedPaidAmounts")
            ?.split(',')
            ?.associate {
              val pair = it.split('=')
              pair[0].toLong() to pair[1].toBigDecimal()
            }
            .orEmpty()
    val database = ScreenshotDatabase(context)
    val repository = ChatRepository(context)
    val rows = JSONArray()
    val report = JSONObject().put("images", rows)
    try {
      val entries = database.getAllEntries().associateBy { it.id }
      for ((id, expectedDate) in requireNotNull(expected)) {
        val image = repository.loadImage(requireNotNull(entries[id]))
        try {
          val text = repository.imageText(requireNotNull(entries[id]), image)
          val date = ChatPrintedEventDates.find(text, ChatReceiptDateOrder.DAY_FIRST)
          val paid = ChatPrintedPaidAmounts.find(text)
          rows.put(
              JSONObject()
                  .put("source_id", id)
                  .put("text", text)
                  .put("event_date", date?.date)
                  .put("evidence", date?.evidence)
                  .put("paid_amount", paid?.amount?.toPlainString())
                  .put("paid_amount_evidence", paid?.evidence))
          assertEquals(
              "The printed event date must be preserved for source $id", expectedDate, date?.date)
          expectedPaid[id]?.let { amount ->
            assertEquals(
                "The aligned paid label must stay readable for source $id", amount, paid?.amount)
          }
        } finally {
          image.recycle()
        }
      }
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("passed", false).put("failure", e.message)
      throw e
    } finally {
      File(context.cacheDir, "chat-printed-dates-device.json").writeText(report.toString(2))
      repository.close()
      database.close()
    }
  }
}
