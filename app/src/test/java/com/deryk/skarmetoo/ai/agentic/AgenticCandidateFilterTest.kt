package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AgenticCandidateFilterTest {
  private fun date(value: String) = LocalDate.parse(value)

  private val window = listOf(ChatDateWindow(date("2026-09-29"), date("2026-09-30")))

  private fun check(
      ocr: String? = null,
      taken: String? = null,
      context: String = "",
      known: List<String> = emptyList(),
      windows: List<ChatDateWindow> = window,
      intent: Intent = Intent.calculate_spending
  ) =
      AgenticCandidateFilter.decide(
          intent,
          windows,
          AgenticCandidateFilter.Evidence(ocr, taken?.let(::date), context, known.map(::date)))

  @Test
  fun printedPaymentDateOverridesLaterScreenshot() {
    val result = check("Successful\nCompleted Time 29-09-2026 12:43", "2026-10-08")
    assertTrue(result.include)
    assertEquals("printed_event_ocr", result.dateBasis)
  }

  @Test
  fun printedDateOutsideRangeExcludesDespiteCaptureInside() {
    assertFalse(check("Payment Date 01-10-2026", "2026-09-29").include)
  }

  @Test
  fun dueOrDeliveryDateCannotBecomePaymentDate() {
    listOf("Due Date 01-10-2026", "Delivery Date 01-10-2026", "Invoice Date 01-10-2026").forEach {
      val decision = check(it, "2026-10-08")
      assertTrue(decision.include)
      assertNull(decision.date)
    }
  }

  @Test
  fun noEventDateUsesCaptureFallback() {
    assertFalse(check("You Paid RM5.72", "2026-10-08").include)
    assertTrue(check("You Paid RM5.72", "2026-09-29").include)
  }

  @Test
  fun missingDatesAreRetained() {
    assertTrue(check().include)
  }

  @Test
  fun unreadablePrintedDatePreventsCaptureExclusion() {
    val result = check("Completed Time unreadable", "2026-10-08")
    assertTrue(result.include)
    assertNull(result.date)
  }

  @Test
  fun invalidPrintedDateIsUnknownNotCapture() {
    assertTrue(check("Transaction Date 31-02-2026", "2026-10-08").include)
  }

  @Test
  fun multipleConflictingDatesRetainCandidate() {
    assertTrue(check("Payment Date 29-09-2026\nTransaction Date 01-10-2026", "2026-10-08").include)
  }

  @Test
  fun yearlessDateDoesNotGuessYear() {
    assertTrue(check("Payment Date 29 Sep", "2026-10-08").include)
  }

  @Test
  fun verifiedDateOverridesCaptureWhenOcrMissing() {
    assertTrue(check(taken = "2026-10-08", known = listOf("2026-09-29")).include)
    assertFalse(check(taken = "2026-09-29", known = listOf("2026-10-08")).include)
  }

  @Test
  fun conflictingModelAndPrintedDatesStayUncertain() {
    assertTrue(check("Payment Date 01-10-2026", known = listOf("2026-09-29")).include)
  }

  @Test
  fun explicitNonpaymentContextExcludesButPaymentCluesWin() {
    assertFalse(check(context = "This is a stock chart screenshot").include)
    assertTrue(check("Payment successful", context = "Game purchase screen").include)
    assertFalse(check(context = "A game event screenshot").include)
  }

  @Test
  fun absenceOfKeywordsDoesNotExcludeUnknownContext() {
    assertTrue(check(context = "Unrecognized screenshot").include)
  }

  @Test
  fun describeImageDoesNotExcludeGames() {
    assertTrue(check(context = "A game screenshot", intent = Intent.describe_image).include)
  }

  @Test
  fun combinedRangesAreUnionedWithoutTopK() {
    val windows = window + ChatDateWindow(date("2026-10-01"), date("2026-10-03"))
    val retained = (1..900).count { check("Payment Date 01-10-2026", windows = windows).include }
    assertEquals(900, retained)
    assertFalse(check("Payment Date 30-09-2026", windows = windows).include)
  }

  @Test
  fun labelledDatesSupportIsoAndNamedMonths() {
    assertTrue(check("Date: 2026-09-29").include)
    assertTrue(check("Date: 29 Sep 2026").include)
  }
}
