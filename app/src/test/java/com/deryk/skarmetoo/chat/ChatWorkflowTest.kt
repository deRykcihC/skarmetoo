package com.deryk.skarmetoo.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatWorkflowTest {
  @Test
  fun differentShapeAndDateErrorsCanBothBeCorrectedWithoutAddingImageCalls() = runBlocking {
    val userQuestion = "How much did I spend on 1 October 2026?"
    val requests = mutableListOf<String>()
    var imageCalls = 0
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          if (stage == ChatWorkflowStage.PLAN) {
            requests += prompt
            when (requests.size) {
              1 ->
                  """{"verification_question":"Is there a record for 1 October 2026?","event_date":null}"""
              2 ->
                  writtenPlan()
                      .put("verification_question", "Is there a record for last month?")
                      .toString()
              else -> response(stage)
            }
          } else {
            if (stage == ChatWorkflowStage.VERIFY || stage == ChatWorkflowStage.EXTRACT)
                imageCalls++
            response(stage)
          }
        })
    val plan = engine.plan(userQuestion)
    assertEquals(3, requests.size)
    assertEquals(0, imageCalls)
    assertEquals(rewritten, requests.first())
    assertTrue(requests[1].contains("response_format"))
    assertTrue(requests[2].contains("Remove all dates"))
    engine.inspect(plan, listOf(5))
    assertEquals(2, imageCalls)
  }

  @Test
  fun rangeRestrictionsAreRegeneratedByTheModelBeforeReadingAnyImage() = runBlocking {
    val userQuestion = "How much did I spend from 30 September to 2 October 2026?"
    val requests = mutableListOf<String>()
    var imageCalls = 0
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          if (stage == ChatWorkflowStage.PLAN) {
            requests += prompt
            if (requests.size == 1)
                writtenPlan()
                    .put("verification_question", "Does this image show spending last month?")
                    .toString()
            else response(stage)
          } else {
            if (stage == ChatWorkflowStage.VERIFY || stage == ChatWorkflowStage.EXTRACT)
                imageCalls++
            response(stage)
          }
        })
    val plan = engine.plan(userQuestion)
    assertEquals(rewritten, requests.first())
    assertEquals(2, requests.size)
    assertEquals(0, imageCalls)
    assertTrue(requests.last().contains("Remove all dates"))
    assertFalse(requests.last().contains("5.72"))
    assertEquals(readerVerification, plan.verificationPrompt)
    assertEquals(readerPrompt, plan.extraction.prompt)
  }

  @Test
  fun relativeMonthsAndWeeksCannotChangeTheImageReaderTask() {
    for (phrase in listOf("last month", "last week", "the fifth week of September 2026")) {
      val userQuestion = "How much did I spend " + phrase + "?"
      assertThrows(IllegalArgumentException::class.java) {
        ChatWorkflow.parsePlan(
            writtenPlan()
                .put(
                    "extraction_question",
                    "What is the actual amount spent " + phrase + ", its unit and date?")
                .toString(),
            userQuestion)
      }
    }
  }

  private val question = "how much did I spend past last week?"
  private val rewritten = "how much did you spend?"
  private val generated =
      "VERIFY: Examine the attached image. Goal: Determine the total amount of money spent."
  private val writtenVerification =
      org.json
          .JSONObject()
          .put("next_prompt", generated)
          .put("yes_if", "Visible confirmation establishes a completed outgoing payment.")
          .put("no_if", "An unpaid request, offer or unrelated image is not a completed payment.")
          .put("maybe_if", "Payment completion or direction is unreadable or unclear.")
  private val readerVerification =
      "Does the image show a completed outgoing payment with a successful status?"
  private val schema =
      "EXTRACT: Read one completed outgoing payment and extract the actual amount paid, its unit and event date. Use visible facts only, null if unknown."
  private val format =
      org.json.JSONObject(
          "{\"value\":null,\"unit\":null,\"event_date\":null,\"record_id\":null,\"evidence\":null}")
  private val extracted =
      "{\"value\":5.72,\"unit\":\"RM\",\"event_date\":\"01-10-2026\",\"record_id\":null,\"evidence\":\"Successful You Paid RM5.72\"}"
  private val definitions =
      org.json
          .JSONObject()
          .put("value", "Read the paid amount rather than the subtotal.")
          .put("unit", "Read the currency of the paid amount.")
          .put("event_date", "Read the visible payment date exactly as printed.")
          .put("record_id", "Read the visible transaction reference, null if absent.")
          .put("evidence", "Quote the visible completion and outgoing payment evidence.")
  private val readerPrompt =
      "What is the actual final amount paid, currency, visible payment date, reference and evidence in this image?"
  private val readerTask = readerPrompt

  private fun writtenPlan() =
      org.json
          .JSONObject()
          .put("verification_question", readerVerification)
          .put("extraction_question", readerPrompt)
          .put("response_format", format)

  private fun response(stage: ChatWorkflowStage, decision: String = "YES"): String =
      when (stage) {
        ChatWorkflowStage.REWRITE -> rewritten
        ChatWorkflowStage.PLAN -> writtenPlan().toString()
        ChatWorkflowStage.VERIFY -> "{\"decision\":\"$decision\",\"evidence\":\"-RM5.72\"}"
        ChatWorkflowStage.EXTRACT -> extracted
        else -> error("No aggregate answer in the per-image engine")
      }

  @Test
  fun generatedPromptsArePassedUnchangedAndOnlyTwoCallsReceiveImageIds() = runBlocking {
    val calls = mutableListOf<Triple<ChatWorkflowStage, String, Long?>>()
    val engine =
        ChatWorkflow({ stage, prompt, source ->
          calls += Triple(stage, prompt, source)
          response(stage)
        })
    val plan = engine.plan(question)
    val result = engine.inspect(plan, listOf(7))
    assertEquals(
        listOf(
            ChatWorkflowStage.REWRITE,
            ChatWorkflowStage.PLAN,
            ChatWorkflowStage.VERIFY,
            ChatWorkflowStage.EXTRACT),
        calls.map { it.first })
    assertEquals(question, calls[0].second)
    assertEquals(rewritten, calls[1].second)
    assertEquals(question, plan.originalQuestion)
    assertFalse(calls[0].second.contains("5.72"))
    assertEquals(
        ChatWorkflowPrompts.input(ChatWorkflowPrompts.verify, readerVerification), calls[2].second)
    assertEquals(
        ChatWorkflowPrompts.extractionInput(readerTask, format, "-RM5.72"), calls[3].second)
    assertEquals(listOf(null, null, 7L, 7L), calls.map { it.third })
    assertEquals(5.72, result.records.single().json.getDouble("value"), 0.0)
  }

  @Test
  fun negativeVerificationStopsWithoutSchemaOrExtraction() = runBlocking {
    val calls = mutableListOf<ChatWorkflowStage>()
    val engine =
        ChatWorkflow({ stage, _, _ ->
          calls += stage
          response(stage, "NO")
        })
    val result = engine.inspect(engine.plan(question), listOf(1))
    assertEquals(3, calls.size)
    assertEquals(1, result.rejected)
    assertTrue(result.records.isEmpty())
    assertNull(result.extractionPrompt)
  }

  @Test
  fun fiveCandidatesShareOnePlanWithoutTextCallsBetweenImages() = runBlocking {
    val calls = mutableListOf<Pair<ChatWorkflowStage, String>>()
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          calls += stage to prompt
          response(stage)
        })
    val result = engine.inspect(engine.plan(question), listOf(1, 2, 3, 4, 5))
    assertEquals(5, result.records.size)
    assertEquals(1, calls.count { it.first == ChatWorkflowStage.PLAN })
    assertEquals(1, calls.count { it.first == ChatWorkflowStage.REWRITE })
    assertEquals(
        List(5) { listOf(ChatWorkflowStage.VERIFY, ChatWorkflowStage.EXTRACT) }.flatten(),
        calls.drop(2).map { it.first })
    assertEquals(
        1, calls.filter { it.first == ChatWorkflowStage.EXTRACT }.map { it.second }.distinct().size)
  }

  @Test
  fun uncertainCandidateCanBeExtractedButKeepsItsUncertainty() = runBlocking {
    val engine = ChatWorkflow({ stage, _, _ -> response(stage, "MAYBE") })
    val result = engine.inspect(engine.plan(question), listOf(1))
    assertEquals(ChatDecision.MAYBE, result.records.single().verification.decision)
  }

  @Test
  fun malformedVerificationIsRecordedAsFailureWithoutInventingDecision() = runBlocking {
    val engine =
        ChatWorkflow({ stage, _, _ ->
          if (stage == ChatWorkflowStage.VERIFY) "YES" else response(stage)
        })
    val result = engine.inspect(engine.plan(question), listOf(1))
    assertEquals(1, result.failed)
    assertTrue(result.records.isEmpty())
    assertNull(result.extractionPrompt)
  }

  @Test
  fun changingGeneratedExtractionFieldsFailsThatCandidate() = runBlocking {
    val engine =
        ChatWorkflow({ stage, _, _ ->
          if (stage == ChatWorkflowStage.EXTRACT) "{\"invented\":5.72}" else response(stage)
        })
    val result = engine.inspect(engine.plan(question), listOf(1))
    assertEquals(1, result.failed)
    assertTrue(result.records.isEmpty())
  }

  @Test
  fun fieldlessInstructionAndImageNullSchemaCannotReachTheReader(): Unit = runBlocking {
    var writingCalls = 0
    var imageCalls = 0
    val engine =
        ChatWorkflow({ stage, _, _ ->
          if (stage == ChatWorkflowStage.VERIFY || stage == ChatWorkflowStage.EXTRACT) imageCalls++
          if (stage == ChatWorkflowStage.PLAN) {
            writingCalls++
            """{"next_prompt":"EXTRACT: Read the attached image. Require visible facts only and unknown dates exactly as printed."}"""
          } else response(stage)
        })
    try {
      engine.inspect(engine.plan(question), listOf(1))
      fail("A fieldless instruction must stop rather than produce an empty answer")
    } catch (_: IllegalArgumentException) {}
    assertEquals(2, writingCalls)
    assertEquals(0, imageCalls)
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionInstruction(
          org.json
              .JSONObject()
              .put("next_prompt", schema)
              .put("fields", org.json.JSONObject().put("image", "Read the attached image itself."))
              .toString())
    }
  }

  @Test
  fun oneGeneralWriterRetryPassesTheCorrectedModelInstructionsUnchanged() = runBlocking {
    val calls = mutableListOf<Pair<ChatWorkflowStage, String>>()
    var writerCalls = 0
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          calls += stage to prompt
          if (stage == ChatWorkflowStage.PLAN && writerCalls++ == 0)
              org.json.JSONObject().put("next_prompt", schema).toString()
          else response(stage)
        })
    val result = engine.inspect(engine.plan(question), listOf(1))
    assertEquals(1, result.records.size)
    assertEquals(2, calls.count { it.first == ChatWorkflowStage.PLAN })
    val retry = calls.filter { it.first == ChatWorkflowStage.PLAN }[1].second
    assertTrue(retry.contains("VALIDATION ERROR:"))
    assertFalse(retry.contains("5.72"))
    assertEquals(
        ChatWorkflowPrompts.extractionInput(readerTask, format, "-RM5.72"), calls.last().second)
  }

  @Test
  fun formatCannotReplaceGeneratedFactsWithAnImageField() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionFormat(
          """{"image":null}""", ChatExtractionInstruction(schema, definitions).readerPrompt)
    }
  }

  @Test
  fun copiedWriterTaskAndQuantityWithoutValueAndUnitAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseVerificationInstruction(
          org.json
              .JSONObject()
              .put(
                  "next_prompt",
                  "VERIFY: Examine the attached image. ${ChatWorkflowPrompts.generate}")
              .toString())
    }
    val incomplete =
        org.json
            .JSONObject()
            .put("event_date", "Read the visible date printed on the record.")
            .put("record_id", "Read the unique identifier for the record.")
            .put("evidence", "Read visible evidence that the event occurred.")
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.validateRequestedFields(rewritten, incomplete)
    }
    ChatWorkflow.validateRequestedFields(rewritten, definitions)
  }

  @Test
  fun plannersCannotInventExampleAmountsButCanKeepUserProvidedThresholds() {
    val invented =
        writtenVerification
            .toString()
            .replace(
                "completed outgoing payment", "completed outgoing payment of 45.00 on 2024-05-22")
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseVerificationInstruction(invented, rewritten)
    }
    ChatWorkflow.rejectInventedPlanningNumbers(
        "A value greater than 100.00", "Find amounts over 100")
  }

  @Test
  fun filledSchemaCannotSeedAnInventedImageAnswer() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionPrompt(
          org.json
              .JSONObject()
              .put("next_prompt", schema)
              .put("response_format", org.json.JSONObject("{\"amount\":5.72}"))
              .toString())
    }
  }

  @Test
  fun bareTemplateCannotPassAsACompleteGeneratedInstruction() {
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionPrompt("EXTRACT: {\"total_expenditure\":null}")
    }
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionPrompt(
          org.json
              .JSONObject()
              .put(
                  "next_prompt", "EXTRACT: total_spent = null, receipt_date = null, user_id = null")
              .put(
                  "response_format",
                  org.json.JSONObject(
                      "{\"total_spent\":null,\"receipt_date\":null,\"user_id\":null}"))
              .toString())
    }
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parseExtractionPrompt(
          org.json
              .JSONObject()
              .put("next_prompt", schema)
              .put("response_format", org.json.JSONObject("{\"undefined_field\":null}"))
              .toString())
    }
  }

  @Test
  fun inventedExtractionAnswersAndFilledPlanTemplatesCannotReachImages() {
    val invented =
        writtenPlan().put("extraction_question", "What is the payment of 5.72 in this image?")
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parsePlan(invented.toString(), question)
    }
    val filled =
        writtenPlan()
            .put("response_format", org.json.JSONObject(format.toString()).put("value", 5.72))
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parsePlan(filled.toString(), question)
    }
  }

  @Test
  fun theSamePlannerWrapperSupportsANonFinancialQuestion() = runBlocking {
    val userQuestion = "Find recipes that use chicken."
    val recipe =
        writtenPlan()
            .put("verification_question", "Does this image show a recipe that includes chicken?")
            .put(
                "extraction_question",
                "What is the recipe name, chicken ingredient and measurement unit, visible date, reference and evidence?")
    val calls = mutableListOf<String>()
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          calls += prompt
          when (stage) {
            ChatWorkflowStage.REWRITE -> userQuestion
            ChatWorkflowStage.PLAN -> recipe.toString()
            ChatWorkflowStage.VERIFY -> """{"decision":"YES","evidence":"chicken breast"}"""
            ChatWorkflowStage.EXTRACT ->
                """{"value":"Chicken soup","unit":null,"event_date":null,"record_id":null,"evidence":"chicken breast"}"""
            else -> error("No answer stage")
          }
        })
    val result = engine.inspect(engine.plan(userQuestion), listOf(1))
    assertEquals("Chicken soup", result.records.single().json.getString("value"))
    assertEquals(userQuestion, calls.first())
    assertTrue(calls.drop(1).all { !it.contains("payment") && !it.contains("spent") })
  }

  @Test
  fun verificationCannotAskForTheAggregateAmount() {
    val invalid =
        writtenPlan().put("verification_question", "How much did you spend in the past week?")
    assertThrows(IllegalArgumentException::class.java) {
      ChatWorkflow.parsePlan(invalid.toString(), question)
    }
  }

  @Test
  fun unfilledQuestionPlaceholdersAreRetriedBeforeAnyImageCall() = runBlocking {
    var writerCalls = 0
    var imageCalls = 0
    val requests = mutableListOf<String>()
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          if (stage == ChatWorkflowStage.PLAN) {
            requests += prompt
            if (writerCalls++ == 0) {
              writtenPlan()
                  .put("verification_question", "Does this image show clear evidence of {TARGET}?")
                  .put("extraction_question", "What is the actual {FACT} for this one record?")
                  .toString()
            } else response(stage)
          } else {
            if (stage == ChatWorkflowStage.VERIFY || stage == ChatWorkflowStage.EXTRACT)
                imageCalls++
            response(stage)
          }
        })
    val plan = engine.plan(question)
    assertEquals(2, writerCalls)
    assertEquals(0, imageCalls)
    assertTrue(requests.last().contains("Replace all question placeholders"))
    assertFalse(requests.last().contains("5.72"))
    assertEquals(readerVerification, plan.verificationPrompt)
    assertEquals(readerPrompt, plan.extraction.prompt)
  }

  @Test
  fun cancellationDoesNotBecomeAnUncertainCandidate() = runBlocking {
    val engine =
        ChatWorkflow({ stage, _, _ ->
          if (stage == ChatWorkflowStage.VERIFY) throw CancellationException("Stopped")
          response(stage)
        })
    try {
      engine.inspect(engine.plan(question), listOf(1))
      fail("Expected cancellation")
    } catch (_: CancellationException) {}
  }

  @Test
  fun modelRewriteRemovesTimeWithoutChangingSubjectOrThreshold() {
    val original = "How much did I spend on coffee over 100 last month?"
    val intent = "How much did I spend on coffee over 100?"
    assertEquals(intent, ChatWorkflow.parseRewrite(intent, original))
    for (bad in
        listOf(
            "How much did I spend over 100?",
            "How much did I spend on tea over 100?",
            "How much did I spend on coffee over 100 last month?",
            "How much did I spend on coffee over 200?")) {
      assertThrows(IllegalArgumentException::class.java) {
        ChatWorkflow.parseRewrite(bad, original)
      }
    }
  }

  @Test
  fun timeFreeShortSpendingIntentsCanPassToThePlanner() {
    for (question in listOf("spending last week", "Oct 1 spending")) {
      assertEquals("spending", ChatWorkflow.parseRewrite("spending", question))
    }
  }

  @Test
  fun rewriteCorrectionStillRequestsPlainTextAndFeedsTheModelOutputUnchanged() = runBlocking {
    val requests = mutableListOf<Pair<ChatWorkflowStage, String>>()
    var rewriteCalls = 0
    val engine =
        ChatWorkflow({ stage, prompt, _ ->
          requests += stage to prompt
          if (stage == ChatWorkflowStage.REWRITE && rewriteCalls++ == 0) question
          else response(stage)
        })
    engine.plan(question)
    assertEquals(2, rewriteCalls)
    assertTrue(
        requests[1].second.endsWith("Return only the rewritten request as one plain sentence."))
    assertFalse(requests[1].second.contains("required JSON"))
    assertEquals(rewritten, requests[2].second)
  }
}
