package com.deryk.skarmetoo.chat

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.GgufModelInfo
import com.deryk.skarmetoo.ai.generateChatResponse
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic native replay; a completed recorder test is not an accuracy assertion. */
@RunWith(AndroidJUnit4::class)
class ChatVisionReplacementDeviceTest {
  @Test
  fun parseSavedResponses() {
    // A separate post-run parser audit; no models are loaded and no outputs are repaired.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "chat-vision-replacement")
    val rows = JSONArray()
    root
        .listFiles()
        .orEmpty()
        .filter { it.extension == "json" }
        .forEach { file ->
          val report = JSONObject(file.readText())
          val calls = report.optJSONArray("calls") ?: return@forEach
          for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            if (call.optString("status") != "completed") continue
            val row =
                JSONObject()
                    .put("report_file", file.name)
                    .put("case_id", call.getInt("case_id"))
                    .put("stage", call.getString("stage"))
            try {
              val parsed = ChatWorkflow.objectResponse(call.getString("response"))
              row.put("parsed", parsed)
              row.put(
                  "unit_currency",
                  ChatWorkflowFacts.currency(parsed.optString("unit")) ?: JSONObject.NULL,
              )
            } catch (e: Exception) {
              row.put("error", e.toString())
            }
            rows.put(row)
          }
        }
    File(root, "app-parser-audit.json")
        .writeText(JSONObject().put("native_inference_calls", 0).put("rows", rows).toString(2))
  }

  @Test
  fun replayCapturedVisualStages(): Unit = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val args = InstrumentationRegistry.getArguments()
    val id = requireNotNull(args.getString("candidateId"))
    val mode = args.getString("promptMode") ?: "raw"
    require(mode in setOf("raw", "yaml", "yaml_question"))
    val root = File(context.cacheDir, "chat-vision-replacement")
    val config = JSONObject(File(root, "phone-config.json").readText())
    val candidates = config.getJSONArray("candidates")
    val entry =
        (0 until candidates.length())
            .map { candidates.getJSONObject(it) }
            .single { it.getString("id") == id }
    val reportFile = File(root, "$id-$mode.json")
    check(!reportFile.exists()) { "Refusing to overwrite an earlier candidate attempt" }
    val weights = entry.getJSONObject("weights")
    val projector = entry.getJSONObject("projector")
    val model =
        GgufModelInfo(
            displayName = entry.getString("display_name"),
            fileName = entry.optString("phone_weights_name", weights.getString("name")),
            hfRepo = entry.getString("repo"),
            hfFile = weights.getString("name"),
            sizeMb = weights.getLong("bytes") / 1_000_000,
            description = "Vision replacement diagnostic",
            isVision = true,
            mmprojFile = entry.optString("phone_projector_name", projector.getString("name")),
            mmprojSizeMb = projector.getLong("bytes") / 1_000_000,
            chatTemplate = "",
        )
    val manager = GgufLlmManager.getInstance(context)
    check(manager.isModelDownloaded(model)) { "Verified model files are missing" }
    val calls = JSONArray()
    val report =
        JSONObject()
            .put("candidate_id", id)
            .put("mode", mode)
            .put("model", entry)
            .put("status", "running")
            .put("cache", "no workflow cache; direct native calls")
            .put("calls", calls)
            .put("started_wall_ms", System.currentTimeMillis())
    fun save() {
      val temp = File(root, "$id-$mode.json.tmp")
      temp.writeText(report.toString(2))
      check(temp.renameTo(reportFile))
    }
    val started = SystemClock.elapsedRealtime()
    try {
      manager.loadModel(model)
      val state =
          manager.uiState.first {
            it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      val cases = config.getJSONArray("cases")
      for (index in 0 until cases.length()) {
        val case = cases.getJSONObject(index)
        val bitmap =
            requireNotNull(BitmapFactory.decodeFile(File(root, case.getString("image_file")).path))
        try {
          val inputs = case.getJSONArray("calls")
          for (stepIndex in 0 until inputs.length()) {
            val input = inputs.getJSONObject(stepIndex)
            val stage = input.getString("stage")
            val raw = input.getString("prompt")
            val wrapped =
                (if (mode != "raw") yamlPrompt(stage, raw, mode == "yaml_question")
                else
                    entry
                        .optString(
                            "raw_prompt_template",
                            "<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n",
                        )
                        .replace("{prompt}", raw)) + entry.optString("assistant_prefill", "")
            val row =
                JSONObject()
                    .put("case_id", case.getInt("id"))
                    .put("question_id", case.getInt("original_question_id"))
                    .put("source_id", case.getInt("source_id"))
                    .put("stage", stage)
                    .put("original_prompt", raw)
                    .put("submitted_prompt", wrapped)
                    .put("status", "running")
                    .put("response", "")
            calls.put(row)
            report.put("progress", "Case ${index + 1}/${cases.length()} $stage")
            save()
            val callStarted = SystemClock.elapsedRealtime()
            var lastSave = callStarted
            val stream = StringBuilder()
            try {
              val response =
                  manager.generateChatResponse(
                      prompt = wrapped,
                      bitmap = bitmap,
                      expectedModel = model,
                      maxOutputTokens = 256,
                      onToken = { token ->
                        stream.append(token)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSave > 1500) {
                          row.put("response", stream.toString())
                          row.put("partial_elapsed_ms", now - callStarted)
                          lastSave = now
                          save()
                        }
                      },
                  )
              row.put("status", "completed").put("response", response)
            } catch (e: Exception) {
              row.put("status", "error").put("error", e.toString())
            } finally {
              row.put("elapsed_ms", SystemClock.elapsedRealtime() - callStarted)
              row.put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
              save()
            }
          }
        } finally {
          bitmap.recycle()
        }
      }
      report.put("status", "completed")
    } catch (e: Exception) {
      report.put("status", "error").put("error", e.toString())
    } finally {
      manager.closeAndWait()
      report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
      report.put("finished_wall_ms", System.currentTimeMillis())
      save()
    }
  }

  private fun yamlPrompt(stage: String, original: String, inlineQuestion: Boolean): String {
    // Format adaptation follows the publisher's YAML-in-system API. The LFM-generated
    // question and original app observations remain unchanged; no expected answers are added.
    val question = original.substringAfter("INPUT:\n").substringBefore("\n\n").trim()
    val schema =
        if (stage == "VERIFY")
            (if (inlineQuestion)
                "decision: $question Select from YES, NO, MAYBE. YES requires visible proof of the requested fact; NO is contradiction or unrelated content; MAYBE is insufficient evidence.\n"
            else
                "decision: Answer the question in INPUT using this image; select from YES, NO, MAYBE.\n") +
                "evidence: Quote decisive visible evidence; preserve any restriction in the requested question."
        else
            (if (inlineQuestion)
                "value: Extract only the requested quantity for this model-generated question: $question Unknown is null.\n"
            else "value: The fact requested in INPUT, numeric for a quantity; unknown is null.\n") +
                "unit: Visible currency or measurement unit for value; unknown is null.\n" +
                "event_date: Visible event date exactly as printed; unknown is null.\n" +
                "record_id: Visible unique transaction reference; unknown is null.\n" +
                "evidence: Quote visible text supporting the requested fact; unknown is null."
    val system =
        if (inlineQuestion)
            "Extract the following from the image:\n\n$schema\nRespond with only a JSON object. Do not include any text outside the JSON."
        else schema
    // The inline variant moves the exact generated request into the schema and removes
    // the app's empty JSON exemplar. Keep the original observations as untrusted data.
    val user =
        if (inlineQuestion) {
          val observations =
              if (original.contains("FIRST-PASS EVIDENCE"))
                  original
                      .substringAfter("\n\nFIRST-PASS EVIDENCE")
                      .substringBefore("\n\nRESPONSE FORMAT:")
                      .let { "FIRST-PASS EVIDENCE$it" }
              else ""
          observations.ifBlank { "Use only the attached image." }
        } else original
    return "<|im_start|>system\n$system<|im_end|>\n" +
        "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
  }
}
