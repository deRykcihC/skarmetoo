package com.deryk.skarmetoo.chat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.generateChatResponse
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

/** Direct text probe, not an implementation of compound requests in production Chat. */
@RunWith(AndroidJUnit4::class)
class LfmCompoundRequestDeviceTest {
  @Test
  fun modelGeneratesQuestionsForBothPartsOfTheOriginalRequest(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val args = InstrumentationRegistry.getArguments()
    val question = args.getString("chatQuestion")
    assumeTrue("Supply the exact original user question", !question.isNullOrBlank())
    val original = requireNotNull(question)
    val today = args.getString("chatToday")?.let(LocalDate::parse) ?: LocalDate.now()
    val manager = GgufLlmManager.getInstance(context)
    assumeTrue(manager.isModelDownloaded(ChatWorkflowModels.planner))
    val system =
        "Read the user's request and identify the distinct parts needed to answer it. Generate a separate standalone question for each requested part, preserving its subject and constraints. If it asks for multiple time periods, keep them distinct; never replace them with one continuous date range. You have no images or results. Do not answer, invent facts or calculate dates. Return only one JSON object with a questions key whose value is an array of strings. Each string is one concrete question derived from the user's request and ends with a question mark. Use only necessary questions. Do not output placeholders or generic writing instructions."
    val trace = JSONArray()
    val query = ChatQuery.parse(original, today)
    val report =
        JSONObject()
            .put("original_question", original)
            .put("reference_today", today)
            .put("production_parser_rejected", query.unsupportedDate)
            .put("production_parser_error", query.dateError)
            .put(
                "production_call_order",
                "The original date parser runs before LFM in ChatWorkflowRunner.")
            .put("probe_model", ChatWorkflowModels.planner.displayName)
            .put("fixed_wrapper", system)
            .put("calls", trace)
            .put("production_support_implemented", false)
    val output = File(context.cacheDir, "lfm-compound-request-device.json")
    try {
      manager.loadModel(ChatWorkflowModels.planner)
      val state =
          withTimeout(120000) {
            manager.uiState.first {
              it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
            }
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      var prompt = original
      var parsed: List<String>? = null
      repeat(2) { attempt ->
        if (parsed != null) return@repeat
        val row = JSONObject().put("prompt", prompt).put("system_prompt", system)
        trace.put(row)
        output.writeText(report.toString(2))
        val started = SystemClock.elapsedRealtime()
        try {
          val raw =
              manager.generateChatResponse(
                  prompt,
                  expectedModel = ChatWorkflowModels.planner,
                  maxOutputTokens = 256,
                  systemPrompt = system)
          row.put("response", raw)
          val json = ChatWorkflow.objectResponse(raw)
          require(json.keys().asSequence().toSet() == setOf("questions")) {
            "Return exactly the questions array in one JSON object."
          }
          val array = json.getJSONArray("questions")
          val questions = (0 until array.length()).map { array.getString(it) }
          require(
              questions.isNotEmpty() &&
                  questions.size <= 8 &&
                  questions.all { it.length in 8..1000 && it.trim().endsWith('?') }) {
                "Generate concrete standalone questions for all distinct requested parts."
              }
          parsed = questions
        } catch (e: Exception) {
          row.put("error", e.message)
          if (attempt == 1) throw e
          prompt =
              "$original\n\nVALIDATION ERROR: ${e.message}\nReturn only the corrected questions JSON."
        } finally {
          row.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
          output.writeText(report.toString(2))
        }
      }
      val questions = requireNotNull(parsed)
      report.put("generated_questions", JSONArray(questions))
      // These are post-inference assertions for the supplied example, never model inputs.
      assertEquals(2, questions.size)
      assertTrue(
          questions.any { it.contains("last week", true) && !it.contains("September", true) })
      assertTrue(
          questions.any {
            it.contains("first week", true) &&
                it.contains("September", true) &&
                !it.contains("last week", true)
          })
      assertTrue(
          questions.all {
            Regex("\\b(spend|spent|spending|expense|expenses)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(it)
          })
      report.put("model_decomposition_passed", true)
      assertTrue(
          "This exact compound request is unsupported by the existing app parser",
          query.unsupportedDate)
    } catch (e: Throwable) {
      report.put("model_decomposition_passed", false).put("error", e.message)
      throw e
    } finally {
      manager.closeAndWait()
      output.writeText(report.toString(2))
    }
  }
}
