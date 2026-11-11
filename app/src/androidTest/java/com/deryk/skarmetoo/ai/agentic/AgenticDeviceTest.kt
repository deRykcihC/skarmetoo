package com.deryk.skarmetoo.ai.agentic

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.Gemma4Workflow
import com.deryk.skarmetoo.ai.WorkflowBackend
import com.deryk.skarmetoo.ai.generateChatResponse
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val ids = listOf(5L, 4L, 8L, 7L, 6L)
  private val expectedAmounts = listOf("5.72", "7.69", "5.58", "5.36", "7.64")
  private val expectedDates =
      listOf("2026-10-01", "2026-10-02", "2026-09-26", "2026-09-29", "2026-09-30")

  private fun fixture() =
      ScreenshotDatabase(context).use { db ->
        ids.map { id -> db.getAllEntries().single { it.id == id } }
      }

  @Test
  fun backendBenchmark() =
      runBlocking<Unit> {
        val backend =
            WorkflowBackend.valueOf(
                InstrumentationRegistry.getArguments().getString("backend") ?: "CPU_CPU")
        val reportFile = File(context.filesDir, "agentic-benchmark-${backend.name}.jsonl")
        reportFile.writeText("")
        fun save(o: JSONObject) {
          reportFile.appendText(o.toString() + "\n")
        }
        val runtime = Gemma4Workflow(context)
        val schema =
            AgenticContracts.objectSchema(
                linkedMapOf(
                    "merchant" to nullableString(),
                    "transaction_date" to nullableString(),
                    "final_amount" to nullableString(),
                    "currency" to nullableString(),
                    "payment_status" to JSONObject().put("type", "string"),
                    "relevant_details" to JSONObject().put("type", "string")))
        val prompt =
            "Analyze this receipt/payment screenshot. Extract the merchant, transaction date, final amount, currency, payment status and relevant details. Return structured JSON. Do not guess missing values."
        try {
          runtime.initialize(backend)
          save(
              JSONObject()
                  .put("type", "initialization")
                  .put("backend", backend.name)
                  .put("ms", runtime.initializationMs)
                  .put("device", Build.MODEL)
                  .put("soc", Build.HARDWARE))
          val images = fixture()
          val warm = load(images.first().imageUri)
          try {
            runtime.generate(
                "Return the requested structured JSON. Final amount is the actually paid amount after discounts, not subtotal, rewards or balance. RM means MYR. Numeric dates are day-month-year.",
                prompt,
                schema.toString(),
                warm,
                timeoutMs = 180_000)
          } finally {
            warm.recycle()
          }
          images.forEachIndexed { imageIndex, image ->
            repeat(3) { run ->
              val bitmap = load(image.imageUri)
              try {
                val reply =
                    runtime.generate(
                        "Return the requested structured JSON. Final amount is the actually paid amount after discounts, not subtotal, rewards or balance. RM means MYR. Numeric dates are day-month-year.",
                        prompt,
                        schema.toString(),
                        bitmap,
                        timeoutMs = 180_000)
                val output = AgenticContracts.parse(reply.text)
                AgenticContracts.validate(output, schema)
                val metrics = reply.metrics
                val correct =
                    runCatching {
                          BigDecimal(output.getString("final_amount"))
                              .compareTo(BigDecimal(expectedAmounts[imageIndex])) == 0 &&
                              output.getString("transaction_date") == expectedDates[imageIndex] &&
                              output.getString("currency") == "MYR"
                        }
                        .getOrDefault(false)
                save(
                    JSONObject()
                        .put("type", "measurement")
                        .put("backend", backend.name)
                        .put("source_id", image.id)
                        .put("run", run + 1)
                        .put("init_ms", runtime.initializationMs)
                        .put("image_preprocessing_ms", reply.preprocessingMs)
                        .put("ttft_ms", reply.firstTokenMs ?: JSONObject.NULL)
                        .put("total_ms", reply.totalMs)
                        .put("vision_encoding_ms", JSONObject.NULL)
                        .put("prefill_ms", JSONObject.NULL)
                        .put("decode_ms", JSONObject.NULL)
                        .put("peak_memory_bytes", JSONObject.NULL)
                        .put("prefill_tokens", metrics?.lastPrefillTokenCount ?: JSONObject.NULL)
                        .put("output_tokens", metrics?.lastDecodeTokenCount ?: JSONObject.NULL)
                        .put(
                            "decode_tokens_per_second",
                            metrics?.lastDecodeTokensPerSecond ?: JSONObject.NULL)
                        .put("output", output)
                        .put("correct", correct)
                        .put("decoding_mode", reply.decodingMode))
              } catch (e: Exception) {
                save(
                    JSONObject()
                        .put("type", "error")
                        .put("backend", backend.name)
                        .put("source_id", image.id)
                        .put("run", run + 1)
                        .put("error", e.stackTraceToString()))
                throw e
              } finally {
                bitmap.recycle()
              }
            }
          }
        } catch (e: Throwable) {
          save(
              JSONObject()
                  .put("type", "failure")
                  .put("backend", backend.name)
                  .put("error", e.stackTraceToString()))
          throw e
        } finally {
          runtime.close()
        }
      }

  @Test
  fun embeddingIndexAndReload() =
      runBlocking<Unit> {
        val pipeline = AgenticPipeline(context, sourceEntries = { fixture() })
        val report = JSONObject()
        try {
          assertEquals(5, pipeline.indexImages(fixture()))
          val persisted =
              AgenticIndex(context).use { it.images().filter { image -> image.entryId in ids } }
          assertEquals(5, persisted.size)
          assertTrue(persisted.all { it.vector.size == 768 })
          val batch =
              pipeline.embedding.images(
                  fixture().take(2).map { entry ->
                    val bitmap = load(entry.imageUri)
                    try {
                      java.io.ByteArrayOutputStream().use { output ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
                        output.toByteArray()
                      }
                    } finally {
                      bitmap.recycle()
                    }
                  })
          assertEquals(2, batch.size)
          assertTrue(batch.all { it.size == 768 })
          report.put("batch_embeddings", batch.size)
          val query = pipeline.embedding.text("successful payment to Deen Maju restaurant")
          val ranking =
              persisted
                  .map { it to EmbeddingGemma2.cosine(query, it.vector) }
                  .sortedByDescending { it.second }
          report
              .put("dimensions", query.size)
              .put("indexed", persisted.size)
              .put(
                  "ranking",
                  JSONArray(
                      ranking.map { (image, score) ->
                        JSONObject()
                            .put("source_id", image.entryId)
                            .put("score", score)
                            .put("indexing_ms", image.indexingMs)
                      }))
          assertTrue(
              "Deen Maju not in top 3: $report",
              ranking.take(3).any { it.first.entryId in listOf(5L, 7L) })
        } catch (e: Throwable) {
          report.put("error", e.stackTraceToString())
          throw e
        } finally {
          File(context.filesDir, "agentic-embedding-test.json").writeText(report.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }

  @Test
  fun endToEndSingleSentenceAndCache() =
      runBlocking<Unit> {
        val pipeline = AgenticPipeline(context, sourceEntries = { fixture() })
        val reports = JSONArray()
        try {
          val progressFile =
              File(context.filesDir, "agentic-e2e-progress.jsonl").also { it.writeText("") }
          val first =
              pipeline.run("How much did I spend last week?") {
                progressFile.appendText(
                    JSONObject()
                        .put("state", it.state.name)
                        .put("message", it.message)
                        .put("at", System.currentTimeMillis())
                        .toString() + "\n")
              }
          reports.put(pipeline.lastReport)
          assertEquals(WorkflowState.COMPLETED, first.state)
          assertEquals(0, first.calculation.totals.getValue("MYR").compareTo(BigDecimal("26.41")))
          assertEquals(4, first.calculation.transactions.size)
          val second = pipeline.run("How much did I spend last week?") {}
          reports.put(pipeline.lastReport)
          assertEquals(first.calculation.totals, second.calculation.totals)
          assertEquals(5, pipeline.lastReport.getInt("cached_verifications"))
          assertEquals(4, second.sources.size)
        } catch (e: Throwable) {
          reports.put(pipeline.lastReport)
          throw e
        } finally {
          File(context.filesDir, "agentic-e2e-test.json").writeText(reports.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }

  @Test
  fun singleCandidateGeneratedPlan() =
      runBlocking<Unit> {
        val pipeline =
            AgenticPipeline(context, sourceEntries = { fixture().filter { it.id == 5L } })
        try {
          val result =
              pipeline.run("How much did I spend last week?") { progress ->
                File(context.filesDir, "agentic-single-progress.json")
                    .writeText(
                        JSONObject()
                            .put("state", progress.state.name)
                            .put("message", progress.message)
                            .toString())
              }
          assertEquals(WorkflowState.COMPLETED, result.state)
          assertEquals(0, result.calculation.totals.getValue("MYR").compareTo(BigDecimal("5.72")))
          assertEquals(1, result.sources.size)
        } finally {
          File(context.filesDir, "agentic-single-test.json")
              .writeText(pipeline.lastReport.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }

  private fun nullableString() = JSONObject().put("type", JSONArray(listOf("string", "null")))

  @Test
  fun threeCandidatesGeneratedPlan() =
      runBlocking<Unit> {
        val pipeline = AgenticPipeline(context, sourceEntries = { fixture().take(3) })
        try {
          val progressFile =
              File(context.filesDir, "agentic-three-progress.jsonl").also { it.writeText("") }
          val result =
              pipeline.run("What was my total spending in the previous week?") {
                progressFile.appendText(
                    JSONObject()
                        .put("state", it.state.name)
                        .put("message", it.message)
                        .put("at", System.currentTimeMillis())
                        .toString() + "\n")
              }
          assertEquals(WorkflowState.COMPLETED, result.state)
          assertEquals(0, result.calculation.totals.getValue("MYR").compareTo(BigDecimal("13.41")))
          assertEquals(2, result.sources.size)
        } finally {
          File(context.filesDir, "agentic-three-test.json")
              .writeText(pipeline.lastReport.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }

  @Test
  fun merchantReceiptLookup() =
      runBlocking<Unit> {
        val pipeline = AgenticPipeline(context, sourceEntries = { fixture().take(2) })
        try {
          val result = pipeline.run("Find receipts from Deen Maju.") {}
          assertEquals(WorkflowState.COMPLETED, result.state)
          assertEquals(1, result.sources.size)
          assertEquals(
              5L, pipeline.index.images().single { it.id == result.sources.single() }.entryId)
        } finally {
          File(context.filesDir, "agentic-merchant-test.json")
              .writeText(pipeline.lastReport.toString(2))
          pipeline.close()
          pipeline.index.close()
        }
      }

  @Test
  fun originalGemmaReceiptBaseline() =
      runBlocking<Unit> {
        val original = com.deryk.skarmetoo.ai.LlmManager(context)
        val report = JSONObject()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
          original.initializeModel(
              File(context.filesDir, "gemma-4-E2B-it.litertlm").absolutePath, isGemma4 = true)
          kotlinx.coroutines.withTimeout(120_000) {
            while (original.uiState.value != com.deryk.skarmetoo.ai.LlmManager.LlmState.Ready) {
              val state = original.uiState.value
              check(state !is com.deryk.skarmetoo.ai.LlmManager.LlmState.Error) { state.toString() }
              kotlinx.coroutines.delay(100)
            }
          }
          report.put("initialization_ms", android.os.SystemClock.elapsedRealtime() - started)
          val bitmap = load(fixture().first().imageUri)
          try {
            val start = android.os.SystemClock.elapsedRealtime()
            val output =
                original.generateChatResponse(
                    "Analyze this receipt/payment screenshot. Extract the merchant, transaction date, final amount, currency, payment status and relevant details. Return structured JSON. Do not guess missing values.",
                    bitmap)
            report
                .put("inference_ms", android.os.SystemClock.elapsedRealtime() - start)
                .put("output", output)
          } finally {
            bitmap.recycle()
          }
        } finally {
          File(context.filesDir, "agentic-original-baseline.json").writeText(report.toString(2))
          original.closeAndWait()
        }
      }

  @Test
  fun cancelNativeGenerationThenReuse() =
      runBlocking<Unit> {
        val runtime = Gemma4Workflow(context)
        val report = JSONObject()
        val start = android.os.SystemClock.elapsedRealtime()
        try {
          runtime.initialize()
          val bitmap = load(fixture().first().imageUri)
          try {
            val job = launch {
              runtime.generate(
                  "Describe the image in detail.", "Explain everything visible.", bitmap = bitmap)
            }
            kotlinx.coroutines.delay(1000)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
          } finally {
            bitmap.recycle()
          }
          val schema =
              AgenticContracts.objectSchema(mapOf("answer" to JSONObject().put("type", "string")))
          val reply =
              runtime.generate(
                  "Return the requested JSON.", "Set answer to ready.", schema.toString())
          assertEquals("ready", AgenticContracts.parse(reply.text).getString("answer"))
          report.put("cancelled", true).put("reuse_reply", reply.text)
        } finally {
          report.put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
          File(context.filesDir, "agentic-cancellation-test.json").writeText(report.toString(2))
          runtime.close()
        }
      }

  private fun load(uri: String) =
      ImageDecoder.decodeBitmap(
          ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val factor = minOf(1.0, 1536.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * factor).toInt(), (info.size.height * factor).toInt())
          }
}
