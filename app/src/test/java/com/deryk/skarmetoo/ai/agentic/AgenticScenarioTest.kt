package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Explicit deterministic fixtures, not claimed as model/device query accuracy. */
class AgenticScenarioTest {
  private val today = LocalDate.parse("2026-10-10")

  private fun receipt(
      id: String,
      date: String = "2026-10-01",
      amount: String? = "5.72",
      currency: String = "MYR",
      merchant: String = "Deen Maju",
      status: PaymentStatus = PaymentStatus.completed,
      match: Match = Match.YES
  ) =
      VerifiedTransaction(
          id,
          listOf(id),
          amount?.let(::BigDecimal),
          currency,
          LocalDate.parse(date),
          "printed_event",
          merchant,
          status,
          match,
          false)

  @Test
  fun lastWeekSpending() {
    val r =
        AgenticCalculation.calculate(
            listOf(receipt("1"), receipt("2", "2026-09-26")),
            AgenticDates.resolve(DateFilter(listOf("last week")), today))
    assertEquals(BigDecimal("5.72"), r.totals["MYR"])
  }

  @Test
  fun foodSpendingUsesVerifiedSemanticMatch() {
    val r = AgenticCalculation.calculate(listOf(receipt("food"), receipt("fuel", match = Match.NO)))
    assertEquals(listOf("food"), r.transactions.map { it.id })
  }

  @Test
  fun yesterdayReceipts() {
    val r =
        AgenticCalculation.calculate(
            listOf(receipt("yesterday", "2026-10-09"), receipt("today", "2026-10-10")),
            AgenticDates.resolve(DateFilter(listOf("yesterday")), today),
            false)
    assertEquals(listOf("yesterday"), r.transactions.map { it.id })
  }

  @Test
  fun specificMerchantVerifiedByModelContract() {
    val r =
        AgenticCalculation.calculate(
            listOf(receipt("deen"), receipt("other", merchant = "Nasi Lemak", match = Match.NO)))
    assertEquals(1, r.transactions.size)
  }

  @Test
  fun countPaymentsDoesNotRequireAmount() {
    assertEquals(
        2,
        AgenticCalculation.calculate(
                listOf(receipt("1"), receipt("2", amount = null)), requireAmount = false)
            .transactions
            .size)
  }

  @Test
  fun noRelevantImage() {
    assertTrue(
        AgenticCalculation.calculate(listOf(receipt("no", match = Match.NO))).totals.isEmpty())
  }

  @Test
  fun receiptMissingAmount() {
    assertEquals(
        listOf("missing"),
        AgenticCalculation.calculate(listOf(receipt("missing", amount = null))).unresolvedIds)
  }

  @Test
  fun multipleScreenshotsOneTransaction() {
    val one = receipt("same")
    val two = one.copy(imageIds = listOf("second"), amount = BigDecimal("5.720"))
    val r = AgenticCalculation.calculate(listOf(one, two))
    assertEquals(1, r.transactions.size)
    assertEquals(2, r.transactions.single().imageIds.size)
  }

  @Test
  fun refundIsNotOutgoingSpending() {
    assertEquals(
        listOf("refund"),
        AgenticCalculation.calculate(listOf(receipt("refund", status = PaymentStatus.refunded)))
            .excludedIds)
  }

  @Test
  fun currenciesAreNeverAddedTogether() {
    val r = AgenticCalculation.calculate(listOf(receipt("myr"), receipt("usd", currency = "USD")))
    assertEquals(2, r.totals.size)
  }

  @Test
  fun irrelevantScreenshot() {
    assertTrue(
        AgenticCalculation.calculate(listOf(receipt("game", match = Match.NO)))
            .transactions
            .isEmpty())
  }

  @Test
  fun ambiguousDateDoesNotInventMissingEvidence() {
    val r =
        AgenticCalculation.calculate(
            listOf(receipt("ambiguous").copy(date = null, requiresReview = true)),
            AgenticDates.resolve(DateFilter(listOf("last week")), today))
    assertEquals(listOf("ambiguous"), r.unresolvedIds)
  }

  @Test
  fun conflictingDuplicatesStayUnresolvedEvenWithThirdCopy() {
    val a = receipt("id")
    val b = a.copy(imageIds = listOf("b"), amount = BigDecimal("6.72"))
    val c = a.copy(imageIds = listOf("c"))
    val r = AgenticCalculation.calculate(listOf(a, b, c))
    assertTrue(r.transactions.isEmpty())
    assertEquals(3, r.unresolvedIds.size)
  }

  @Test
  fun strictJsonRejectsLenientSyntaxAndDuplicateKeys() {
    listOf(
            "{a:1}",
            "{\"a\":'text'}",
            "{\"a\":true,}",
            "{\"a\":01}",
            "{\"a\":null,\"a\":null}",
            "{\"a\":NaN}")
        .forEach { raw -> assertTrue(raw, runCatching { AgenticContracts.parse(raw) }.isFailure) }
  }

  @Test
  fun strictJsonAllowsEscapesAndWhitespace() {
    assertEquals("a\nb", AgenticContracts.parse(" {\"text\":\"a\\nb\"} ").getString("text"))
  }

  @Test
  fun monthAndNumberedWeekDates() {
    val month = AgenticDates.resolve(DateFilter(listOf("last month")), today).single()
    assertEquals(LocalDate.parse("2026-09-01"), month.start)
    assertEquals(LocalDate.parse("2026-10-01"), month.endExclusive)
    val week = AgenticDates.resolve(DateFilter(listOf("first week of September")), today).single()
    assertEquals(LocalDate.parse("2026-09-08"), week.endExclusive)
  }

  @Test
  fun unambiguousMonthFirstDateAndPreviousWeek() {
    assertEquals(
        LocalDate.parse("2026-09-29"),
        AgenticDates.resolve(DateFilter(listOf("09-29")), today).single().start)
    assertEquals(
        LocalDate.parse("2026-09-28"),
        AgenticDates.resolve(DateFilter(listOf("previous week")), today).single().start)
  }
}
