package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.data.ScreenshotEntry
import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatWorkflowFactsTest {
  private val query =
      ChatQuery.parse("How much did I spend last week?", LocalDate.parse("2026-10-04"))

  private fun record(
      id: Long = 1,
      amount: String = "5.72",
      date: String = "2026-10-01",
      decision: ChatDecision = ChatDecision.YES,
      extra: String = ""
  ) =
      ChatExtractedRecord(
          id,
          ChatVerification(decision, "You Paid RM$amount"),
          JSONObject(
              "{\"amount\":$amount,\"total_spent\":null,\"currency\":\"RM\",\"transaction_date\":\"$date\"$extra}"))

  @Test
  fun originalQuestionKeepsWeekAndRewrittenPronounsDoNotBecomeSearchTerms() {
    val original =
        ChatQuery.parse("how much did I spend past last week?", LocalDate.parse("2026-10-04"))
    assertEquals(query.start, original.start)
    assertEquals(query.endExclusive, original.endExclusive)
    assertTrue(original.terms.isEmpty())
    assertTrue(ChatQuery.parse("how much did you spend?").terms.isEmpty())
  }

  @Test
  fun screenshotDateCannotMakeOutOfPeriodPaymentCount() {
    val result = ChatWorkflowFacts.amounts(query, listOf(record(date = "2026-09-24")), emptyMap())
    assertTrue(result.totals.isEmpty())
    assertEquals(1, result.excluded)
  }

  @Test
  fun missingValuesAndUnreadableDatesHaveSpecificExclusionReasons() {
    val missingValue =
        record(1).copy(json = JSONObject("""{"amount":null,"event_date":"2026-10-01"}"""))
    val missingDate =
        record(2).copy(json = JSONObject("""{"amount":5.72,"currency":"RM","event_date":null}"""))
    val ambiguousDate = record(3, date = "01-10-2026")
    val result =
        ChatWorkflowFacts.amounts(
            query, listOf(missingValue, missingDate, ambiguousDate), emptyMap())
    assertTrue(result.totals.isEmpty())
    assertEquals(3, result.excluded)
    assertEquals(listOf(1L, 2L, 3L), result.exclusions.map { it.sourceId })
    assertEquals("No readable amount was extracted.", result.exclusions[0].reason)
    assertEquals(
        "No event date was extracted and no image taken date is available.",
        result.exclusions[1].reason)
    assertTrue(result.exclusions[2].reason.contains("01-10-2026"))
  }

  @Test
  fun ambiguousDateIsNotSilentlyInterpretedAndCurrencyRmIsNormalized() {
    assertNull(ChatWorkflowFacts.parseDate("01-10-2026"))
    assertEquals(LocalDate.parse("2026-10-13"), ChatWorkflowFacts.parseDate("13-10-2026"))
    assertEquals("MYR", ChatWorkflowFacts.currency("RM"))
    assertNull(ChatWorkflowFacts.currency("$"))
    assertTrue(
        ChatWorkflowFacts.amounts(query, listOf(record(date = "01-10-2026")), emptyMap())
            .amounts
            .isEmpty())
  }

  @Test
  fun selectedNumericDateConventionResolvesTest2WithoutChangingItsRawDate() {
    val image = record(date = "01-10-2026 12:52")
    assertEquals(
        LocalDate.parse("2026-10-01"),
        ChatWorkflowFacts.parseDate("01-10-2026 12:52", ChatReceiptDateOrder.DAY_FIRST))
    assertEquals(
        LocalDate.parse("2026-01-10"),
        ChatWorkflowFacts.parseDate("01-10-2026 12:52", ChatReceiptDateOrder.MONTH_FIRST))
    assertNull(ChatWorkflowFacts.parseDate("30-09-2026", ChatReceiptDateOrder.MONTH_FIRST))
    assertEquals(
        BigDecimal("5.72"),
        ChatWorkflowFacts.amounts(query, listOf(image), emptyMap(), ChatReceiptDateOrder.DAY_FIRST)
            .totals["MYR"])
    assertTrue(
        ChatWorkflowFacts.amounts(
                query, listOf(image), emptyMap(), ChatReceiptDateOrder.MONTH_FIRST)
            .totals
            .isEmpty())
    assertEquals("01-10-2026 12:52", image.json.getString("transaction_date"))
  }

  @Test
  fun maybeAndExplicitUnpaidRecordsNeverEnterTotals() {
    val result =
        ChatWorkflowFacts.amounts(
            query,
            listOf(
                record(decision = ChatDecision.MAYBE),
                record(2, extra = ",\"payment_status\":\"unpaid\"")),
            emptyMap())
    assertEquals(2, result.excluded)
    assertTrue(result.totals.isEmpty())
  }

  @Test
  fun exactDecimalsAndDifferentCurrenciesStaySeparate() {
    val other =
        record(3)
            .copy(
                json =
                    JSONObject("{\"amount\":12.90,\"currency\":\"SGD\",\"date\":\"2026-10-01\"}"))
    val result =
        ChatWorkflowFacts.amounts(
            query, listOf(record(1, "0.10"), record(2, "0.20"), other), emptyMap())
    assertEquals(BigDecimal("0.30"), result.totals["MYR"])
    assertEquals(BigDecimal("12.90"), result.totals["SGD"])
  }

  @Test
  fun alternateModelSelectedValueUnitAndDateFieldsCanBeCounted() {
    val generated =
        record()
            .copy(
                json =
                    JSONObject(
                        "{\"total_expenditure\":5.72,\"unit\":\"RM\",\"event_date\":\"2026-10-01\"}"))
    assertEquals(
        BigDecimal("5.72"),
        ChatWorkflowFacts.amounts(query, listOf(generated), emptyMap()).totals["MYR"])
    val alternate =
        generated.copy(
            json = JSONObject("{\"total_amount\":\"RM5.72\",\"event_date\":\"2026-10-01\"}"))
    assertEquals(
        BigDecimal("5.72"),
        ChatWorkflowFacts.amounts(query, listOf(alternate), emptyMap()).totals["MYR"])
    alternate.json.put("amount", 6.35)
    assertTrue(ChatWorkflowFacts.amounts(query, listOf(alternate), emptyMap()).amounts.isEmpty())
    generated.json.put("currency", "USD")
    assertTrue(ChatWorkflowFacts.amounts(query, listOf(generated), emptyMap()).amounts.isEmpty())
  }

  @Test
  fun currencyMustBeInAnExtractedValueOrQuoteForTheSameAmount() {
    val sample = record().copy(json = JSONObject("{\"total_spent\":5.72}"))
    val undated = ChatQuery.parse("How much did I spend?")
    assertEquals(
        BigDecimal("5.72"),
        ChatWorkflowFacts.amounts(undated, listOf(sample), emptyMap()).totals["MYR"])
    val embedded =
        sample.copy(
            json = JSONObject("{\"total_expenditure\":\"RM5.72\"}"),
            verification = ChatVerification(ChatDecision.YES, "Completed payment"))
    assertEquals(
        BigDecimal("5.72"),
        ChatWorkflowFacts.amounts(undated, listOf(embedded), emptyMap()).totals["MYR"])
    val missing = sample.copy(verification = ChatVerification(ChatDecision.YES, "Purchase RM6.35"))
    assertTrue(ChatWorkflowFacts.amounts(undated, listOf(missing), emptyMap()).amounts.isEmpty())
    val conflict =
        sample.copy(verification = ChatVerification(ChatDecision.YES, "You Paid RM5.72 or USD5.72"))
    assertTrue(ChatWorkflowFacts.amounts(undated, listOf(conflict), emptyMap()).amounts.isEmpty())
    assertTrue(ChatWorkflowFacts.amounts(query, listOf(sample), emptyMap()).amounts.isEmpty())
  }

  @Test
  fun repeatedImageIsRemovedButEqualAmountsDoNotMergeSeparatePurchases() {
    val records = listOf(record(1), record(2), record(3))
    val result =
        ChatWorkflowFacts.amounts(
            query, records, mapOf(1L to "same", 2L to "same", 3L to "different"))
    assertEquals(2, result.amounts.size)
    assertEquals(1, result.duplicates)
  }

  @Test
  fun conflictingAmountFieldsAreExcludedRatherThanChoosingSubtotalOrTotal() {
    val result =
        ChatWorkflowFacts.amounts(query, listOf(record(extra = ",\"total\":6.35")), emptyMap())
    assertEquals(1, result.excluded)
    assertTrue(result.totals.isEmpty())
  }

  @Test
  fun cachedResultsAreScopedToExactPromptModelAndImageContext() {
    val entry =
        ScreenshotEntry(
            id = 1, imageUri = "image/1", imageHash = "image-content", summary = "Receipt")
    fun key(prompt: String = "prompt", model: String = "3B", image: ScreenshotEntry = entry) =
        ChatWorkflowCacheKey.build(model, ChatWorkflowStage.VERIFY, prompt, image)
    assertNotEquals(key(), key("different question criteria"))
    assertNotEquals(key(), key(model = "450M"))
    assertNotEquals(key(), key(image = entry.copy(imageHash = "changed")))
    assertNotEquals(key(), key(image = entry.copy(tags = "updated tag")))
  }
}
