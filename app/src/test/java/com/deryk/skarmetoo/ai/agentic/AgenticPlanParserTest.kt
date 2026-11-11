package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticPlanParserTest {
  @Test
  fun protectedLiteralSpellingTurnCannotReinterpretNumericDates() {
    val input = AgenticPlanParser.normalizationInput("spnd on 02/10/2026 and 09/29/2026")
    assertFalse(input.question.contains("02/10/2026"))
    assertEquals(
        "spending on 02/10/2026 and 09/29/2026",
        input.restore(input.question.replace("spnd", "spending")))
    assertEquals(listOf("02/10/2026", "09/29/2026"), input.literals.values.toList())
    assertTrue(runCatching { input.restore("spending on February 10, 2026") }.isFailure)
    assertTrue(
        runCatching { input.restore(input.question + input.literals.keys.first()) }.isFailure)
    val tokens = input.literals.keys.toList()
    assertTrue(
        runCatching { input.restore("spending ${tokens.reversed().joinToString("/")}") }.isFailure)
  }

  @Test
  fun protectedLiteralTokensAvoidUserTextCollisionsAndRetainLeadingZeros() {
    val q = "Total paid __USER_LITERAL_A__ on 09-29 and from 2026-09-26 to 2026-09-29"
    val input = AgenticPlanParser.normalizationInput(q)
    assertEquals(q, input.restore(input.question))
    assertEquals(q, AgenticPlanParser.normalizationInput(q).restore(input.question))
    assertEquals(
        "spending last week",
        AgenticPlanParser.normalizationInput("spnd lst week").restore("spending last week"))
  }

  private val today = LocalDate.parse("2026-10-08")
  private val earlier =
      QueryPlan(
          Intent.unknown,
          Operation.retrieve,
          null,
          null,
          emptyList(),
          emptyList(),
          emptyList(),
          "Does this image show a payment?",
          Operation.retrieve)

  private fun period(text: String) =
      JSONObject().put("kind", "period").put("start", text).put("end", JSONObject.NULL)

  private fun range(start: String, end: String) =
      JSONObject().put("kind", "range").put("start", start).put("end", end)

  private fun parsed(q: String, clauses: List<JSONObject>) =
      JSONObject()
          .put("normalized_question", q)
          .put("intent", "calculate_spending")
          .put("operation", "sum")
          .put("action_quote", "")
          .put("subject_phrase", "")
          .put("date_clauses", JSONArray(clauses))
          .put("search_keywords", JSONArray(listOf("payment")))

  private fun decode(o: JSONObject, q: String) =
      AgenticPlanParser.parse(o.toString(), q, today, earlier)

  @Test
  fun separateReverseOrderDaysSurviveAnEmptyRedundantRewrite() {
    val q = "spending oct 5 and oct 1"
    val clauses = listOf(period("October 5"), period("October 1"))
    val plan = decode(parsed("", clauses).put("search_keywords", JSONArray()), q)
    assertEquals(Intent.calculate_spending, plan.intent)
    assertEquals(Operation.sum, plan.operation)
    assertEquals(listOf("October 5", "October 1"), plan.dateFilter!!.expressions)
    assertEquals(
        listOf(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-01")),
        AgenticDates.resolve(plan.dateFilter, today).map { it.start })
    assertTrue(
        runCatching { decode(parsed("", listOf(range("October 5", "October 1"))), q) }.isFailure)
    assertTrue(runCatching { decode(parsed("", listOf(period("October 5"))), q) }.isFailure)
    assertTrue(
        runCatching { decode(parsed("", listOf(period("October 5"), period("October 2"))), q) }
            .isFailure)
  }

  @Test
  fun emptyRewriteDoesNotRelaxSeparateDaysOrTrueRangeCoverage() {
    for (separator in listOf("and", "&", ",")) {
      val q = "paid Oct 1 $separator Oct 5"
      assertEquals(
          2,
          decode(parsed("", listOf(period("October 1"), period("October 5"))), q)
              .dateFilter!!
              .expressions
              .size)
      assertTrue(
          runCatching { decode(parsed("", listOf(range("October 1", "October 5"))), q) }.isFailure)
    }
    val q = "paid between Oct 1 and Oct 5"
    assertEquals(
        1,
        decode(parsed("", listOf(range("October 1", "October 5"))), q)
            .dateFilter!!
            .expressions
            .size)
    assertTrue(
        runCatching { decode(parsed("", listOf(period("October 1"), period("October 5"))), q) }
            .isFailure)
  }

  @Test
  fun abbreviatedMonthsAndExpandedNamesDescribeTheSameRequestedDay() {
    val months =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December")
    for ((index, month) in months.withIndex()) {
      for (abbreviation in
          listOf(month.take(3)) + if (month == "September") listOf("Sept") else emptyList()) {
        val original = "spending at 5 $abbreviation"
        val expanded = "Spending at 5 $month"
        assertEquals(expanded, AgenticPlanParser.normalizedQuestion(original, expanded))
        val plan =
            decode(
                parsed(expanded, listOf(period("5 $month"))).put("subject_phrase", "Spending"),
                original)
        assertEquals(
            LocalDate.of(2026, index + 1, 5),
            AgenticDates.resolve(plan.dateFilter, today).single().start)
        assertNull(plan.category)
        AgenticQueryCoverage.validate(expanded, listOf("5 $abbreviation"))
      }
    }
  }

  @Test
  fun monthAliasesStillRejectChangedDaysAndMissingOrSplitPeriods() {
    val q = "spending at 5 Oct and from 1 Sep to 3 Sept"
    val normalized = "Spending at 5 October and from 1 September to 3 September"
    val valid = listOf(period("5 October"), range("1 September", "3 September"))
    assertEquals(2, decode(parsed(normalized, valid), q).dateFilter!!.expressions.size)
    for (invalid in
        listOf(
            listOf(period("6 October"), range("1 September", "3 September")),
            listOf(period("5 November"), range("1 September", "3 September")),
            listOf(range("1 September", "3 September")),
            listOf(period("5 October"), period("1 September"), period("3 September")))) {
      assertTrue(runCatching { decode(parsed(normalized, invalid), q) }.isFailure)
    }
    assertTrue(
        runCatching { AgenticPlanParser.normalizedQuestion("paid 5 Oct", "paid 5 November") }
            .isFailure)
    assertTrue(
        runCatching { AgenticPlanParser.normalizedQuestion("paid 5 Oct", "paid 5 October 2026") }
            .isFailure)
  }

  @Test
  fun dayAndRangeRemainDistinctAndEndpointsStayPaired() {
    val q = "Total paid on 09-29 and from 2026-09-26 to 2026-09-29"
    val plan = decode(parsed(q, listOf(period("09-29"), range("2026-09-26", "2026-09-29"))), q)
    assertEquals(2, plan.dateFilter!!.expressions.size)
    assertEquals(
        LocalDate.parse("2026-09-26"), AgenticDates.resolve(plan.dateFilter, today)[1].start)
    assertTrue(
        runCatching { decode(parsed(q, listOf(range("2026-09-26", "2026-09-29"))), q) }.isFailure)
    assertTrue(
        runCatching {
              decode(
                  parsed(q, listOf(period("09-29"), period("2026-09-26"), period("2026-09-29"))), q)
            }
            .isFailure)
  }

  @Test
  fun containedRangeCannotReplaceRequestedMonth() {
    val q = "How much did my paid purchases cost in September and from 2026-09-26 to 2026-09-29?"
    val r = range("2026-09-26", "2026-09-29")
    assertTrue(runCatching { decode(parsed(q, listOf(r)), q) }.isFailure)
    val plan = decode(parsed(q, listOf(period("September"), r)), q)
    assertEquals(
        LocalDate.parse("2026-09-01"), AgenticDates.resolve(plan.dateFilter, today)[0].start)
  }

  @Test
  fun modelSpellingNormalizationPreservesMeaningAndLiteralDates() {
    val plan = decode(parsed("spending last week", listOf(period("last week"))), "spnd lst week")
    assertEquals(Intent.calculate_spending, plan.intent)
    assertEquals(
        LocalDate.parse("2026-09-28"), AgenticDates.resolve(plan.dateFilter, today).single().start)
    assertTrue(
        runCatching {
              decode(parsed("spending today 2026", listOf(period("today"))), "spnd lst week")
            }
            .isFailure)
  }

  @Test
  fun barePaymentsCannotSilentlyBecomeAnExplicitListOrCount() {
    val q = "PAYMENTS TODAY AND YESTERDAY"
    val o = parsed(q, listOf(period("today"), period("yesterday")))
    assertEquals(Operation.sum, decode(o, q).operation)
    o.put("intent", "find_transaction").put("operation", "retrieve").put("action_quote", "PAYMENTS")
    assertTrue(runCatching { decode(o, q) }.isFailure)
    val count = "How many payments yesterday?"
    assertEquals(
        Intent.count_transactions,
        decode(
                parsed(count, listOf(period("yesterday")))
                    .put("intent", "count_transactions")
                    .put("operation", "count")
                    .put("action_quote", "How many"),
                count)
            .intent)
  }

  @Test
  fun itemSubjectsNeedAnOriginalQuoteAndCannotBecomeAllSpending() {
    val q = "How much did I pay for coffee last week?"
    val plan = decode(parsed(q, listOf(period("last week"))).put("subject_phrase", "coffee"), q)
    assertEquals("coffee", plan.category)
    assertTrue(
        runCatching {
              decode(parsed(q, listOf(period("last week"))).put("subject_phrase", "tea"), q)
            }
            .isFailure)
  }

  @Test
  fun pricesInMenusAndCartsAreNotCompletedPaymentsAndQuotedNullIsAbsent() {
    for (kind in listOf("menu", "product_listing", "cart_checkout")) {
      val raw =
          """{"image_kind":"$kind","direction":"unknown","payment_status":"unknown","transaction_date":"null","amount":"16.90","currency":"MYR","merchant":null,"evidence":"Price RM16.90"}"""
      assertEquals(Match.NO, AgenticPaymentFacts.verification(raw, "x").match)
      assertNull(AgenticPaymentFacts.verification(raw, "x").transactionDate)
    }
  }

  @Test
  fun unresolvedSubtotalAndCategoryUncertaintyStayExplicit() {
    val total =
        CalculationResult(
            mapOf("MYR" to BigDecimal("53.88")), emptyList(), listOf("order"), emptyList())
    val answer = AgenticCalculation.answer(Intent.calculate_spending, total)
    assertTrue(answer.contains("Confirmed subtotal: MYR 53.88"))
    assertTrue(answer.contains("complete total cannot be determined"))
    val missing =
        AgenticCalculation.answer(
            Intent.calculate_spending, total.copy(totals = emptyMap()), "coffee")
    assertTrue(missing.contains("cannot determine coffee spending"))
  }

  @Test
  fun completeRelativePhraseSurvivesAMislabeledRangeWithoutLosingCoffee() {
    val q = "How much did I pay for coffee last week?"
    val c = period("last week").put("kind", "range")
    val plan = decode(parsed(q, listOf(c)).put("subject_phrase", "coffee"), q)
    assertEquals("coffee", plan.category)
    assertEquals(
        LocalDate.parse("2026-09-28"), AgenticDates.resolve(plan.dateFilter, today).single().start)
    assertNull(AgenticPaymentFacts.category("total paid"))
  }

  @Test
  fun spellingNormalizationCannotDropPeriodsOrInsertLiteralDates() {
    assertEquals(
        "spending last week",
        AgenticPlanParser.normalizedQuestion("spnd lst week", "spending last week"))
    assertTrue(
        runCatching {
              AgenticPlanParser.normalizedQuestion("spending September and today", "spending today")
            }
            .isFailure)
    assertTrue(
        runCatching { AgenticPlanParser.normalizedQuestion("paid yesterday", "paid today") }
            .isFailure)
    assertTrue(
        runCatching { AgenticPlanParser.normalizedQuestion("paid on 09-29", "paid on 09-29-2026") }
            .isFailure)
  }
}
