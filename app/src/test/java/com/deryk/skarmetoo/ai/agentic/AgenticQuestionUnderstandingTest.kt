package com.deryk.skarmetoo.ai.agentic

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticQuestionUnderstandingTest {
  private fun response(
      intent: String = "calculate_spending",
      operation: String = "sum",
      subject: String = "",
      clarification: String = ""
  ) =
      JSONObject()
          .put("intent", intent)
          .put("operation", operation)
          .put("subject_quote", subject)
          .put("search_keywords", JSONArray(listOf("payment", "receipt")))
          .put(
              "required_evidence",
              JSONArray(listOf("completed outgoing payment", "final paid amount", "currency")))
          .put("optional_evidence", JSONArray())
          .put("clarification_question", clarification)

  @Test
  fun spendingIntentIsIndependentOfItsTiming() {
    val question = "what is my spending past day and past week?"
    val plan = AgenticQuestionUnderstanding.parse(response().toString(), question)
    assertEquals(Intent.calculate_spending, plan.intent)
    assertEquals(Operation.sum, plan.operation)
    assertNull(plan.dateFilter)
    assertNull(plan.category)
    assertEquals(listOf("payment", "receipt"), plan.searchKeywords)
  }

  @Test
  fun timingCannotBecomeASubjectAndTriggerImpossibleVerification() {
    val question = "what is my spending past day and last week"
    assertTrue(
        runCatching {
              AgenticQuestionUnderstanding.parse(
                  response(subject = "spending past day and last week").toString(), question)
            }
            .isFailure)
    assertNull(AgenticQuestionUnderstanding.parse(response().toString(), question).category)
    assertTrue(
        runCatching {
              AgenticQuestionUnderstanding.parse(
                  response(subject = "coffee yesterday").toString(), "coffee yesterday")
            }
            .isFailure)
  }

  @Test
  fun explicitSubjectIsKeptAndInventedSubjectRejected() {
    assertEquals(
        "coffee",
        AgenticQuestionUnderstanding.parse(
                response(subject = "coffee").toString(), "coffee spending yesterday")
            .category)
    assertTrue(
        runCatching {
              AgenticQuestionUnderstanding.parse(
                  response(subject = "coffee").toString(), "spending yesterday")
            }
            .isFailure)
  }

  @Test
  fun countRetrievalAndClarificationRetainTheirOperations() {
    assertEquals(
        Operation.count,
        AgenticQuestionUnderstanding.parse(
                response("count_transactions", "count").toString(), "How many payments yesterday?")
            .operation)
    assertEquals(
        Operation.retrieve,
        AgenticQuestionUnderstanding.parse(
                response("find_transaction", "retrieve").toString(), "Show payments last month")
            .operation)
    assertTrue(
        runCatching {
              AgenticQuestionUnderstanding.parse(
                  response(operation = "count").toString(), "spending yesterday")
            }
            .isFailure)
    val unknown =
        AgenticQuestionUnderstanding.parse(
            response("unknown", "request_clarification", clarification = "What should I find?")
                .toString(),
            "help")
    assertEquals(Operation.request_clarification, unknown.nextAction)
  }
}
