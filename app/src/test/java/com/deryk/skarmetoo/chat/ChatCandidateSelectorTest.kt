package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.data.ScreenshotEntry
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChatCandidateSelectorTest {
  private val query =
      ChatQuery.parse("how much did I spent last week", LocalDate.parse("2026-10-04"))
  private val lastWeek = LocalDate.parse("2026-10-01")

  private fun image(id: Long, summary: String) =
      ScreenshotEntry(id = id, imageUri = "image/$id", imageHash = "$id", summary = summary)

  @Test
  fun eightHundredPhotosDoNotBecomeEightHundredVisionRequests() {
    val photos = (1L..800L).map { image(it, "A cat sitting by a window") }
    val receipts = (801L..805L).map { image(it, "Receipt for a completed payment") }
    val entries = photos + receipts
    val dates = entries.associate { it.imageUri to lastWeek }
    val candidates = ChatCandidateSelector.select(query, entries, dates, emptyMap())
    assertEquals(receipts.map { it.id }.toSet(), candidates.map { it.entry.id }.toSet())
  }

  @Test
  fun outOfPeriodReceiptsAreExcludedUnlessStoredTransactionDateMatches() {
    val old = image(1, "Receipt for groceries")
    val recentCapture = image(2, "Payment receipt dated 2026-10-01")
    val dates =
        mapOf(
            old.imageUri to LocalDate.parse("2026-08-01"),
            recentCapture.imageUri to LocalDate.parse("2026-10-08"))
    assertEquals(
        listOf(2L),
        ChatCandidateSelector.select(query, listOf(old, recentCapture), dates, emptyMap()).map {
          it.entry.id
        })
  }

  @Test
  fun theSeptember30PaymentIsNotDroppedAtTheWeekBoundary() {
    val week = ChatQuery.parse("How much did I spend last week?", LocalDate.parse("2026-10-07"))
    val boundary = image(6, "Successful payment completed on 30-09-2026")
    val older = image(7, "Successful payment")
    val dates =
        mapOf(
            boundary.imageUri to LocalDate.parse("2026-09-30"),
            older.imageUri to LocalDate.parse("2026-09-29"))
    assertEquals(
        listOf(6L),
        ChatCandidateSelector.select(week, listOf(boundary, older), dates, emptyMap()).map {
          it.entry.id
        })
  }

  @Test
  fun missingDatesAndGenericWordsDoNotAdmitUnrelatedImages() {
    val unknown = image(1, "A total of five trees")
    val undatedReceipt = image(2, "A receipt")
    val weakMatch = image(3, "A photograph of lunch")
    val strongMatch = image(4, "A completed transfer confirmation")
    val scores = mapOf(weakMatch.imageUri to 0.5f, strongMatch.imageUri to 0.7f)
    assertEquals(
        setOf(2L, 4L),
        ChatCandidateSelector.select(
                query, listOf(unknown, undatedReceipt, weakMatch, strongMatch), emptyMap(), scores)
            .map { it.entry.id }
            .toSet())
  }
}
