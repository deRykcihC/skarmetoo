package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticContractsTest {
  private val id = "image-1"

  private fun valid() =
      AgenticContracts.verificationJson(
          ImageVerification(
              id,
              Match.YES,
              LocalDate.parse("2026-10-01"),
              BigDecimal("5.72"),
              "MYR",
              "Deen Maju",
              "food",
              PaymentStatus.completed,
              listOf("food"),
              "You Paid",
              false))

  private fun rejected(block: () -> Unit) {
    assertTrue(runCatching(block).isFailure)
  }

  @Test
  fun dispatcherBindsIdentityAndStillRequiresReviewDecision() {
    val wire = valid().also { it.remove("image_id") }
    assertEquals(id, AgenticContracts.verificationResponse(wire.toString(), id).imageId)
    rejected { AgenticContracts.verificationResponse(wire.put("image_id", "wrong").toString(), id) }
    wire.remove("image_id")
    wire.remove("requires_review")
    rejected { AgenticContracts.verificationResponse(wire.toString(), id) }
  }

  @Test
  fun onlyMatchingCurrencyPrefixesAreNormalized() {
    val wire = valid().also { it.remove("image_id") }.put("amount", "RM5.72")
    assertEquals(
        BigDecimal("5.72"), AgenticContracts.verificationResponse(wire.toString(), id).amount)
    rejected { AgenticContracts.verificationResponse(wire.put("currency", "USD").toString(), id) }
    rejected {
      AgenticContracts.verificationResponse(
          wire.put("amount", "around RM5.72").put("currency", "MYR").toString(), id)
    }
    rejected { AgenticContracts.verificationResponse(wire.put("amount", "RM-5.72").toString(), id) }
  }

  @Test
  fun dateTransportRepairPreservesLiteralAndRejectsInvalidDates() {
    val wire =
        valid().also { it.remove("image_id") }.toString().replace("\"2026-10-01\"", "2026-10-01")
    rejected { AgenticContracts.verificationResponse(wire, id) }
    val fixed = AgenticContracts.quoteVerificationDate(wire)
    assertEquals(
        LocalDate.parse("2026-10-01"),
        AgenticContracts.verificationResponse(fixed, id).transactionDate)
    assertEquals(fixed, AgenticContracts.quoteVerificationDate(fixed))
    rejected {
      AgenticContracts.verificationResponse(
          AgenticContracts.quoteVerificationDate(wire.replace("2026-10-01", "2026-99-01")), id)
    }
  }

  @Test
  fun identicalMalformedReviewRepetitionIsLosslessButConflictsAndLegalDuplicatesAreRejected() {
    val wire = valid().also { it.remove("image_id") }.toString()
    val malformed = wire.dropLast(1) + ",requires_review\":false}"
    assertEquals(wire, AgenticContracts.removeRepeatedReviewTransport(malformed))
    assertFalse(
        AgenticContracts.verificationResponse(
                AgenticContracts.removeRepeatedReviewTransport(malformed), id)
            .requiresReview)
    val conflict = wire.dropLast(1) + ",requires_review\":true}"
    assertEquals(conflict, AgenticContracts.removeRepeatedReviewTransport(conflict))
    rejected { AgenticContracts.verificationResponse(conflict, id) }
    val duplicate = wire.dropLast(1) + ",\"requires_review\":false}"
    assertEquals(duplicate, AgenticContracts.removeRepeatedReviewTransport(duplicate))
    rejected { AgenticContracts.verificationResponse(duplicate, id) }
  }

  @Test
  fun singletonCategoryKeepsItsMeaningButMultipleCategoriesAreNotGuessed() {
    val wire =
        valid().also { it.remove("image_id") }.put("category", org.json.JSONArray(listOf("food")))
    assertEquals("food", AgenticContracts.verificationResponse(wire.toString(), id).category)
    rejected {
      AgenticContracts.verificationResponse(
          wire.put("category", org.json.JSONArray(listOf("food", "fuel"))).toString(), id)
    }
  }

  @Test
  fun validVerificationAndNulls() {
    assertEquals(BigDecimal("5.72"), AgenticContracts.verification(valid().toString(), id).amount)
    assertNull(
        AgenticContracts.verification(valid().put("amount", JSONObject.NULL).toString(), id).amount)
  }

  @Test
  fun rejectsMissingField() = rejected {
    AgenticContracts.verification(valid().also { it.remove("merchant") }.toString(), id)
  }

  @Test
  fun rejectsUnknownField() = rejected {
    AgenticContracts.verification(valid().put("override", true).toString(), id)
  }

  @Test
  fun rejectsEnum() = rejected {
    AgenticContracts.verification(valid().put("match", "probably").toString(), id)
  }

  @Test
  fun rejectsVersion() = rejected {
    AgenticContracts.verification(valid().put("schema_version", 2).toString(), id)
  }

  @Test
  fun rejectsNumericAmount() = rejected {
    AgenticContracts.verification(valid().put("amount", 5.72).toString(), id)
  }

  @Test
  fun rejectsWrongImage() = rejected { AgenticContracts.verification(valid().toString(), "other") }

  @Test fun rejectsMalformedJson() = rejected { AgenticContracts.verification("{broken", id) }

  @Test
  fun rejectsTrailingJson() = rejected {
    AgenticContracts.verification(valid().toString() + " {}", id)
  }

  @Test
  fun rejectsBadDate() = rejected {
    AgenticContracts.verification(valid().put("transaction_date", "2026-02-30").toString(), id)
  }

  @Test
  fun rejectsCurrency() = rejected {
    AgenticContracts.verification(valid().put("currency", "RM").toString(), id)
  }

  @Test
  fun nativeSchemasHaveClosedObjects() {
    assertFalse(AgenticContracts.planSchema.getBoolean("additionalProperties"))
    assertFalse(AgenticContracts.verificationSchema.getBoolean("additionalProperties"))
  }

  private fun queryPlan() =
      JSONObject()
          .put("schema_version", 1)
          .put("intent", "calculate_spending")
          .put("operation", "sum")
          .put("category", JSONObject.NULL)
          .put(
              "date_filter",
              JSONObject().put("expressions", org.json.JSONArray(listOf("last week"))))
          .put("search_keywords", org.json.JSONArray(listOf("payment", "receipt")))
          .put(
              "required_evidence",
              org.json.JSONArray(listOf("completed payment", "amount", "currency")))
          .put("optional_evidence", org.json.JSONArray())
          .put("verification_question", "Does this image show a completed outgoing payment?")
          .put("next_action", "retrieve")

  @Test
  fun plannerKeepsCalendarOutOfVisionQuestion() {
    assertEquals(Operation.sum, AgenticContracts.plan(queryPlan().toString()).operation)
    rejected {
      AgenticContracts.plan(
          queryPlan().put("verification_question", "Is this payment from last week?").toString())
    }
    rejected {
      AgenticContracts.plan(
          queryPlan().put("search_keywords", org.json.JSONArray(listOf("last week"))).toString())
    }
  }

  @Test
  fun plannerRejectsContradictoryOperation() = rejected {
    AgenticContracts.plan(queryPlan().put("operation", "answer").toString())
  }

  @Test
  fun toolsValidateAndStopRepeatedRequests() = runBlocking {
    val tools = AgenticTools(Clock.fixed(Instant.parse("2026-10-09T17:00:00Z"), ZoneOffset.UTC))
    val args = JSONObject().put("timezone", "Asia/Singapore")
    assertEquals("2026-10-10", tools.dispatch("get_current_datetime", args).getString("today"))
    tools.dispatch("get_current_datetime", args)
    assertTrue(runCatching { tools.dispatch("get_current_datetime", args) }.isFailure)
    assertTrue(runCatching { tools.dispatch("read_file", args) }.isFailure)
    assertTrue(
        runCatching {
              tools.dispatch(
                  "get_current_datetime", JSONObject().put("timezone", "UTC").put("file", "x"))
            }
            .isFailure)
  }

  private fun transaction(
      key: String,
      amount: String? = "5.72",
      currency: String? = "MYR",
      status: PaymentStatus = PaymentStatus.completed,
      match: Match = Match.YES,
      date: LocalDate? = LocalDate.parse("2026-10-01")
  ) =
      VerifiedTransaction(
          key,
          listOf(key),
          amount?.let(::BigDecimal),
          currency,
          date,
          "printed",
          "Deen Maju",
          status,
          match,
          false)

  @Test
  fun aggregateToolAcceptsEightHundredIds() = runBlocking {
    val tools = AgenticTools()
    tools.register("get_verified_results", AgenticTools.schema("result_ids" to "array")) { args ->
      JSONObject().put("count", args.getJSONArray("result_ids").length())
    }
    val result =
        tools.dispatch(
            "get_verified_results",
            JSONObject().put("result_ids", org.json.JSONArray((1..800).map { "image-$it" })))
    assertEquals(800, result.getInt("count"))
  }

  @Test
  fun decimalTotalsAreExact() {
    val r = AgenticCalculation.calculate(listOf(transaction("a", "0.10"), transaction("b", "0.20")))
    assertEquals(BigDecimal("0.30"), r.totals["MYR"])
  }

  @Test
  fun multipleCurrenciesStaySeparate() {
    val r = AgenticCalculation.calculate(listOf(transaction("a"), transaction("b", "10.00", "USD")))
    assertEquals(2, r.totals.size)
  }

  @Test
  fun missingAmountIsUnresolved() {
    val r = AgenticCalculation.calculate(listOf(transaction("a", null)))
    assertTrue(r.totals.isEmpty())
    assertEquals(listOf("a"), r.unresolvedIds)
  }

  @Test
  fun refundedPendingFailedExcluded() {
    val r =
        AgenticCalculation.calculate(
            listOf(
                transaction("a", status = PaymentStatus.refunded),
                transaction("b", status = PaymentStatus.pending),
                transaction("c", status = PaymentStatus.failed)))
    assertEquals(3, r.excludedIds.size)
  }

  @Test
  fun irrelevantAndNoRelevant() {
    assertTrue(
        AgenticCalculation.calculate(listOf(transaction("a", match = Match.NO))).totals.isEmpty())
    assertTrue(AgenticCalculation.calculate(emptyList()).transactions.isEmpty())
  }

  @Test
  fun duplicateTransactionMerged() {
    val a = transaction("a")
    val b = a.copy(imageIds = listOf("b"))
    val r = AgenticCalculation.calculate(listOf(a, b))
    assertEquals(1, r.transactions.size)
    assertEquals(2, r.transactions.single().imageIds.size)
  }

  @Test
  fun calendarLastWeek() {
    val w =
        AgenticDates.resolve(DateFilter(listOf("last week")), LocalDate.parse("2026-10-10"))
            .single()
    assertEquals(LocalDate.parse("2026-09-28"), w.start)
    assertEquals(LocalDate.parse("2026-10-05"), w.endExclusive)
  }

  @Test
  fun printedDateWinsOverCapturedDate() {
    val w = AgenticDates.resolve(DateFilter(listOf("2026-10-01")), LocalDate.parse("2026-10-10"))
    assertEquals(1, AgenticCalculation.calculate(listOf(transaction("a")), w).transactions.size)
  }

  @Test
  fun unknownDateNotDiscarded() {
    val w = AgenticDates.resolve(DateFilter(listOf("yesterday")), LocalDate.parse("2026-10-10"))
    assertEquals(
        listOf("a"),
        AgenticCalculation.calculate(listOf(transaction("a", date = null)), w).unresolvedIds)
  }

  @Test
  fun multipleDateWindowsUseUnion() {
    val w =
        AgenticDates.resolve(
            DateFilter(listOf("2026-09-26", "2026-09-29")), LocalDate.parse("2026-10-10"))
    val r =
        AgenticCalculation.calculate(
            listOf(
                transaction("a", date = LocalDate.parse("2026-09-26")),
                transaction("b", date = LocalDate.parse("2026-09-29")),
                transaction("c")),
            w)
    assertEquals(2, r.transactions.size)
  }

  @Test
  fun malformedDateNeedsClarification() = rejected {
    AgenticDates.resolve(DateFilter(listOf("2026-02-30")), LocalDate.parse("2026-10-10"))
  }
}
