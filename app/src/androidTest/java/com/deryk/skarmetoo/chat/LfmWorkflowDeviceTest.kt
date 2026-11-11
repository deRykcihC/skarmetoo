package com.deryk.skarmetoo.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ai.VisionBatch
import com.deryk.skarmetoo.ai.generateChatResponse
import com.deryk.skarmetoo.ai.withVisionBatch
import com.llamatik.library.platform.LlamaBridge
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional real GGUF check; requires both LFM presets and an explicitly supplied cache fixture. */
@RunWith(AndroidJUnit4::class)
class LfmWorkflowDeviceTest {
  @Test
  fun generatedPromptsRunAcross3bPlanningAnd450mVision(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = File(context.cacheDir, "lfm-chat-test.jpg")
    val manager = GgufLlmManager.getInstance(context)
    assumeTrue(
        "Download both LFM presets and supply the test image first",
        manager.isModelDownloaded(ChatWorkflowModels.planner) &&
            manager.isModelDownloaded(ChatWorkflowModels.vision) &&
            image.isFile)
    val trace = JSONArray()
    val report =
        JSONObject()
            .put("planner_model", ChatWorkflowModels.planner.fileName)
            .put("vision_model", ChatWorkflowModels.vision.fileName)
            .put("calls", trace)
    var batch: VisionBatch? = null
    val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath))
    try {
      manager.loadModel(LFM2_5_3B_MODEL)
      val state =
          withTimeout(120000) {
            manager.uiState.first {
              it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
            }
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      val workflow =
          ChatWorkflow({ stage, prompt, source ->
            val row =
                JSONObject()
                    .put("stage", stage.name)
                    .put("prompt", prompt)
                    .put("image", source != null)
                    .put("model", ChatWorkflowModels.forStage(stage).fileName)
            trace.put(row)
            val started = SystemClock.elapsedRealtime()
            val system =
                when (stage) {
                  ChatWorkflowStage.REWRITE -> ChatWorkflowPrompts.rewrite
                  ChatWorkflowStage.PLAN -> ChatWorkflowPrompts.plan
                  else -> null
                }
            if (system != null) row.put("system_prompt", system)
            ChatWorkflowSchemas.forStage(stage)?.let { row.put("response_schema", it) }
            try {
              (if (source != null) requireNotNull(batch).generate(prompt, bitmap, stage.tokenLimit)
                  else
                      manager.generateChatResponse(
                          prompt,
                          expectedModel = LFM2_5_3B_MODEL,
                          maxOutputTokens = stage.tokenLimit,
                          systemPrompt = system,
                          responseSchema =
                              ChatWorkflowSchemas.forStage(stage).takeUnless {
                                stage == ChatWorkflowStage.PLAN
                              }))
                  .also {
                    row.put("response", it)
                    if (source == null)
                        row.put(
                            "rendered_prompt",
                            LlamaBridge.applyChatTemplate(
                                listOfNotNull(system?.let { "system" to it }, "user" to prompt),
                                true))
                  }
            } finally {
              row.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
              File(context.cacheDir, "lfm-workflow-device.json").writeText(report.toString(2))
            }
          })
      val plan = workflow.plan("how much did I spend past last week?")
      val result =
          manager.withVisionBatch(ChatWorkflowModels.planner, ChatWorkflowModels.vision) { session
            ->
            batch = session
            try {
              workflow.inspect(plan, listOf(1))
            } finally {
              batch = null
            }
          }
      assertNull(
          "Batch completion releases the image model before the text answer",
          manager.residentModel.value)
      report.put("rejected", result.rejected).put("failed", result.failed)
      report.put("records", JSONArray(result.records.map { it.json }))
      assertEquals("One image should supply evidence", 1, result.records.size)
      assertEquals(ChatDecision.YES, result.records.single().verification.decision)
      assertTrue("Text writers have bounded retries", trace.length() in 4..7)
      assertEquals(
          "The sample must use exactly two image calls",
          2,
          (0 until trace.length()).count { trace.getJSONObject(it).getBoolean("image") })
      assertEquals(ChatWorkflowModels.planner, manager.activeModelInfo.value)
      // This checks the visible value; its ambiguous date still cannot confirm a dated total.
      val values =
          ChatWorkflowFacts.amounts(
              ChatQuery.parse("How much did I spend?"), result.records, emptyMap())
      report.put(
          "readable_totals", JSONObject(values.totals.mapValues { it.value.toPlainString() }))
      assertEquals(BigDecimal("5.72"), values.totals["MYR"])
      assertTrue(
          "The model must request the event date for later filtering",
          result.records.single().json.keys().asSequence().any {
            it.contains("date") || it.contains("time")
          })
      assertTrue(
          "The visible ambiguous date must remain in its original order",
          result.records.single().json.toString().contains("01-10-2026"))
      val weekly =
          ChatWorkflowFacts.amounts(
              ChatQuery.parse("how much did I spend past last week?"), result.records, emptyMap())
      report.put("weekly_totals", JSONObject(weekly.totals))
      assertTrue("An ambiguous date must not be counted in a dated total", weekly.totals.isEmpty())
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("passed", false).put("error", e.message)
      throw e
    } finally {
      File(context.cacheDir, "lfm-workflow-device.json").writeText(report.toString(2))
      bitmap.recycle()
      manager.closeAndWait()
    }
  }
}
