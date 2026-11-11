package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatRequestWorkflowTest {
  private val today = LocalDate.of(2026, 10, 8)

  private fun interpretation(period: String, basis: String = "event_then_taken") =
      """{"intent":"What was my spending?","date_basis":"$basis","period":$period}"""

  @Test
  fun oneUnambiguousDateCannotBecomeAnEntireMonthOrADifferentDay() {
    listOf("09-29", "09/29", "29-09", "29/09", "September 29", "2026-09-29").forEach { date ->
      val question = "What is the total spending on $date?"
      val correct = """{"type":"day","date":"2026-09-29"}"""
      assertEquals(
          today.withMonth(9).withDayOfMonth(29) to today.withMonth(9).withDayOfMonth(30),
          ChatRequestWorkflow.calendar(
              ChatRequestWorkflow.parsePeriod(correct, question, today), today))
      listOf(
              """{"type":"range","start":"2026-09-01","end":"2026-09-29"}""",
              """{"type":"day","date":"2026-09-26"}""",
              """{"type":"month","month":9}""")
          .forEach { wrong ->
            assertThrows(IllegalArgumentException::class.java) {
              ChatRequestWorkflow.parsePeriod(wrong, question, today)
            }
          }
    }
  }

  @Test
  fun twoGeneratedNumericDatesKeepOnlyTheRequestedDaysAndRemoveTemporalFiller() = runBlocking {
    val generated =
        listOf(
            "What is the total spending for the period 09-29?",
            "What is the total spending for the period 09/26?")
    val engine =
        ChatRequestWorkflow(
            { stage, input, _ ->
              when (stage) {
                ChatWorkflowStage.SPLIT ->
                    JSONObject().put("questions", org.json.JSONArray(generated)).toString()
                ChatWorkflowStage.DATES ->
                    if (input.contains("09-29")) """{"type":"day","date":"2026-09-29"}"""
                    else """{"type":"day","date":"2026-09-26"}"""
                ChatWorkflowStage.REWRITE -> "What is the total spending?"
                else -> error("Unexpected stage")
              }
            },
            today)
    val parts = engine.parts("what is the total spending on 09-29 and 09/26?")
    assertEquals(generated, parts.map { it.question })
    assertEquals(
        listOf("2026-09-29 to 2026-09-29", "2026-09-26 to 2026-09-26"),
        parts.map { it.query.period })
    assertTrue(parts.all { it.query.terms.isEmpty() })
    assertFalse(parts.any { it.query.includes(LocalDate.of(2026, 9, 27)) })
    assertFalse(parts.any { it.query.includes(LocalDate.of(2026, 9, 28)) })
    assertEquals("What is the total spending?", engine.rewrite(parts.first()).intent)
  }

  @Test
  fun combinedRequestUsesGeneratedQuestionsAndKeepsDisjointPeriods() = runBlocking {
    val original = "my spending in last week and first week of september"
    val generated =
        listOf(
            "What was my spending for the last week?",
            "What was my spending for the first week of September?")
    val calls = mutableListOf<Pair<ChatWorkflowStage, String>>()
    val engine =
        ChatRequestWorkflow(
            { stage, input, source ->
              assertNull(source)
              calls += stage to input
              when (stage) {
                ChatWorkflowStage.SPLIT ->
                    JSONObject().put("questions", org.json.JSONArray(generated)).toString()
                ChatWorkflowStage.REWRITE -> "What was my spending?"
                ChatWorkflowStage.DATES ->
                    if (input.contains(generated[0])) """{"type":"rolling_week"}"""
                    else """{"type":"month_week","month":9,"week":1}"""
                else -> error("Unexpected stage")
              }
            },
            today)
    val parts = engine.parts(original)
    assertEquals(original, calls.first().second)
    assertEquals(generated, parts.map { it.question })
    assertEquals(
        listOf("2026-10-01 to 2026-10-08", "2026-09-01 to 2026-09-07"),
        parts.map { it.query.period })
    assertFalse(parts.any { it.query.includes(LocalDate.of(2026, 9, 15)) })
    assertTrue(parts.all { it.query.spending && it.query.terms.isEmpty() })
    assertTrue(
        calls
            .filter { it.first == ChatWorkflowStage.DATES }
            .all { it.second.contains("REFERENCE DATE: 2026-10-08") })
    assertTrue(calls.none { it.first == ChatWorkflowStage.REWRITE })
    assertEquals("What was my spending?", engine.rewrite(parts.first()).intent)
    assertEquals(
        listOf(generated.first()),
        calls.filter { it.first == ChatWorkflowStage.REWRITE }.map { it.second })
  }

  @Test
  fun modelCalendarOperationsCoverDifferentDateMeanings() {
    val cases =
        listOf(
            """{"type":"relative_month","offset":1}""" to "2026-09-01 to 2026-09-30",
            """{"type":"day","date":"2026-10-01"}""" to "2026-10-01 to 2026-10-01",
            """{"type":"month_week","month":9,"week":5}""" to "2026-09-29 to 2026-09-30",
            """{"type":"month","month":9,"year":2025}""" to "2025-09-01 to 2025-09-30",
            """{"type":"range","start":"2026-09-30","end":"2026-10-02"}""" to
                "2026-09-30 to 2026-10-02",
            """{"type":"rolling_days","days":7}""" to "2026-10-02 to 2026-10-08",
            """{"type":"calendar_week","offset":1}""" to "2026-09-28 to 2026-10-04",
            """{"type":"relative_day","offset":1}""" to "2026-10-07 to 2026-10-07",
            """{"type":"all"}""" to "all saved dates")
    cases.forEach { (period, expected) ->
      assertEquals(
          expected,
          ChatRequestWorkflow.parsePart(interpretation(period), "Spending?", today).query.period)
    }
  }

  @Test
  fun aRedundantPreviousWeekOffsetDoesNotChangeTheRollingBoundary() {
    listOf(1, -1).forEach { offset ->
      val period =
          ChatRequestWorkflow.parsePeriod(
              """{"type":"rolling_week","offset":$offset}""",
              "What was my spending last week?",
              today)
      assertEquals(
          today.minusDays(7) to today.plusDays(1), ChatRequestWorkflow.calendar(period, today))
    }
    listOf(0, 2, -2).forEach { offset ->
      assertThrows(IllegalArgumentException::class.java) {
        ChatRequestWorkflow.calendar(
            JSONObject("""{"type":"rolling_week","offset":$offset}"""), today)
      }
    }
  }

  @Test
  fun malformedDateOperationsDoNotSilentlyWidenTheSearch() {
    listOf(
            """{"type":"month_week","month":2,"week":5,"year":2026}""",
            """{"type":"range","start":"2026-10-08","end":"2026-10-01"}""",
            """{"type":"rolling_week","start":"2026-09-01"}""",
            """{"type":"month","month":13}""",
            """{"type":"rolling_days","days":7.5}""")
        .forEach { period ->
          assertThrows(Exception::class.java) {
            ChatRequestWorkflow.parsePart(interpretation(period), "Spending?", today)
          }
        }
  }

  @Test
  fun copiedInstructionsAreNotAcceptedAsTheInterpretedRequest() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatRequestWorkflow.parsePart(
          """{"intent":"request with its entire time filter removed","date_basis":"event_then_taken","period":{"type":"all"}}""",
          "What was my spending last week?",
          today)
    }
  }

  @Test
  fun aModelCannotDropATimeRestrictionByChoosingAll() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatRequestWorkflow.parsePart(
          interpretation("""{"type":"all"}"""), "What was my spending last week?", today)
    }
  }

  @Test
  fun aModelCannotChangeTheSubjectWhileRemovingDates() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatRequestWorkflow.parsePart(
          interpretation("""{"type":"rolling_week"}"""),
          "What was my spending on coffee last week?",
          today)
    }
  }

  @Test
  fun dateBasisIsModelSelectedAndDoesNotConfuseImportWithCapture() {
    val period = """{"type":"day","date":"2026-10-01"}"""
    listOf(
            "event_then_taken" to ChatDateBasis.EVENT_THEN_TAKEN,
            "taken" to ChatDateBasis.TAKEN,
            "added" to ChatDateBasis.ADDED)
        .forEach { (name, expected) ->
          assertEquals(
              expected,
              ChatRequestWorkflow.parsePart(interpretation(period, name), "Spending?", today)
                  .query
                  .dateBasis)
        }
  }

  @Test
  fun invalidInterpretationGetsFeedbackWithoutInsertingExpectedDates() = runBlocking {
    val inputs = mutableListOf<String>()
    ChatRequestWorkflow(
            { stage, input, _ ->
              if (stage == ChatWorkflowStage.SPLIT)
                  """{"questions":["What was my spending last week?"]}"""
              else if (stage == ChatWorkflowStage.REWRITE) "What was my spending?"
              else {
                inputs += input
                if (inputs.size == 1) """{"type":"rolling_week","start":"wrong"}"""
                else """{"type":"rolling_week"}"""
              }
            },
            today)
        .parts("spending last week")
    assertEquals(2, inputs.size)
    assertTrue(inputs.last().contains("VALIDATION ERROR:"))
    assertFalse(inputs.last().contains("2026-10-01"))
  }

  private fun record(value: Int = 1237) =
      ChatExtractedRecord(
          3,
          ChatVerification(ChatDecision.YES, "Successful payment; purchase amount RM8.05"),
          JSONObject(
              """{"value":$value,"unit":"RM","event_date":null,"record_id":null,"evidence":null}"""))

  @Test
  fun aCopiedFollowupPlaceholderDoesNotCountAsAModelGeneratedCheck() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatRequestWorkflow.parseFollowup(
          """{"followup_question":"one concrete question ending in ?"}""")
    }
  }

  private val paid = ChatPrintedPaidAmount(BigDecimal("7.77"), "MYR", "You Paid RM7.77")

  @Test
  fun generatedConflictQuestionIsFedBackUnchangedAndRawReadingIsPreserved() = runBlocking {
    val question = "What was my spending last week?"
    val part =
        ChatRequestWorkflow.parsePart(
            interpretation("""{"type":"rolling_week"}"""), question, today)
    val original = record()
    val calls = mutableListOf<Pair<ChatWorkflowStage, String>>()
    val generated =
        "Which observed amount is actually labeled as paid rather than the purchase subtotal?"
    val corrected =
        ChatRequestWorkflow(
                { stage, input, source ->
                  assertEquals(3L, source)
                  calls += stage to input
                  if (stage == ChatWorkflowStage.REVIEW)
                      JSONObject().put("followup_question", generated).toString()
                  else
                      """{"value":7.77,"unit":"RM","event_date":null,"record_id":null,"evidence":"You Paid RM7.77"}"""
                },
                today)
            .resolve(part, original, "Purchase Amount RM8.05\nYou Paid RM7.77", paid)
    assertEquals(
        listOf(ChatWorkflowStage.REVIEW, ChatWorkflowStage.RESOLVE), calls.map { it.first })
    assertTrue(calls.last().second.startsWith("GENERATED QUESTION:\n$generated\n"))
    assertEquals(1237, original.json.getInt("value"))
    assertEquals(
        0, BigDecimal("7.77").compareTo(corrected.json.get("value").toString().toBigDecimal()))
  }

  @Test
  fun unresolvedAmountConflictIsExcludedInsteadOfCountedOrSilentlyOverwritten() {
    val query = ChatQuery.parse("spending")
    val result =
        ChatWorkflowFacts.amounts(
            query, listOf(record()), emptyMap(), printedPaidAmounts = mapOf(3L to paid))
    assertTrue(result.amounts.isEmpty())
    assertEquals(1, result.excluded)
    assertTrue(result.exclusions.single().reason.contains("paid label"))
  }

  @Test
  fun emptyPeriodHasUnknownTotalAndDoesNotEraseOtherPeriod() {
    val query =
        ChatRequestWorkflow.parsePart(
                interpretation("""{"type":"month_week","month":9,"week":1}"""), "Spending?", today)
            .query
    val empty =
        ChatPartAnswer("question", query, ChatAmountResult(emptyList(), 0, 0), emptyList(), 0)
    assertEquals("no_confirmed_evidence", empty.status)
    assertTrue(empty.summary.contains("2026-09-01 to 2026-09-07"))
    assertTrue(empty.summary.contains("unknown"))
    assertFalse(empty.summary.contains("MYR 0"))
  }
}
