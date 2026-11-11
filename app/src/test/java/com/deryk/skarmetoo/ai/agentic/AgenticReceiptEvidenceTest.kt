package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AgenticReceiptEvidenceTest {
  @Test
  fun printedReferenceSurvivesACopyButtonBetweenLabelAndValue() {
    val expected = "ATIRIACGXPNX8S"
    for (text in
        listOf(
            "Transaction SN\nCopy\nATIRiAcgXpnx8S",
            "Transaction SN: ATIRiAcgXpnx8S",
            "Receipt ID\nCopy ATIRiAcgXpnx8S")) {
      assertEquals(expected, AgenticReceiptEvidence.transactionReference(text))
    }
    assertEquals(
        "COPY12345678", AgenticReceiptEvidence.transactionReference("Transaction ID COPY12345678"))
    assertNull(
        AgenticReceiptEvidence.transactionReference("You Paid RM5.72\nOrder SN 253008364774152316"))
    assertNull(AgenticReceiptEvidence.transactionReference("Transaction ID Unavailable"))
    assertNull(
        AgenticReceiptEvidence.transactionReference(
            "Transaction SN ATIRiAcgXpnx8S\nTransaction ID DIFFERENT12345"))
  }

  @Test
  fun duplicateScreenshotsKeepBothSourcesButCountOnePayment() {
    fun payment(reference: String, image: String, amount: String) =
        VerifiedTransaction(
            reference,
            listOf(image),
            BigDecimal(amount),
            "MYR",
            LocalDate.parse("2026-10-01"),
            "printed_event",
            "Merchant",
            PaymentStatus.completed,
            Match.YES,
            false)
    val reference =
        AgenticReceiptEvidence.transactionReference("Transaction SN\nCopy\nATIRiAcgXpnx8S")!!
    val a = payment(reference, "first-screenshot", "5.72")
    val b = payment(reference, "second-screenshot", "5.72")
    val other = payment("DIFFERENT12345", "other-payment", "7.77")
    val total = AgenticCalculation.calculate(listOf(a, b, other))
    assertEquals(BigDecimal("13.49"), total.totals.getValue("MYR"))
    assertEquals(2, total.transactions.size)
    assertEquals(
        listOf("first-screenshot", "second-screenshot"), total.transactions.first().imageIds)
    assertEquals(
        2, AgenticCalculation.calculate(listOf(a, b.copy(id = "SEPARATE12345"))).transactions.size)
    val conflict =
        AgenticCalculation.calculate(listOf(a, b.copy(amount = BigDecimal("6.72")), other))
    assertEquals(BigDecimal("7.77"), conflict.totals.getValue("MYR"))
    assertEquals(listOf("first-screenshot", "second-screenshot"), conflict.unresolvedIds)
  }

  private val visual =
      ImageVerification(
          "id",
          Match.YES,
          null,
          null,
          null,
          null,
          null,
          PaymentStatus.completed,
          listOf("payment"),
          "Successful payment",
          false)
  private val ocr =
      "Successful\nCompleted Time 01-10-2026 12:52\nPay To\nExample Cafe\nYou Paid RM5.72\nPurchase Amount RM6.35\nRewards RM0.20"

  @Test
  fun unreadablePrintedDateLabelsPreventCaptureFallback() {
    assertTrue(AgenticReceiptEvidence.hasPrintedEventDate("Completed Time blurred"))
    assertTrue(AgenticReceiptEvidence.hasPrintedEventDate("Paid on unreadable"))
    assertTrue(AgenticReceiptEvidence.hasPrintedEventDate("Date: unreadable"))
    assertTrue(AgenticReceiptEvidence.hasPrintedEventDate("Date:01-10-2026"))
    assertFalse(AgenticReceiptEvidence.hasPrintedEventDate("You Paid RM5.72"))
  }

  @Test
  fun explicitPaidLabelsCompleteVisibleFields() {
    val result = AgenticReceiptEvidence.complete(visual, ocr)
    assertEquals(BigDecimal("5.72"), result.verification.amount)
    assertEquals("MYR", result.verification.currency)
    assertEquals(LocalDate.parse("2026-10-01"), result.verification.transactionDate)
    assertEquals("Example Cafe", result.verification.merchant)
    assertFalse(result.verification.requiresReview)
  }

  @Test
  fun billAndArbitraryNumbersCannotBecomePaidAmount() {
    val result =
        AgenticReceiptEvidence.complete(visual, "Amount Due RM600\nSubtotal RM750\nRewards RM20")
    assertNull(result.verification.amount)
    assertNull(result.verification.currency)
  }

  @Test
  fun incompleteOrUnrelatedVisualContextCannotBeApprovedByOcr() {
    listOf(
            visual.copy(match = Match.NO),
            visual.copy(match = Match.MAYBE),
            visual.copy(paymentStatus = PaymentStatus.unknown))
        .forEach { assertEquals(it, AgenticReceiptEvidence.complete(it, ocr).verification) }
  }

  @Test
  fun conflictingModelAmountRequiresReview() {
    val result =
        AgenticReceiptEvidence.complete(visual.copy(amount = BigDecimal("0.77")), "You Paid RM7.77")
    assertTrue(result.verification.requiresReview)
    assertEquals(listOf("amount"), result.conflicts)
    assertEquals(BigDecimal("0.77"), result.verification.amount)
  }

  @Test
  fun ambiguousPaidLabelsDoNotChooseAnAmount() {
    val result = AgenticReceiptEvidence.complete(visual, "You Paid RM5.72\nAmount Paid USD8.00")
    assertNull(result.verification.amount)
    assertTrue(result.verification.requiresReview)
  }

  @Test
  fun conflictingOrInvalidDatesAreNeverInvented() {
    val conflict =
        AgenticReceiptEvidence.complete(
            visual, "Payment Date 01-10-2026\nTransaction Date 02-10-2026")
    assertTrue(conflict.verification.requiresReview)
    assertNull(conflict.verification.transactionDate)
    assertNull(
        AgenticReceiptEvidence.complete(visual, "Completed Time 31-02-2026")
            .verification
            .transactionDate)
  }

  @Test
  fun unlabelledDateCannotOverrideCaptureFallback() {
    assertNull(
        AgenticReceiptEvidence.complete(visual, "2026-10-01\nBalance RM5.72")
            .verification
            .transactionDate)
  }

  @Test
  fun conflictingDateIsReviewableEvenIfModelDateIsOutOfRange() {
    val v =
        AgenticReceiptEvidence.complete(
                visual.copy(transactionDate = LocalDate.parse("2025-10-01")), ocr)
            .verification
    val tx =
        VerifiedTransaction(
            "id",
            listOf("id"),
            v.amount,
            v.currency,
            v.transactionDate,
            "printed_event",
            v.merchant,
            v.paymentStatus,
            v.match,
            v.requiresReview)
    val result =
        AgenticCalculation.calculate(
            listOf(tx),
            AgenticDates.resolve(DateFilter(listOf("last week")), LocalDate.parse("2026-10-10")))
    assertEquals(listOf("id"), result.unresolvedIds)
    assertTrue(result.excludedIds.isEmpty())
  }

  @Test
  fun monetarySynthesisCannotSwapCurrencies() {
    val result =
        CalculationResult(
            mapOf("MYR" to BigDecimal("5.72"), "USD" to BigDecimal("8.00")),
            emptyList(),
            emptyList(),
            emptyList())
    assertTrue(AgenticCalculation.monetaryAnswerIsGrounded("RM5.72 and USD8.00", result))
    assertFalse(AgenticCalculation.monetaryAnswerIsGrounded("RM8.00 and USD5.72", result))
    assertFalse(AgenticCalculation.monetaryAnswerIsGrounded("MYR5.72 and EUR8.00", result))
  }
}
