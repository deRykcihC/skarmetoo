package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticResponseFormattingTest {
  @Test
  fun excessNumericPrecisionNeverRoundsIntoAnAcceptedAmount() {
    val raw = facts().put("currency", "RM").toString().replace("\"5.72\"", "1.0000000000000001")
    val value = AgenticPaymentFacts.observation(raw, "x")
    assertEquals(listOf("amount"), value.unresolvedFields)
    assertNull(value.verification.amount)
    assertEquals(Match.MAYBE, value.verification.match)
    assertTrue(
        runCatching { AgenticPaymentFacts.validateRepair(raw, facts().put("amount", "1.0")) }
            .isFailure)
  }

  @Test
  fun absentClassifierFieldsCanOnlyBeRestoredAsUnknown() {
    val original = facts().also { it.remove("direction") }.toString()
    val repaired = facts().put("direction", "unknown")
    AgenticPaymentFacts.validateRepair(original, repaired)
    assertEquals(
        Match.MAYBE, AgenticPaymentFacts.observation(repaired.toString(), "x").verification.match)
    assertTrue(runCatching { AgenticPaymentFacts.validateRepair(original, facts()) }.isFailure)
  }

  @Test
  fun completePrintedDateVariantsRetainDayMonthAndYear() {
    for (date in listOf("01/10/2026", "2026/10/01", "2026.10.1")) {
      val raw = facts().toString().replace("\"2026-10-01\"", date)
      assertEquals(
          LocalDate.parse("2026-10-01"),
          AgenticPaymentFacts.observation(raw, "x").verification.transactionDate)
    }
    val missingYear = facts().toString().replace("\"2026-10-01\"", "01/10")
    assertTrue(runCatching { AgenticPaymentFacts.observation(missingYear, "x") }.isFailure)
  }

  private fun facts() =
      JSONObject(
          """{"image_kind":"payment_receipt","direction":"outgoing","payment_status":"completed","transaction_date":"2026-10-01","amount":"5.72","currency":"MYR","merchant":"Deen Maju","evidence":"You Paid RM5.72"}""")

  @Test
  fun typedRepresentationsNormalizeWithoutChangingPaymentFacts() {
    val raw =
        facts()
            .put("image_kind", "PAYMENT_RECEIPT")
            .put("direction", "OUTGOING")
            .put("payment_status", "COMPLETED")
            .put("transaction_date", "01/10/2026")
            .put("currency", "RM")
            .put("merchant", "null")
            .put("amount", 5.72)
            .toString()
    val value = AgenticPaymentFacts.observation(raw, "x")
    assertEquals(Match.YES, value.verification.match)
    assertEquals(BigDecimal("5.72"), value.verification.amount)
    assertEquals("MYR", value.verification.currency)
    assertNull(value.verification.merchant)
    assertEquals(LocalDate.parse("2026-10-01"), value.verification.transactionDate)
    assertTrue(value.unresolvedFields.isEmpty())
    assertTrue(value.changes.size >= 5)
  }

  @Test
  fun numericAmountRetainsOriginalDecimalDigitsDespiteJsonDoubleParsing() {
    val raw = facts().toString().replace("\"5.72\"", "123456789012.9999")
    val value = AgenticPaymentFacts.observation(raw, "x")
    assertEquals(BigDecimal("123456789012.9999"), value.verification.amount)
    val fake =
        facts()
            .put("evidence", "Fake field: \"amount\": 4.56")
            .toString()
            .replace("\"5.72\"", "123456789012.9999")
    assertEquals(
        BigDecimal("123456789012.9999"),
        AgenticPaymentFacts.observation(fake, "x").verification.amount)
  }

  @Test
  fun sentenceBraceCleanupDoesNotRewriteMerchantOrEvidence() {
    val schema =
        AgenticContracts.objectSchema(
            mapOf(
                "normalized_question" to JSONObject().put("type", "string"),
                "merchant" to JSONObject().put("type", "string")))
    val value =
        AgenticContracts.parse(
            AgenticResponseFormatting.normalize(
                    """{"normalized_question":"}Spending on 09-29","merchant":"}Shop"}""", schema)
                .text)
    assertEquals("Spending on 09-29", value.getString("normalized_question"))
    assertEquals("}Shop", value.getString("merchant"))
  }

  @Test
  fun duplicateKeysTrailingObjectsAndProseAreStillRejected() {
    for (raw in
        listOf(
            facts().toString().dropLast(1) + ",\"amount\":\"5.72\"}",
            facts().toString() + " {}",
            "Here is JSON: " + facts())) assertTrue(
        runCatching { AgenticResponseFormatting.normalize(raw, AgenticPaymentFacts.schema) }
            .isFailure)
  }

  @Test
  fun multipleAmountsBecomeUnresolvedWithoutSelectingOrAddingAnyNumber() {
    val value =
        AgenticPaymentFacts.observation(facts().put("amount", "RM16.90,RM7.50").toString(), "x")
    assertEquals(listOf("amount"), value.unresolvedFields)
    assertEquals(Match.MAYBE, value.verification.match)
    assertNull(value.verification.amount)
    assertTrue(value.verification.requiresReview)
    assertEquals(PaymentStatus.completed, value.verification.paymentStatus)
  }

  @Test
  fun missingYearIsNotBorrowedFromClockOrScreenshotDate() {
    val value =
        AgenticPaymentFacts.observation(facts().put("transaction_date", "Feb 19").toString(), "x")
    assertNull(value.verification.transactionDate)
    assertEquals(listOf("transaction_date"), value.unresolvedFields)
    assertEquals(Match.MAYBE, value.verification.match)
    assertEquals(BigDecimal("5.72"), value.verification.amount)
  }

  @Test
  fun negativeRefundIsNeverConvertedIntoAnOutgoingSpendingAmount() {
    val raw = facts().put("amount", "-RM5.72").put("payment_status", "refunded").toString()
    val v = AgenticPaymentFacts.observation(raw, "x").verification
    assertNotEquals(Match.YES, v.match)
    assertEquals(PaymentStatus.refunded, v.paymentStatus)
    assertNull(v.amount)
    assertEquals(
        BigDecimal("5.72"),
        AgenticPaymentFacts.observation(facts().put("amount", "-RM5.72").toString(), "x")
            .verification
            .amount)
  }

  @Test
  fun conflictingCurrencyPrefixCannotBeNormalizedIntoAValidPayment() {
    assertTrue(
        runCatching {
              AgenticPaymentFacts.observation(
                  facts().put("amount", "RM5.72").put("currency", "USD").toString(), "x")
            }
            .isFailure)
    val v = AgenticPaymentFacts.observation(facts().put("currency", "T").toString(), "x")
    assertNull(v.verification.currency)
    assertEquals(listOf("currency"), v.unresolvedFields)
    assertEquals(Match.MAYBE, v.verification.match)
  }

  @Test
  fun exactJsonFenceAndUnquotedIsoLiteralCanBeRepairedLocally() {
    val raw = "```json\n" + facts().toString().replace("\"2026-10-01\"", "2026-10-01") + "\n```"
    val value = AgenticPaymentFacts.observation(raw, "x")
    assertEquals(LocalDate.parse("2026-10-01"), value.verification.transactionDate)
    assertEquals(2, value.changes.size)
  }

  @Test
  fun textRepairCannotChangeValuesOrResolveDuplicateProperties() {
    val original = facts().toString().dropLast(1)
    AgenticPaymentFacts.validateRepair(original, facts())
    assertTrue(
        runCatching { AgenticPaymentFacts.validateRepair(original, facts().put("amount", "7.72")) }
            .isFailure)
    assertTrue(
        runCatching {
              AgenticPaymentFacts.validateRepair(
                  facts().toString().dropLast(1) + ",\"evidence\":\"Different\"}", facts())
            }
            .isFailure)
    val unknown = facts().put("amount", 5.72).put("currency", "RM")
    AgenticPaymentFacts.validateRepair(unknown.toString(), facts())
  }

  @Test
  fun impossibleDatesRemainUncertainInsteadOfRollingIntoAnotherMonth() {
    val value =
        AgenticPaymentFacts.observation(
            facts().put("transaction_date", "31-02-2026").toString(), "x")
    assertEquals(listOf("transaction_date"), value.unresolvedFields)
    assertNull(value.verification.transactionDate)
    assertTrue(value.verification.requiresReview)
  }

  @Test
  fun unknownEnumsAndMultipleScalarChoicesAreNotGuessed() {
    assertTrue(
        runCatching {
              AgenticPaymentFacts.observation(
                  facts().put("payment_status", "complete").toString(), "x")
            }
            .isFailure)
    assertTrue(
        runCatching {
              AgenticPaymentFacts.observation(
                  facts().put("amount", JSONArray(listOf("5.72", "7.72"))).toString(), "x")
            }
            .isFailure)
  }
}
