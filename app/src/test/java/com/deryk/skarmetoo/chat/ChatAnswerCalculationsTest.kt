package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatAnswerCalculationsTest {
  private fun part(
      id: Long,
      date: String,
      value: String,
      currency: String = "MYR"
  ): ChatPartAnswer {
    val record =
        ChatExtractedRecord(
            id, ChatVerification(ChatDecision.YES, "Completed payment"), JSONObject())
    val amount = ChatAmount(record, BigDecimal(value), currency, LocalDate.parse(date))
    val query = ChatQuery.parse("Spending on $date?", LocalDate.of(2026, 10, 8))
    return ChatPartAnswer(
        query.question, query, ChatAmountResult(listOf(amount), 0, 0), listOf(record), 1)
  }

  @Test
  fun separateDaysHaveSubtotalsAndOneCheckedCombinedTotal() {
    val parts = listOf(part(7, "2026-09-29", "5.36"), part(8, "2026-09-26", "5.58"))
    val result = requireNotNull(ChatAnswerCalculations.combinedAmounts(parts))
    assertEquals(BigDecimal("10.94"), result.totals["MYR"])
    assertTrue(parts[0].summary.contains("MYR 5.36"))
    assertTrue(parts[1].summary.contains("MYR 5.58"))
    assertTrue(ChatAnswerCalculations.combinedSummary(parts, result).contains("MYR 10.94"))
  }

  @Test
  fun overlappingPeriodsAndIdenticalImageCopiesDoNotDoubleCount() {
    val first = part(7, "2026-09-29", "5.36")
    val overlap = first.copy(query = first.query.copy(start = LocalDate.of(2026, 9, 1)))
    val sameImage = part(17, "2026-09-29", "5.36")
    val result =
        requireNotNull(
            ChatAnswerCalculations.combinedAmounts(
                listOf(first, overlap, sameImage), mapOf(7L to "same-bytes", 17L to "same-bytes")))
    assertEquals(1, result.amounts.size)
    assertEquals(BigDecimal("5.36"), result.totals["MYR"])
  }

  @Test
  fun missingEvidenceDoesNotProduceAnOverallTotalOrZeroSpending() {
    val first = part(7, "2026-09-29", "5.36")
    val empty = first.copy(amounts = ChatAmountResult(emptyList(), 0, 0), records = emptyList())
    val parts = listOf(first, empty)
    val result = ChatAnswerCalculations.combinedAmounts(parts)
    assertEquals("", ChatAnswerCalculations.combinedSummary(parts, result))
    assertTrue(empty.summary.contains("unknown"))
    assertEquals("", ChatAnswerCalculations.combinedSummary(listOf(first), result))
  }

  @Test
  fun differentCurrenciesKeepSeparateTotals() {
    val parts = listOf(part(7, "2026-09-29", "5.36"), part(8, "2026-09-26", "5.58", "USD"))
    val result = requireNotNull(ChatAnswerCalculations.combinedAmounts(parts))
    assertEquals(mapOf("MYR" to BigDecimal("5.36"), "USD" to BigDecimal("5.58")), result.totals)
    val summary = ChatAnswerCalculations.combinedSummary(parts, result)
    assertTrue(summary.contains("MYR 5.36"))
    assertTrue(summary.contains("USD 5.58"))
    assertFalse(summary.contains("10.94"))
  }
}
