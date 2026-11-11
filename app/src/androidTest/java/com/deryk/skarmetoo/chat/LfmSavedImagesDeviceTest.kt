package com.deryk.skarmetoo.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional live-library regression: uses the production runner and a fresh isolated response cache.
 */
@RunWith(AndroidJUnit4::class)
class LfmSavedImagesDeviceTest {
  @Test
  fun savedPaymentsReachTheAnswerThroughGeneratedInstructions(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val args = InstrumentationRegistry.getArguments()
    val expectedId = args.getString("expectedPaymentSource")?.toLongOrNull()
    val expectedAmount = args.getString("expectedPaymentAmount")?.toBigDecimalOrNull()
    assumeTrue(
        "Supply a known saved payment for this optional device check",
        expectedId != null && expectedAmount != null)
    val question = args.getString("chatQuestion") ?: "How much did I spend last week?"
    val today = args.getString("chatToday")?.let { LocalDate.parse(it) } ?: LocalDate.now()
    val manager = GgufLlmManager.getInstance(context)
    assumeTrue(
        manager.isModelDownloaded(ChatWorkflowModels.planner) &&
            manager.isModelDownloaded(ChatWorkflowModels.vision))
    val reportFile = File(context.cacheDir, "lfm-saved-images-device.json")
    val report =
        JSONObject()
            .put("question", question)
            .put("today", today.toString())
            .put("wrapper_fingerprint", ChatWorkflowPrompts.fingerprint)
    val databaseName = "chat_workflow_live_probe.db"
    context.deleteDatabase(databaseName)
    val cache = ChatWorkflowCache(context, databaseName)
    val repository = ChatRepository(context)
    report.put("numeric_date_order", repository.numericDateOrder.name)
    var steps = emptyList<ChatWorkflowStep>()
    fun save() {
      reportFile.writeText(report.toString(2))
    }
    try {
      manager.loadModel(ChatWorkflowModels.planner)
      val state =
          withTimeout(120000) {
            manager.uiState.first {
              it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
            }
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      val result =
          ChatWorkflowRunner(
                  manager,
                  ChatWorkflowModels.planner,
                  context.filesDir,
                  repository,
                  cache,
                  checkActive = {},
                  onSteps = { current ->
                    steps = current
                    if (current.lastOrNull()?.working == false) {
                      report.put(
                          "steps",
                          JSONArray(
                              current.map { step ->
                                JSONObject()
                                    .put("stage", step.stage.name)
                                    .put("source_id", step.sourceId)
                                    .put("model", step.model)
                                    .put("prompt", step.prompt)
                                    .put("system_prompt", step.systemPrompt)
                                    .put("response_schema", step.responseSchema)
                                    .put("response", step.response)
                                    .put("error", step.error)
                                    .put("cached", step.cached)
                                    .put("elapsed_ms", step.elapsedMs)
                                    .put("date_evidence", step.dateEvidence)
                                    .put("amount_evidence", step.amountEvidence)
                              }))
                      save()
                    }
                  },
                  onProgress = {
                    report.put("progress", it)
                    save()
                  },
                  onRetrieval = { query, found ->
                    report
                        .put("period", query.period)
                        .put("date_basis", query.dateBasis.name)
                        .put("library_count", found.libraryCount)
                        .put("relevant_count", found.relevantCount)
                        .put(
                            "candidates",
                            JSONArray(
                                found.candidates.map {
                                  JSONObject()
                                      .put("source_id", it.entry.id)
                                      .put("image_date", it.imageDate)
                                      .put("taken_date", it.dates.taken)
                                      .put("taken_source", it.dates.takenSource)
                                      .put("added_date", it.dates.added)
                                      .put("modified_date", it.dates.modified)
                                }))
                    save()
                  },
                  today = today)
              .run(question)
      report.put("answer", result.answer)
      report.put(
          "parts",
          JSONArray(
              result.parts.map { part ->
                JSONObject()
                    .put("question", part.question)
                    .put("period", part.query.period)
                    .put("date_basis", part.query.dateBasis.name)
                    .put("status", part.status)
                    .put("candidates", part.candidates)
                    .put("summary", part.summary)
                    .put("accepted_sources", JSONArray(part.records.map { it.sourceId }))
                    .put(
                        "totals",
                        JSONObject(
                            part.amounts?.totals?.mapValues { it.value.toPlainString() }.orEmpty()))
              }))
      save()
      assertFalse(
          "Internal prompt labels must stay in the raw trace, not the displayed answer",
          result.answer.contains("APP_CALCULATIONS") || result.answer.contains("ACCEPTED FACTS"))
      val amounts =
          requireNotNull(result.amounts) { "The production runner must return checked amounts." }
      report.put(
          "accepted",
          JSONArray(
              amounts.amounts.map {
                JSONObject()
                    .put("source_id", it.record.sourceId)
                    .put("amount", it.amount.toPlainString())
                    .put("currency", it.currency)
                    .put("date", it.date)
                    .put("date_basis", it.resolvedDate?.label)
                    .put("date_evidence", it.resolvedDate?.evidence)
                    .put("printed_date_evidence", it.printedDate?.evidence)
                    .put("printed_amount_evidence", it.printedAmount?.evidence)
              }))
      save()
      assertFalse(
          "A fresh response cache must not replay earlier model outputs", steps.any { it.cached })
      assertEquals(question, steps.first { it.stage == ChatWorkflowStage.SPLIT }.prompt)
      val generated =
          ChatRequestWorkflow.parseQuestions(
              steps.last { it.stage == ChatWorkflowStage.SPLIT && it.error.isBlank() }.response)
      assertEquals(generated, result.parts.map { it.question })
      assertTrue(
          result.parts.all { part ->
            steps.any {
              it.stage == ChatWorkflowStage.DATES &&
                  it.prompt.startsWith("QUESTION:\n${part.question}\n")
            }
          })
      steps
          .filter { it.stage == ChatWorkflowStage.VERIFY || it.stage == ChatWorkflowStage.EXTRACT }
          .groupBy { it.sourceId }
          .forEach { (_, calls) ->
            assertTrue(
                "At most two visual calls per candidate for this shared goal", calls.size <= 2)
            assertTrue(calls.all { it.model == ChatWorkflowModels.vision.displayName })
          }
      assertTrue(
          steps
              .filter {
                it.stage != ChatWorkflowStage.VERIFY && it.stage != ChatWorkflowStage.EXTRACT
              }
              .all { it.model == ChatWorkflowModels.planner.displayName })
      args.getString("expectedPeriods")?.split('|')?.let { expected ->
        assertEquals(expected, result.parts.map { it.query.period })
      }
      args.getString("expectedEmptyPeriod")?.let { emptyPeriod ->
        val empty = result.parts.single { it.query.period == emptyPeriod }
        assertEquals("no_confirmed_evidence", empty.status)
        assertTrue(empty.records.isEmpty())
        assertTrue(empty.amounts!!.totals.isEmpty())
        assertTrue(empty.summary.contains("unknown"))
      }
      assertTrue(
          "The known payment must be extracted and included; see the device report",
          amounts.amounts.any {
            it.record.sourceId == expectedId &&
                it.amount.compareTo(expectedAmount) == 0 &&
                it.currency == "MYR"
          })
      assertTrue(
          "The production runner must provide a nonempty confirmed total",
          result.answer.startsWith("Amounts found"))
      args
          .getString("expectedPaymentSources")
          ?.split(',')
          ?.map { it.toLong() }
          ?.let { expected ->
            assertEquals(
                "Every expected payment image must contribute once",
                expected.toSet(),
                amounts.amounts.map { it.record.sourceId }.toSet())
            assertEquals(0, amounts.excluded)
            assertEquals(0, amounts.duplicates)
          }
      args.getString("expectedMyrTotal")?.toBigDecimalOrNull()?.let { expected ->
        assertEquals(
            "Only the known completed payments should contribute to the total",
            0,
            requireNotNull(amounts.totals["MYR"]).compareTo(expected))
        assertTrue(
            "The displayed total must match the checked records",
            result.answer.contains("MYR ${expected.toPlainString()}"))
      }
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("failure", e.message).put("passed", false)
      throw e
    } finally {
      save()
      repository.close()
      cache.close()
      context.deleteDatabase(databaseName)
      manager.closeAndWait()
    }
  }
}
