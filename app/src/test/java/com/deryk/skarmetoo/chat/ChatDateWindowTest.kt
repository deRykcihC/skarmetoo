package com.deryk.skarmetoo.chat

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChatDateWindowTest {
  private val today = LocalDate.parse("2026-10-08")

  private fun checkPeriod(phrase: String, start: String, end: String, now: LocalDate = today) {
    val query = ChatQuery.parse("How much did I spend $phrase?", now)
    assertFalse(phrase + ": " + query.dateError, query.unsupportedDate)
    assertEquals(phrase, LocalDate.parse(start), query.start)
    assertEquals(phrase, LocalDate.parse(end).plusDays(1), query.endExclusive)
    assertTrue(phrase, query.includes(LocalDate.parse(start)))
    assertTrue(phrase, query.includes(LocalDate.parse(end)))
    assertFalse(phrase, query.includes(LocalDate.parse(start).minusDays(1)))
    assertFalse(phrase, query.includes(LocalDate.parse(end).plusDays(1)))
    assertTrue(phrase + " must not become a subject filter", query.terms.isEmpty())
  }

  @Test
  fun shortSpendingFragmentsDoNotRequireASentenceOrADatePreposition() {
    for ((question, start, end) in
        listOf(
            Triple("spending last week", "2026-10-01", "2026-10-08"),
            Triple("Oct 1 spending", "2026-10-01", "2026-10-01"))) {
      val query = ChatQuery.parse(question, today)
      assertTrue(query.spending)
      assertFalse(query.unsupportedDate)
      assertEquals(LocalDate.parse(start), query.start)
      assertEquals(LocalDate.parse(end).plusDays(1), query.endExclusive)
      assertTrue(query.terms.isEmpty())
    }
  }

  @Test
  fun lastMonthAndMonthToDateUseCalendarBoundaries() {
    listOf("last month", "past month", "previous month").forEach {
      checkPeriod(it, "2026-09-01", "2026-09-30")
    }
    checkPeriod("this month", "2026-10-01", "2026-10-08")
    checkPeriod("last month", "2025-12-01", "2025-12-31", LocalDate.parse("2026-01-01"))
    checkPeriod("last month", "2024-02-01", "2024-02-29", LocalDate.parse("2024-03-08"))
    checkPeriod("last month", "2026-02-01", "2026-02-28", LocalDate.parse("2026-03-08"))
  }

  @Test
  fun namedMonthsKeepExplicitYearsAndDefaultMissingYearToPhoneYear() {
    listOf(
            "in September",
            "in September 2026",
            "in Sept 2026",
            "in Sep 2026",
            "in 2026 September",
            "in September of 2026",
            "in 2026-09",
            "in 09/2026")
        .forEach { checkPeriod(it, "2026-09-01", "2026-09-30") }
    checkPeriod("in September 2025", "2025-09-01", "2025-09-30")
    checkPeriod("last October", "2025-10-01", "2025-10-31")
    checkPeriod("last September", "2026-09-01", "2026-09-30")
  }

  @Test
  fun writtenAndIsoDaysSelectExactlyOneDay() {
    listOf(
            "on 2026-10-05",
            "on 5 October 2026",
            "on October 5, 2026",
            "on 5th October",
            "on Oct 5th")
        .forEach { checkPeriod(it, "2026-10-05", "2026-10-05") }
  }

  @Test
  fun numericQueryDatesUseTheSameExplicitConventionAsReceiptDates() {
    checkPeriod("on 05/10/2026", "2026-10-05", "2026-10-05")
    val monthFirst = ChatQuery.parse("spent on 05/10/2026", today, ChatReceiptDateOrder.MONTH_FIRST)
    assertEquals(LocalDate.parse("2026-05-10"), monthFirst.start)
    assertTrue(
        ChatQuery.parse("spent on 05/10/2026", today, ChatReceiptDateOrder.UNRESOLVED)
            .unsupportedDate)
    assertFalse(
        ChatQuery.parse("spent on 30/09/2026", today, ChatReceiptDateOrder.UNRESOLVED)
            .unsupportedDate)
  }

  @Test
  fun writtenAndNumericRangesIncludeBothEndpoints() {
    listOf(
            "from 30 September to 2 October 2026",
            "between September 30, 2026 and October 2, 2026",
            "from 30/09/2026 through 02/10/2026",
            "2026-09-30 – 2026-10-02")
        .forEach { checkPeriod(it, "2026-09-30", "2026-10-02") }
    checkPeriod("from 31 December to 2 January 2027", "2026-12-31", "2027-01-02")
    checkPeriod("from 31 December 2026 to 2 January", "2026-12-31", "2027-01-02")
    checkPeriod("from 31 December to 2 January", "2025-12-31", "2026-01-02")
  }

  @Test
  fun monthWeeksAreSevenDayBlocksClippedAtMonthEnd() {
    checkPeriod("in the first week of September 2026", "2026-09-01", "2026-09-07")
    checkPeriod("in week 2 of September 2026", "2026-09-08", "2026-09-14")
    checkPeriod("in the third week of September", "2026-09-15", "2026-09-21")
    checkPeriod("in the 4th week of September", "2026-09-22", "2026-09-28")
    checkPeriod("in the fifth week of September", "2026-09-29", "2026-09-30")
    checkPeriod("in the last week of September", "2026-09-29", "2026-09-30")
    checkPeriod("in the last week of February 2026", "2026-02-22", "2026-02-28")
    checkPeriod("in the fifth week of February 2024", "2024-02-29", "2024-02-29")
    checkPeriod("in the first week of last month", "2026-09-01", "2026-09-07")
    checkPeriod("in the first week of the month", "2026-10-01", "2026-10-07")
  }

  @Test
  fun weekStartingAtADateAndPreviousWeekdaysHaveExplicitBoundaries() {
    checkPeriod("in the week of 30 September 2026", "2026-09-30", "2026-10-06")
    checkPeriod("last Monday", "2026-10-05", "2026-10-05")
    checkPeriod("last Thursday", "2026-10-01", "2026-10-01")
    checkPeriod("this Monday", "2026-10-05", "2026-10-05")
  }

  @Test
  fun unsupportedOrConflictingPeriodsNeverFallBackToAllDates() {
    listOf(
            "on 31 September 2026",
            "on 2026-02-29",
            "in 2026-13",
            "from 7 October 2026 to 5 October 2026",
            "on 5 October and 7 October",
            "on 1 October, 2 October and 3 October",
            "in the fifth week of February 2026",
            "in week 6 of September",
            "next September",
            "last month on Monday",
            "since 5 October 2026",
            "last year",
            "two months ago",
            "September 2026 and last week")
        .forEach { phrase ->
          assertTrue(
              phrase, ChatQuery.parse("How much did I spend $phrase?", today).unsupportedDate)
        }
  }

  @Test
  fun temporalPhrasesAreRemovedWhileSubjectAndDateBasisRemain() {
    val query =
        ChatQuery.parse(
            "How much did I spend on coffee in the second week of September 2026?", today)
    assertEquals(setOf("coffee"), query.terms)
    assertEquals(ChatDateBasis.EVENT_THEN_TAKEN, query.dateBasis)
    assertEquals(
        ChatDateBasis.TAKEN, ChatQuery.parse("Show screenshots I took last month", today).dateBasis)
    assertEquals(
        ChatDateBasis.ADDED, ChatQuery.parse("Show images imported in September", today).dateBasis)
    assertEquals(
        ChatDateBasis.ADDED, ChatQuery.parse("Show screenshots I saved yesterday", today).dateBasis)
    assertFalse(ChatQuery.parse("May I see my coffee photos?", today).unsupportedDate)
  }

  @Test
  fun savedContextDatesRequireAYearAndSupportTheVisibleDateForms() {
    assertEquals(
        listOf(LocalDate.parse("2026-09-30")),
        ChatDateWindows.explicitDates(
            "Completed Time 30-09-2026 12:41", ChatReceiptDateOrder.DAY_FIRST))
    assertEquals(
        listOf(LocalDate.parse("2025-09-30")),
        ChatDateWindows.explicitDates(
            "Transaction on September 30, 2025", ChatReceiptDateOrder.DAY_FIRST))
    assertTrue(
        ChatDateWindows.explicitDates("September 30", ChatReceiptDateOrder.DAY_FIRST).isEmpty())
  }
}
