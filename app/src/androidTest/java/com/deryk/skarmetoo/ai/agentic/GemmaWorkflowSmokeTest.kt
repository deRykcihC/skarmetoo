package com.deryk.skarmetoo.ai.agentic

import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.Gemma4Workflow
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GemmaWorkflowSmokeTest {
  @OptIn(ExperimentalApi::class)
  @Test
  fun nativeToolAndJsonRoundTrip() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val runtime = Gemma4Workflow(context)
    val registry = AgenticTools()
    var calls = 0
    val dateTool =
        tool(
            object : OpenApiTool {
              override fun getToolDescriptionJsonString() =
                  registry.declaration("get_current_datetime").toString()

              override fun execute(paramsJsonString: String): String {
                calls++
                return runBlocking {
                  registry
                      .dispatch("get_current_datetime", AgenticContracts.parse(paramsJsonString))
                      .toString()
                }
              }
            })
    val report = JSONObject()
    try {
      ModelInfo.from(runtime.modelFile.absolutePath).use { info ->
        report
            .put("minimum_runtime", info.minRuntimeVersion())
            .put("text_backends", info.supportedBackends(Modality.TEXT).toString())
            .put("vision_backends", info.supportedBackends(Modality.VISION).toString())
      }
      runtime.initialize()
      val reply =
          runtime.generate(
              "You are an on-device assistant. Call get_current_datetime before answering. Use its result, never invent a date.",
              "Call get_current_datetime with timezone Asia/Singapore, then reply with today's date from the result.",
              tools = listOf(dateTool))
      report
          .put("native_tool_calls", calls)
          .put("tool_reply", reply.text)
          .put("tool_elapsed_ms", reply.totalMs)
      assertTrue("Native tool was not requested: ${reply.text}", calls > 0)
      assertTrue(reply.text.contains(registry.datetime("Asia/Singapore").getString("today")))
      val schema =
          AgenticContracts.objectSchema(mapOf("answer" to JSONObject().put("type", "string")))
      val json =
          runtime.generate(
              "Return only the requested JSON object.",
              "Return {\"answer\":\"ready\"}",
              schema = schema.toString())
      report.put("json_reply", json.text).put("decoding_mode", json.decodingMode)
      AgenticContracts.validate(AgenticContracts.parse(json.text), schema)
      val nullableSchema =
          AgenticContracts.objectSchema(
              mapOf(
                  "merchant" to
                      JSONObject().put("type", org.json.JSONArray(listOf("string", "null"))),
                  "amount" to
                      JSONObject().put("type", org.json.JSONArray(listOf("string", "null")))))
      val nullableReply =
          runtime.generate(
              "Return only requested JSON, preserving known facts.",
              "Known facts: merchant is Example Cafe; amount is 5.72. Return an object with merchant and amount as strings. Neither is missing.",
              schema = nullableSchema.toString())
      report.put("nullable_reply", nullableReply.text)
      val nullableResult = AgenticContracts.parse(nullableReply.text)
      AgenticContracts.validate(nullableResult, nullableSchema)
      assertEquals("Example Cafe", nullableResult.getString("merchant"))
      assertEquals("5.72", nullableResult.getString("amount"))
    } catch (e: Throwable) {
      report.put("error", e.stackTraceToString())
      throw e
    } finally {
      File(context.filesDir, "agentic-smoke.json").writeText(report.toString(2))
      runtime.close()
    }
  }
}
