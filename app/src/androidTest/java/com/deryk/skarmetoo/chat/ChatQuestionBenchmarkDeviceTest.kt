package com.deryk.skarmetoo.chat

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Observation only: original questions, production cache/runner, no answer assertions or repairs.
 */
@RunWith(AndroidJUnit4::class)
class ChatQuestionBenchmarkDeviceTest {
  @Test
  fun recordQuestionsSequentially(): Unit = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val args = InstrumentationRegistry.getArguments()
    val questions =
        instrumentation.context.assets.open("chat-test-100.txt").bufferedReader().use {
          it.readLines().filter(String::isNotBlank)
        }
    check(questions.size == 100)
    val today = LocalDate.parse(args.getString("chatToday") ?: "2026-10-08")
    val startAt = args.getString("startAt")?.toIntOrNull() ?: 1
    val endAt = args.getString("endAt")?.toIntOrNull() ?: 100
    require(startAt in 1..100 && endAt in startAt..100)
    val directory = File(context.cacheDir, "chat-question-benchmark").apply { mkdirs() }
    val manager = GgufLlmManager.getInstance(context)
    check(manager.isModelDownloaded(ChatWorkflowModels.planner))
    check(manager.isModelDownloaded(ChatWorkflowModels.vision))
    val manifest =
        JSONObject()
            .put("today", today.toString())
            .put("timezone", ZoneId.systemDefault().id)
            .put("manufacturer", Build.MANUFACTURER)
            .put("device", Build.MODEL)
            .put("planner", ChatWorkflowModels.planner.displayName)
            .put("vision", ChatWorkflowModels.vision.displayName)
            .put("wrapper_fingerprint", ChatWorkflowPrompts.fingerprint)
            .put("cache_mode", "existing_production_cache_retained")
            .put("external_retries", 0)
            .put("inference_timeout", JSONObject.NULL)
            .put("question_count", questions.size)
    File(directory, "manifest.json").writeText(manifest.toString(2))

    // Preserve source pixels for independent review; never feed those review answers to Chat.
    val libraryFile = File(directory, "library.json")
    if (!libraryFile.exists()) {
      val database = ScreenshotDatabase(context)
      val repository = ChatRepository(context)
      val images = File(directory, "images").apply { mkdirs() }
      try {
        val rows =
            database.getAllEntries().map { entry ->
              val row =
                  JSONObject()
                      .put("source_id", entry.id)
                      .put("image_uri", entry.imageUri)
                      .put("summary", entry.summary)
                      .put("tags", entry.tags)
                      .put("note", entry.note)
                      .put("analyzing", entry.isAnalyzing)
              try {
                val dates = repository.imageDates(entry.imageUri)
                row.put("taken", dates.taken?.toString())
                    .put("taken_source", dates.takenSource)
                    .put("added", dates.added?.toString())
                    .put("modified", dates.modified?.toString())
                val image = File(images, "source-" + entry.id + ".jpg")
                context.contentResolver.openInputStream(Uri.parse(entry.imageUri)).use { input ->
                  requireNotNull(input).use { source ->
                    image.outputStream().use { destination -> source.copyTo(destination) }
                  }
                }
                row.put("snapshot", image.name)
              } catch (e: Exception) {
                row.put("snapshot_error", e.toString())
              }
              row
            }
        libraryFile.writeText(JSONObject().put("entries", JSONArray(rows)).toString(2))
      } finally {
        repository.close()
        database.close()
      }
    }
    if (args.getString("snapshotOnly") == "true") return@runBlocking
    manager.loadModel(ChatWorkflowModels.planner)
    val selected =
        manager.uiState.first {
          it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
        }
    check(selected is GgufLlmManager.LlmState.Ready) { selected.toString() }

