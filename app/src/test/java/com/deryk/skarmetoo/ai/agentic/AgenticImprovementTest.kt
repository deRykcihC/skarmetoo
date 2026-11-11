package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticImprovementTest {
  private val today = LocalDate.parse("2026-10-08")

  private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

  private fun facts() =
      JSONObject(
          """{"image_kind":"payment_receipt","direction":"outgoing","payment_status":"completed","transaction_date":"2026-10-01","amount":"5.72","currency":"MYR","merchant":"Deen Maju","evidence":"Successful You Paid RM5.72"}""")

  @Test
  fun dayBeforeYesterdayIsTwoDaysEarlier() {
    val range = AgenticDates.resolve(DateFilter(listOf("the day before yesterday")), today).single()
    assertEquals(today.minusDays(2), range.start)
    assertEquals(today.minusDays(1), range.endExclusive)
    AgenticQueryCoverage.validate(
        "How much did I spend the day before yesterday?", listOf("day before yesterday"))
  }

  @Test
  fun slashAndCompactIsoRangesStayRanges() {
    for (expression in listOf("30/09/2026-02/10/2026", "2026-09-30-2026-10-02")) {
      val range = AgenticDates.resolve(DateFilter(listOf(expression)), today).single()
      assertEquals(LocalDate.parse("2026-09-30"), range.start)
      assertEquals(LocalDate.parse("2026-10-03"), range.endExclusive)
      AgenticQueryCoverage.validate(
          "Paid from ${expression.replace(Regex("(?<=2026)-(?=02|2026)"), " to ")}",
          listOf(expression))
    }
  }

  @Test
  fun originalSlashRangeCanUseTheModelsCompactEquivalent() {
    AgenticQueryCoverage.validate(
        "How much did I pay for my purchases from 30/09/2026 to 02/10/2026?",
        listOf("30/09/2026-02/10/2026"))
    rejects {
      AgenticQueryCoverage.validate(
          "Paid from 2026-09-30 to 2026-10-02", listOf("2026-09-30 and 2026-10-02"))
    }
  }

  @Test
  fun rangesCannotBecomeTwoEndpointDays() {
    rejects {
      AgenticQueryCoverage.validate(
          "Paid between September 29 and October 2, 2026",
          listOf("September 29", "October 2, 2026"))
    }
    AgenticQueryCoverage.validate(
        "Paid between September 29 and October 2, 2026",
        listOf("between September 29 and October 2, 2026"))
  }

  @Test
  fun everyCombinedPeriodIsRequired() {
    val q = "Spending in September and from 2026-09-26 to 2026-09-29"
    rejects { AgenticQueryCoverage.validate(q, listOf("from 2026-09-26 to 2026-09-29")) }
    AgenticQueryCoverage.validate(q, listOf("September", "from 2026-09-26 to 2026-09-29"))
    rejects {
      AgenticQueryCoverage.validate("Spending today and yesterday", listOf("today and yesterday"))
    }
    AgenticQueryCoverage.validate("Spending today and yesterday", listOf("today", "yesterday"))
  }

  @Test
  fun anUnboundedQuestionCannotAcquireToday() {
    rejects { AgenticQueryCoverage.validate("How much did I spend?", listOf("today")) }
    AgenticQueryCoverage.validate("How much did I spend?", emptyList())
    assertTrue(AgenticDates.resolve(DateFilter(emptyList()), today).isEmpty())
  }

  @Test
  fun outgoingMinusMagnitudeDoesNotTurnRefundsIntoSpending() {
    assertEquals(
        BigDecimal("5.72"),
        AgenticPaymentFacts.verification(facts().put("amount", "-RM5.72").toString(), "x").amount)
    rejects {
      AgenticPaymentFacts.verification(
          facts().put("amount", "-RM5.72").put("currency", "USD").toString(), "x")
    }
    rejects {
      AgenticPaymentFacts.verification(
          facts().put("amount", "-RM5.72").put("payment_status", "refunded").toString(), "x")
    }
    rejects {
      AgenticPaymentFacts.verification(
          facts().put("amount", "-RM5.72").put("direction", "incoming").toString(), "x")
    }
  }

  @Test
  fun textRepairCannotInventOrChangeFinancialValues() {
    val original = facts().toString().dropLast(1)
    AgenticPaymentFacts.validateRepair(original, facts())
    rejects { AgenticPaymentFacts.validateRepair(original, facts().put("amount", "9.99")) }
    rejects {
      AgenticPaymentFacts.validateRepair(original.dropLast(1) + ",\"amount\":\"9.99\"}", facts())
    }
    rejects {
      AgenticPaymentFacts.validateRepair(facts().also { it.remove("amount") }.toString(), facts())
    }
  }

  @Test
  fun wallpaperIsNonpaymentWithoutAQuerySpecificDecision() {
    val v =
        AgenticPaymentFacts.verification(
            facts()
                .put("image_kind", "other")
                .put("direction", "unknown")
                .put("payment_status", "unknown")
                .put("amount", JSONObject.NULL)
                .put("currency", JSONObject.NULL)
                .put("transaction_date", JSONObject.NULL)
                .toString(),
            "wallpaper")
    assertEquals(Match.NO, v.match)
    assertFalse(v.requiresReview)
  }

  @Test
  fun categoryClaimsRequireAQuoteInOriginalEvidence() {
    val v = AgenticPaymentFacts.verification(facts().toString(), "x")
    val decision =
        JSONObject("""{"match":"YES","evidence_quote":"Coffee RM5.72","reason":"Coffee item"}""")
    val unknown = AgenticPaymentFacts.relevance(v, decision, "You Paid RM5.72")
    assertEquals(Match.MAYBE, unknown.match)
    assertTrue(unknown.requiresReview)
    assertEquals(Match.YES, AgenticPaymentFacts.relevance(v, decision, "Item: Coffee RM5.72").match)
  }

  @Test
  fun uncertainOrContradictoryNonpaymentCannotBeCachedAsNo() {
    assertEquals(
        Match.MAYBE,
        AgenticPaymentFacts.verification(facts().put("image_kind", "other").toString(), "x").match)
    assertEquals(
        Match.MAYBE,
        AgenticPaymentFacts.verification(facts().put("evidence", "").toString(), "x").match)
  }

  @Test
  fun actualPrintedDateFormatKeepsTheSameEventAndRepairMayNormalizeOnlyThatDate() {
    val raw = facts().put("transaction_date", "07-10-2026")
    assertEquals(
        LocalDate.parse("2026-10-07"),
        AgenticPaymentFacts.verification(raw.toString(), "x").transactionDate)
    val repaired = JSONObject(raw.toString()).put("transaction_date", "2026-10-07")
    AgenticPaymentFacts.validateRepair(raw.toString(), repaired)
    rejects {
      AgenticPaymentFacts.validateRepair(
          raw.toString(), repaired.put("transaction_date", "2026-07-10"))
    }
    rejects {
      AgenticPaymentFacts.verification(raw.put("transaction_date", "31-02-2026").toString(), "x")
    }
  }

  @Test
  fun genericSpendingConceptsDoNotBecomeItemRestrictions() {
    assertNull(AgenticPaymentFacts.category("spending"))
    assertNull(AgenticPaymentFacts.category("outgoing payments"))
    assertEquals("coffee", AgenticPaymentFacts.category("coffee"))
  }
}
