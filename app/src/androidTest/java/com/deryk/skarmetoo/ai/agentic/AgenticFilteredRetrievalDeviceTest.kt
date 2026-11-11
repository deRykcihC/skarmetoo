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

/**
 * Full original library and original single-sentence requests; expected values are assertion-only.
 */
class AgenticFilteredRetrievalDeviceTest {
  @Test
  fun retryFewOriginalQuestions(): Unit =
      runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = ScreenshotDatabase(context).use { it.getAllEntries() }
        assertEquals(38, source.size)
        val zone = ZoneId.of("Asia/Kuala_Lumpur")
        val clock =
            Clock.fixed(LocalDate.parse("2026-10-08").atTime(12, 0).atZone(zone).toInstant(), zone)
        val cases =
            listOf(
                Triple("How much did I spend today?", emptySet<Long>(), null),
                Triple("What is the total spending on 09-29 and 09/26?", setOf(7L, 8L), "10.94"),
                Triple("Total paid on 2026-10-01 & 2026-10-02", setOf(5L, 4L), "13.41"))
        val reports = JSONArray()
        val pipeline = AgenticPipeline(context, clock = clock, sourceEntries = { source })
        val directory = File(context.filesDir, "agentic-filtered-retries").apply { mkdirs() }
        val progress = File(directory, "progress.json")
        try {
          cases.forEachIndexed { number, (question, expectedIds, expectedTotal) ->
            val result =
                pipeline.run(question) { event ->
                  progress.writeText(
                      JSONObject()
                          .put("case", number + 1)
                          .put("question", question)
                          .put("state", event.state.name)
                          .put("message", event.message)
                          .toString(2))
                }
            val report = pipeline.lastReport
            reports.put(report)
            File(directory, "results.json").writeText(reports.toString(2))
            val filter = report.getJSONObject("retrieval_filter")
            assertEquals(38, filter.getInt("library_count"))
            assertEquals(expectedIds.size, filter.getInt("candidate_count"))
            val filteredIds =
                filter.getJSONArray("records").let { records ->
                  (0 until records.length())
                      .map { records.getJSONObject(it) }
                      .filter { it.getBoolean("included") }
                      .map { it.getLong("source_id") }
                      .toSet()
                }
            assertEquals(expectedIds, filteredIds)
            assertEquals(WorkflowState.COMPLETED, result.state)
            assertEquals(expectedIds.size, result.sources.size)
            if (expectedTotal == null) assertTrue(result.calculation.totals.isEmpty())
            else
                assertEquals(
                    0,
                    result.calculation.totals.getValue("MYR").compareTo(BigDecimal(expectedTotal)))
          }
        } finally {
          File(directory, "results.json").writeText(reports.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }
}
