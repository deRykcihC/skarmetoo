package com.deryk.skarmetoo.ai.agentic

import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.WorkflowBackend
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run one supplied user sentence on the real library, clock, backend and retained caches. */
class AgenticSingleQuestionDeviceTest {
  private class PlanningComplete : CancellationException("Planning-only check complete")

  @Test
  fun recordSingleQuestion(): Unit =
      runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val question = requireNotNull(InstrumentationRegistry.getArguments().getString("question"))
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
        val planningOnly =
            InstrumentationRegistry.getArguments().getString("planningOnly") == "true"
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val directory = File(context.filesDir, "agentic-single-question/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Do not overwrite an earlier attempt" }
        val pipeline = AgenticPipeline(context)
        pipeline.backend =
            WorkflowBackend.valueOf(
                context.getSharedPreferences("chat_settings", 0).getString("backend", "CPU_CPU")!!)
        try {
          val result =
              pipeline.run(question) { event ->
                File(directory, "progress.json")
                    .writeText(
                        JSONObject()
                            .put("question", question)
                            .put("state", event.state.name)
                            .put("message", event.message)
                            .put("done", event.done)
                            .put("total", event.total)
                            .toString(2))
                if (planningOnly && event.state == WorkflowState.RETRIEVING)
                    throw PlanningComplete()
              }
          File(directory, "answer.json")
              .writeText(
                  JSONObject()
                      .put("state", result.state.name)
                      .put("answer", result.answer)
                      .put("requested_periods", org.json.JSONArray(result.requestedPeriods))
                      .toString(2))
          assertTrue(
              result.state == WorkflowState.COMPLETED ||
                  result.state == WorkflowState.PARTIAL_RESULT)
        } catch (done: PlanningComplete) {
          assertTrue(planningOnly)
          assertTrue(
              pipeline.lastReport.getJSONObject("request_count_check").getBoolean("complete"))
          File(directory, "answer.json")
              .writeText(JSONObject().put("planning_only", true).toString())
        } finally {
          File(directory, "report.json").writeText(pipeline.lastReport.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }
}
