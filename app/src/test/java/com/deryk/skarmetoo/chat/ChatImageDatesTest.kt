package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.data.ScreenshotEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatImageDatesTest {
  private val today = LocalDate.parse("2026-10-08")
  private val query = ChatQuery.parse("How much did I spend last month?", today)
  private val dates =
      ChatImageDates(taken = today, added = today.plusDays(1), modified = today.plusDays(2))
  private val order = ChatReceiptDateOrder.DAY_FIRST

  private fun resolve(
      json: String = """{"event_date":null}""",
      metadata: ChatImageDates = dates,
      visible: Boolean = false,
      q: ChatQuery = query
  ) = ChatEventDates.resolve(q, JSONObject(json), null, metadata, order, visible)

  @Test
  fun visibleEventDateWinsOverLaterCaptureAndImportDates() {
    val result = resolve("""{"event_date":"30-09-2026 12:41"}""")
    assertEquals(LocalDate.parse("2026-09-30"), result.date)
    assertEquals("Event date in image", result.label)
    assertTrue(query.includes(result.date))
    val printed = ChatPrintedEventDates.find("Completed Time 29-09-2026 12:41", order)!!
    assertEquals(
        LocalDate.parse("2026-09-29"),
        ChatEventDates.resolve(
                query, JSONObject("""{"event_date":"2026-10-08"}"""), printed, dates, order)
            .date)
  }

  @Test
  fun absentEventDateUsesCaptureWithAnExplicitOrigin() {
    val result = resolve()
    assertEquals(today, result.date)
    assertEquals("Image taken date", result.label)
    assertNull(result.error)
    assertFalse(query.includes(result.date))
  }

  @Test
  fun visibleButUnreadableOrAmbiguousEventsCannotBorrowTheCaptureDate() {
    assertNull(resolve("""{"event_date":"unreadable"}""").date)
    assertNull(resolve(visible = true).date)
    assertNull(
        ChatEventDates.resolve(
                query,
                JSONObject("""{"event_date":"01-10-2026"}"""),
                null,
                dates,
                ChatReceiptDateOrder.UNRESOLVED)
            .date)
    assertNull(resolve("""{"event_date":"2026-09-30","payment_date":"2026-09-29"}""").date)
  }

  @Test
  fun importAndFileModificationAreNeverCaptureFallbacks() {
    val result = resolve(metadata = ChatImageDates(added = today, modified = today))
    assertNull(result.date)
    assertNotNull(result.error)
  }

  @Test
  fun explicitTakenAndImportedQuestionsUseTheirRequestedBasis() {
    val json = """{"event_date":"2026-09-30"}"""
    assertEquals(
        today,
        resolve(json, q = ChatQuery.parse("Show payment screenshots taken today", today)).date)
    val added =
        resolve(json, q = ChatQuery.parse("Show payment screenshots imported tomorrow", today))
    assertEquals(today.plusDays(1), added.date)
    assertEquals("Image added date", added.label)
  }

  @Test
  fun millisecondsSecondsAndTimezoneBoundariesAreNotInterchangeable() {
    val instant = Instant.parse("2026-09-30T18:00:00Z")
    val zone = ZoneId.of("Asia/Singapore")
    assertEquals(
        LocalDate.parse("2026-10-01"),
        ChatImageDateValues.epoch(instant.toEpochMilli(), false, zone))
    assertEquals(
        LocalDate.parse("2026-10-01"), ChatImageDateValues.epoch(instant.epochSecond, true, zone))
    assertNull(ChatImageDateValues.epoch(0, false, zone))
    assertNull(ChatImageDateValues.epoch(Long.MAX_VALUE, true, zone))
    assertEquals(
        LocalDate.parse("2026-10-01"),
        ChatImageDateValues.exif("2026:09:30 23:30:00", "+00:00", zone))
    assertEquals(
        LocalDate.parse("2026-09-30"), ChatImageDateValues.exif("2026:09:30 23:30:00", null, zone))
    assertNull(ChatImageDateValues.exif("2026:02:30 12:00:00", null, zone))
  }

  @Test
  fun savedEventContextKeepsAnOlderReceiptCandidateWhenImportedLater() {
    val receipt =
        ScreenshotEntry(
            id = 1, imageUri = "image/1", imageHash = "1", summary = "Payment on 30 September 2026")
    val unknown =
        ScreenshotEntry(
            id = 2, imageUri = "image/2", imageHash = "2", summary = "A successful payment")
    val candidates =
        ChatCandidateSelector.select(
            query,
            listOf(receipt, unknown),
            emptyMap(),
            emptyMap(),
            mapOf(receipt.imageUri to dates, unknown.imageUri to ChatImageDates(added = today)))
    assertEquals(setOf(1L, 2L), candidates.map { it.entry.id }.toSet())
    val takenQuery = ChatQuery.parse("Show payment images taken last month", today)
    assertTrue(
        ChatCandidateSelector.select(
                takenQuery,
                listOf(receipt, unknown),
                emptyMap(),
                emptyMap(),
                mapOf(receipt.imageUri to dates, unknown.imageUri to ChatImageDates(added = today)))
            .isEmpty())
  }

  @Test
  fun finalAmountFilteringUsesTheSameEventFirstFallbackAndPeriod() {
    fun record(id: Long, event: String?) =
        ChatExtractedRecord(
            id,
            ChatVerification(ChatDecision.YES, "Successful RM5.72"),
            JSONObject()
                .put("value", 5.72)
                .put("unit", "RM")
                .put("event_date", event ?: JSONObject.NULL))
    val facts =
        ChatWorkflowFacts.amounts(
            query,
            listOf(record(1, "2026-09-30"), record(2, null), record(3, null)),
            emptyMap(),
            order,
            imageDates =
                mapOf(
                    1L to dates,
                    2L to ChatImageDates(taken = LocalDate.parse("2026-09-29")),
                    3L to ChatImageDates(added = LocalDate.parse("2026-09-29"))))
    assertEquals(listOf(1L, 2L), facts.amounts.map { it.record.sourceId })
    assertEquals(
        listOf("Event date in image", "Image taken date"),
        facts.amounts.map { it.resolvedDate?.label })
    assertEquals(1, facts.excluded)
  }
}