    for (number in startAt..endAt) {
      currentCoroutineContext().ensureActive()
      val reportFile = File(directory, "%03d.json".format(number))
      check(!reportFile.exists()) { "Refusing to overwrite an earlier attempt: " + number }
      val started = SystemClock.elapsedRealtime()
      val report =
          JSONObject()
              .put("id", number)
              .put("question", questions[number - 1])
              .put("today", today.toString())
              .put("started_wall_ms", System.currentTimeMillis())
              .put("started_monotonic_ms", started)
              .put("status", "running")
      val events = JSONArray()
      val retrievals = JSONArray()
      var steps = emptyList<ChatWorkflowStep>()
      var writtenSteps = 0
      var retrievalStarted: Long? = null
      fun save() {
        report.put(
            "steps",
            JSONArray(
                steps.map { step ->
                  JSONObject()
                      .put("stage", step.stage.name)
                      .put("source_id", step.sourceId)
                      .put("model", step.model)
                      .put("prompt", step.prompt)
                      .put("system_prompt", step.systemPrompt)
                      .put("response_schema", step.responseSchema)
                      .put("response", step.response)
                      .put("error", step.error)
                      .put("working", step.working)
                      .put("cached", step.cached)
                      .put("elapsed_ms", step.elapsedMs)
                      .put("date_evidence", step.dateEvidence)
                      .put("amount_evidence", step.amountEvidence)
                }))
        report.put("events", events).put("retrievals", retrievals)
        reportFile.writeText(report.toString(2))
      }
      save()
      File(directory, "progress.json")
          .writeText(
              JSONObject()
                  .put("id", number)
                  .put("status", "running")
                  .put("question", questions[number - 1])
                  .toString())
      val repository = ChatRepository(context)
      val cache = ChatWorkflowCache(context)
      report.put("numeric_date_order", repository.numericDateOrder.name)
      try {
        val result =
            ChatWorkflowRunner(
                    manager,
                    ChatWorkflowModels.planner,
                    context.filesDir,
                    repository,
                    cache,
                    checkActive = { currentCoroutineContext().ensureActive() },
                    onSteps = { current ->
                      steps = current
                      val finished = current.count { !it.working }
                      if (finished > writtenSteps) {
                        writtenSteps = finished
                        save()
                      }
                    },
                    onProgress = { progress ->
                      val now = SystemClock.elapsedRealtime()
                      events.put(
                          JSONObject().put("offset_ms", now - started).put("message", progress))
                      if (progress.startsWith("Filtering ") &&
                          progress.contains("saved descriptions")) {
                        retrievalStarted = now
                      }
                      report.put("progress", progress)
                      save()
                    },
                    onRetrieval = { query, found ->
                      val finished = SystemClock.elapsedRealtime()
                      retrievals.put(
                          JSONObject()
                              .put("question", query.question)
                              .put("period", query.period)
                              .put("date_basis", query.dateBasis.name)
                              .put("offset_ms", finished - started)
                              .put("observed_elapsed_ms", retrievalStarted?.let { finished - it })
                              .put("library_count", found.libraryCount)
                              .put("relevant_count", found.relevantCount)
                              .put(
                                  "candidates",
                                  JSONArray(
                                      found.candidates.map { candidate ->
                                        JSONObject()
                                            .put("source_id", candidate.entry.id)
                                            .put("image_date", candidate.imageDate?.toString())
                                            .put("taken", candidate.dates.taken?.toString())
                                            .put("added", candidate.dates.added?.toString())
                                      })))
                      retrievalStarted = null
                      save()
                    },
                    today = today)
                .run(questions[number - 1])
        report
            .put("answer", result.answer)
            .put("source_ids", JSONArray(result.sources.map { it.id }))
            .put(
                "parts",
                JSONArray(
                    result.parts.map { part ->
                      JSONObject()
                          .put("question", part.question)
                          .put("period", part.query.period)
                          .put("spending", part.query.spending)
                          .put("terms", JSONArray(part.query.terms))
                          .put("status", part.status)
                          .put("candidates", part.candidates)
                          .put("summary", part.summary)
                          .put(
                              "records",
                              JSONArray(
                                  part.records.map {
                                    JSONObject()
                                        .put("source_id", it.sourceId)
                                        .put("decision", it.verification.decision.name)
                                        .put("verification_evidence", it.verification.evidence)
                                        .put("fields", it.json)
                                  }))
                          .put(
                              "totals",
                              JSONObject(
                                  part.amounts
                                      ?.totals
                                      ?.mapValues { it.value.toPlainString() }
                                      .orEmpty()))
                    }))
            .put(
                "accepted",
                JSONArray(
                    result.amounts
                        ?.amounts
                        ?.map {
                          JSONObject()
                              .put("source_id", it.record.sourceId)
                              .put("amount", it.amount.toPlainString())
                              .put("currency", it.currency)
                              .put("date", it.date?.toString())
                        }
                        .orEmpty()))
            .put(
                "totals",
                JSONObject(
                    result.amounts?.totals?.mapValues { it.value.toPlainString() }.orEmpty()))
            .put("status", "completed")
      } catch (e: Exception) {
        report
            .put("status", "error")
            .put("error", e.toString())
            .put("answer", "I couldn't complete this question: " + (e.message ?: "please retry"))
      } finally {
        repository.close()
        cache.close()
        val elapsed = SystemClock.elapsedRealtime() - started
        val stageTimes =
            steps
                .filter { !it.working }
                .groupBy { it.stage.name }
                .mapValues { (_, values) -> values.sumOf { it.elapsedMs } }
        val searchTime =
            (0 until retrievals.length()).sumOf {
              retrievals.getJSONObject(it).optLong("observed_elapsed_ms")
            }
        report
            .put("elapsed_ms", elapsed)
            .put("finished_wall_ms", System.currentTimeMillis())
            .put("stage_times_ms", JSONObject(stageTimes))
            .put("retrieval_observed_ms", searchTime)
            .put("other_ms", elapsed - stageTimes.values.sum() - searchTime)
        save()
        File(directory, "progress.json")
            .writeText(
                JSONObject()
                    .put("id", number)
                    .put("status", report.getString("status"))
                    .put("elapsed_ms", elapsed)
                    .toString())
      }
    }
    manager.closeAndWait()
    File(directory, "finished.json")
        .writeText(
            JSONObject()
                .put("start_at", startAt)
                .put("end_at", endAt)
                .put("completed_wall_ms", System.currentTimeMillis())
                .toString())
  }
}
