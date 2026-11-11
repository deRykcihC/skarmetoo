package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RagRetrievalTest {
  @Test
  fun lastWeekIncludesTheDateSevenDaysAgoAndToday() {
    val query = ChatQuery.parse("How much did I spend last week?", LocalDate.parse("2026-10-04"))
    assertTrue(query.spending)
    assertEquals(LocalDate.parse("2026-09-27"), query.start)
    assertEquals(LocalDate.parse("2026-10-05"), query.endExclusive)
    assertFalse(query.includes(LocalDate.parse("2026-09-26")))
    assertTrue(query.includes(LocalDate.parse("2026-09-27")))
    assertTrue(query.includes(LocalDate.parse("2026-09-28")))
    assertTrue(query.includes(LocalDate.parse("2026-10-04")))
    assertFalse(query.includes(LocalDate.parse("2026-10-05")))
    assertTrue(query.terms.isEmpty())
  }

  @Test
  fun pastWeekAliasesIncludeBothBoundaryDatesAcrossMonthAndYearBoundaries() {
    for (today in listOf("2026-10-04", "2026-10-05", "2026-10-07", "2027-01-01")) {
      val now = LocalDate.parse(today)
      for (phrase in listOf("last week", "past week", "past last week", "previous week")) {
        val query = ChatQuery.parse("How much did I spend $phrase?", now)
        assertEquals(phrase, now.minusDays(7), query.start)
        assertEquals(phrase, now.plusDays(1), query.endExclusive)
        assertEquals(
            phrase,
            8,
            java.time.temporal.ChronoUnit.DAYS.between(query.start, query.endExclusive).toInt())
        assertFalse(phrase, query.unsupportedDate)
        assertTrue(phrase, query.terms.isEmpty())
      }
    }
  }

  @Test
  fun anExplicitSevenDayCountStillMeansSevenDates() {
    val today = LocalDate.parse("2026-10-07")
    for (phrase in listOf("last 7 days", "past 7 days")) {
      val query = ChatQuery.parse("How much did I spend $phrase?", today)
      assertEquals(today.minusDays(6), query.start)
      assertFalse(query.includes(LocalDate.parse("2026-09-30")))
    }
    val week = ChatQuery.parse("How much did I spend last week?", today)
    assertEquals("2026-09-30 to 2026-10-07", week.period)
    assertTrue(week.includes(LocalDate.parse("2026-09-30")))
  }

  @Test
  fun explicitCalendarWeekStillUsesMondayToSunday() {
    val today = LocalDate.parse("2026-10-04")
    for (phrase in listOf("last calendar week", "previous calendar week")) {
      val query = ChatQuery.parse("How much did I spend $phrase?", today)
      assertEquals(LocalDate.parse("2026-09-21"), query.start)
      assertEquals(LocalDate.parse("2026-09-28"), query.endExclusive)
      assertTrue(query.terms.isEmpty())
    }
    val current = ChatQuery.parse("How much did I spend this week?", today)
    assertEquals(LocalDate.parse("2026-09-28"), current.start)
    assertEquals(LocalDate.parse("2026-10-05"), current.endExclusive)
  }

  @Test
  fun rollingWeekIncludingTodayDoesNotBecomeATodayOnlyQuery() {
    val today = LocalDate.parse("2026-10-04")
    val query = ChatQuery.parse("How much did I spend in the past week including today?", today)
    assertEquals(today.minusDays(7), query.start)
    assertEquals(today.plusDays(1), query.endExclusive)
    val explicit = ChatQuery.parse("last week from 2026-09-21 to 2026-09-27", today)
    assertEquals(LocalDate.parse("2026-09-21"), explicit.start)
    assertEquals(LocalDate.parse("2026-09-28"), explicit.endExclusive)
  }

  @Test
  fun explicitRangeAndUnknownDatesAreHandled() {
    val query = ChatQuery.parse("spending 2026-10-01 to 2026-10-03")
    assertTrue(query.includes(LocalDate.parse("2026-10-03")))
    assertFalse(query.includes(null))
    assertTrue(ChatQuery.parse("What did I spend last year?").unsupportedDate)
    assertTrue(ChatQuery.parse("2026-10-04 to 2026-10-01").unsupportedDate)
    assertTrue(ChatQuery.parse("spent on 2026-13-01").unsupportedDate)
  }

  private fun receipt(id: Long, amount: String, currency: String, reference: String = "abc") =
      ReceiptFacts.parse(
          id,
          """{"is_transaction":true,"paid":true,"confidence":0.95,"total":"$amount","currency":"$currency","date":"2026-10-01","merchant":"Cafe","transaction_id":"$reference","items":"noodles","evidence":"PAID TOTAL $amount"}""")!!

  @Test
  fun sumsDecimalAmountsSeparatelyByCurrencyAndRemovesDuplicateReceipts() {
    val unique =
        ReceiptFacts.deduplicate(
            listOf(
                receipt(1, "0.10", "SGD"),
                receipt(2, "0.10", "SGD"),
                receipt(3, "0.20", "SGD", "def"),
                receipt(4, "12.90", "MYR")))
    assertEquals(3, unique.size)
    assertEquals(BigDecimal("0.30"), ReceiptFacts.totals(unique)["SGD"])
    assertEquals(BigDecimal("12.90"), ReceiptFacts.totals(unique)["MYR"])
  }

  @Test
  fun ambiguousUnpaidAndMalformedEvidenceIsRejected() {
    assertNull(ReceiptFacts.parse(1, "not JSON"))
    assertNull(ReceiptFacts.parse(1, """{"is_transaction":true,"paid":false}"""))
    assertNull(
        ReceiptFacts.parse(
            1,
            """{"is_transaction":true,"paid":true,"confidence":0.9,"currency":"$","total":"10","date":"2026-10-01","evidence":"total"}"""))
    assertNull(
        ReceiptFacts.parse(
            1,
            """{"is_transaction":true,"paid":true,"confidence":0.9,"currency":"SGD","total":"-10","date":"2026-10-01","evidence":"total"}"""))
  }

  @Test
  fun itemQuestionUsesItsLineAmountInsteadOfFullReceiptTotal() {
    val fact =
        receipt(1, "15.26", "MYR")
            .copy(
                lineItems =
                    listOf(
                        ReceiptItem("Beef noodles", BigDecimal("11.90")),
                        ReceiptItem("Tea", BigDecimal("2.50"))))
    assertEquals(
        BigDecimal("11.90"),
        ReceiptFacts.forSubject(fact, setOf("noodles"), listOf("food"))!!.amount)
    assertNull(
        ReceiptFacts.forSubject(
            fact.copy(lineItems = emptyList()), setOf("noodles"), listOf("food")))
    assertEquals(
        BigDecimal("15.26"), ReceiptFacts.forSubject(fact, emptySet(), emptyList())!!.amount)
  }
}
