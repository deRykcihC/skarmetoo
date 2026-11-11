package com.deryk.skarmetoo.ai.agentic

import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.WorkflowBackend
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One untouched user sentence per attempt. No expected answers, external retries or corrections.
 */
@RunWith(AndroidJUnit4::class)
class AgenticQuestionBenchmarkDeviceTest {
  @Test
  fun recordQuestionsSequentially(): Unit =
      runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("agenticRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val directory = File(context.filesDir, "agentic-question-benchmark/$runId")
        val request = JSONObject(File(directory, "request.json").readText())
        val lock = File(directory, "started.lock")
        check(lock.mkdir()) { "Refusing to restart/overwrite an existing attempt: $runId" }
        fun save(name: String, value: JSONObject) {
          val target = File(directory, name)
          val temporary = File(directory, "$name.tmp")
          temporary.outputStream().use { output ->
            output.write(value.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
          }
          check(temporary.renameTo(target)) { "Could not atomically save $name" }
        }
        val status =
            JSONObject().put("run_id", runId).put("state", "preflight").put("pid", Process.myPid())
        save("status.json", status)
        var pipeline: AgenticPipeline? = null
        try {
          val questions = request.getJSONArray("questions")
          require(questions.length() == 100)
          val bundled =
              InstrumentationRegistry.getInstrumentation()
                  .context
                  .assets
                  .open("chat-test-100.txt")
                  .bufferedReader()
                  .use { it.readLines().filter(String::isNotBlank) }
          require((0 until questions.length()).map { questions.getString(it) } == bundled) {
            "Question list differs from the original LFM test asset"
          }
          val selection = request.optJSONArray("question_ids")
          val selected =
              if (selection == null) (1..100).toList()
              else (0 until selection.length()).map { selection.getInt(it) }
          require(
              selected.isNotEmpty() &&
                  selected.size <= 100 &&
                  selected.distinct().size == selected.size &&
                  selected.all { it in 1..100 })
          status.put("attempt_count", selected.size)
          val zone = ZoneId.of(request.getString("timezone"))
          require(ZoneId.systemDefault().rules == zone.rules) {
            "Phone timezone differs from benchmark timezone"
          }
          val today = LocalDate.parse(request.getString("reference_date"))
          val fixedClock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
          val entries = ScreenshotDatabase(context).use { it.getAllEntries() }
          val corpus = request.getJSONArray("corpus")
          require(entries.size == corpus.length()) { "Image corpus size changed" }
          val actual = entries.associateBy { it.id }
          val sources = JSONArray()
          for (i in 0 until corpus.length()) {
            val expected = corpus.getJSONObject(i)
            val entry =
                requireNotNull(actual[expected.getLong("source_id")]) { "Missing original source" }
            require(entry.imageUri == expected.getString("image_uri")) {
              "Original image URI changed"
            }
            val hash =
                context.contentResolver.openInputStream(Uri.parse(entry.imageUri))?.use {
                  EmbeddingGemma2.sha256(it)
                } ?: error("Original image cannot be read")
            require(hash == expected.getString("sha256")) {
              "Original source pixels changed: ${entry.id}"
            }
            sources.put(
                JSONObject()
                    .put("source_id", entry.id)
                    .put("image_uri", entry.imageUri)
                    .put("sha256", hash))
          }
          val backend = WorkflowBackend.valueOf(request.getString("backend"))
          val active = AgenticPipeline(context, clock = fixedClock, sourceEntries = { entries })
          pipeline = active
          active.backend = backend
          require(active.gemma.modelFile.isFile && active.embedding.available) {
            "Required existing model file missing"
          }
          save(
              "manifest.json",
              JSONObject()
                  .put("run_id", runId)
                  .put("reference_date", today.toString())
                  .put("timezone", zone.id)
                  .put("device", Build.MODEL)
                  .put("soc", Build.HARDWARE)
                  .put("backend", backend.name)
                  .put("pipeline_version", AgenticIndex.VERIFIER_VERSION)
                  .put("question_sha256", request.getString("question_sha256"))
                  .put("cache_mode", "existing_production_cache_retained")
                  .put("external_retries", 0)
                  .put("question_count", selected.size)
                  .put("question_ids", JSONArray(selected))
                  .put(
                      "date_policy",
                      "previous Monday-Sunday calendar week; numbered month weeks days 1-7 etc")
                  .put("native_call_timeout_ms", 120000)
                  .put("sources", sources))
          for ((attempt, number) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            val reportName = "%03d.json".format(number)
            check(!File(directory, reportName).exists()) { "Earlier attempt exists: $number" }
            val question = questions.getString(number - 1)
            val started = SystemClock.elapsedRealtime()
            val events = JSONArray()
            val report =
                JSONObject()
                    .put("id", number)
                    .put("question", question)
                    .put("status", "running")
                    .put("started_wall_ms", System.currentTimeMillis())
            status
                .put("state", "running")
                .put("id", number)
                .put("attempt", attempt + 1)
                .put("question", question)
                .put("last_progress_wall_ms", System.currentTimeMillis())
            save("status.json", status)
            save(reportName, report)
            var critical: Throwable? = null
            try {
              val result =
                  active.run(question) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    val event =
                        JSONObject()
                            .put("offset_ms", now - started)
                            .put("state", progress.state.name)
                            .put("message", progress.message)
                            .put("done", progress.done)
                            .put("total", progress.total)
                    events.put(event)
                    report.put("progress", event).put("events", events)
                    save(reportName, report)
                    status
                        .put("last_progress_wall_ms", System.currentTimeMillis())
                        .put("progress", event)
                    save("status.json", status)
                  }
              report.put("status", result.state.name.lowercase()).put("answer", result.answer)
            } catch (e: CancellationException) {
              critical = e
              report.put("status", "cancelled").put("error", e.stackTraceToString())
            } catch (e: Exception) {
              report.put("status", "error").put("error", e.stackTraceToString())
              if (e.stackTraceToString().contains("draining", ignoreCase = true) ||
                  e.stackTraceToString().contains("quarantined", ignoreCase = true))
                  critical = e
            } catch (e: Throwable) {
              critical = e
              report.put("status", "critical_stop").put("error", e.stackTraceToString())
            } finally {
              report
                  .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                  .put("finished_wall_ms", System.currentTimeMillis())
                  .put("events", events)
                  .put("flow", active.lastReport)
              // Pipeline may isolate a quarantined image worker into a partial result. Stop the
              // batch
              // rather than issuing another prompt against that unsafe native engine.
              if (active.lastReport.toString().contains("quarantined", true) ||
                  active.lastReport.toString().contains("still draining", true)) {
                critical = critical ?: IllegalStateException("Native engine is still draining")
              }
              save(reportName, report)
              status
                  .put("last_finished_id", number)
                  .put("last_status", report.getString("status"))
                  .put("state", if (critical == null) "running" else "critical_stop")
              save("status.json", status)
            }
            critical?.let { throw it }
          }
          status.put("state", "completed").put("finished_wall_ms", System.currentTimeMillis())
          save("status.json", status)
        } catch (e: Throwable) {
          status.put("state", "critical_stop").put("error", e.stackTraceToString())
          save("status.json", status)
          throw e
        } finally {
          try {
            pipeline?.close()
          } finally {
            pipeline?.index?.close()
          }
        }
      }
}
