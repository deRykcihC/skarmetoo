package com.deryk.skarmetoo.ai.agentic

import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Critical adapter regression check; expected facts never enter pipeline prompts. */
class AgenticImprovementDeviceTest {
  @Test
  fun printedDatesAndQuestionCoverage(): Unit =
      runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = ScreenshotDatabase(context).use { it.getAllEntries() }
        assertEquals(38, entries.size)
        val zone = ZoneId.of("Asia/Kuala_Lumpur")
        val clock =
            Clock.fixed(LocalDate.parse("2026-10-08").atTime(12, 0).atZone(zone).toInstant(), zone)
        val pipeline = AgenticPipeline(context, clock = clock, sourceEntries = { entries })
        val reports = JSONArray()
        val directory = File(context.filesDir, "agentic-v12-preflight").apply { mkdirs() }
        val cases =
            listOf(
                Triple("How much have I spent yesterday?", "7.82", "2026-10-07"),
                Triple(
                    "How much money did I spend the day before yesterday?", "6.30", "2026-10-06"),
                Triple(
                    "How much did I pay for my purchases from 30/09/2026 to 02/10/2026?",
                    "21.05",
                    "2026-09-30"))
        try {
          cases.forEachIndexed { n, (question, expected, start) ->
            val result =
                try {
                  pipeline.run(question) { progress ->
                    File(directory, "progress.json")
                        .writeText(
                            JSONObject()
                                .put("case", n + 1)
                                .put("question", question)
                                .put("stage", progress.state.name)
                                .put("message", progress.message)
                                .toString(2))
                  }
                } catch (error: Exception) {
                  reports.put(pipeline.lastReport)
                  throw error
                }
            reports.put(pipeline.lastReport)
            File(directory, "results.json").writeText(reports.toString(2))
            assertEquals(WorkflowState.COMPLETED, result.state)
            assertEquals(
                0, result.calculation.totals.getValue("MYR").compareTo(BigDecimal(expected)))
            assertEquals(
                start,
                pipeline.lastReport
                    .getJSONArray("date_windows")
                    .getJSONObject(0)
                    .getString("start"))
            assertTrue(result.calculation.unresolvedIds.isEmpty())
            assertTrue(pipeline.lastReport.getJSONObject("plan").isNull("category"))
          }
        } finally {
          File(directory, "results.json").writeText(reports.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }
}
