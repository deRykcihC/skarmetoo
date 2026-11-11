package com.deryk.skarmetoo.chat

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufBridge
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.generateChatResponse
import com.deryk.skarmetoo.ai.withModelUnloaded
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native lifetime/crash regression, independent of retrieval and planner answer quality. */
@RunWith(AndroidJUnit4::class)
class LfmModelMemoryDeviceTest {
  @Test
  fun nativeAllocationsUnloadAndReloadWithoutOverlappingModels(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = GgufLlmManager.getInstance(context)
    val planner = ChatWorkflowModels.planner
    val vision = ChatWorkflowModels.vision
    val image = File(context.cacheDir, "lfm-chat-test.jpg")
    assumeTrue(
        manager.isModelDownloaded(planner) && manager.isModelDownloaded(vision) && image.isFile)
    val reportFile = File(context.cacheDir, "lfm-memory-device.json")
    val rows = JSONArray()
    val report =
        JSONObject()
            .put("snapshots", rows)
            .put("text_context_tokens", GgufLlmManager.TEXT_CONTEXT_TOKENS)
    val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath))
    val modelNames =
        listOf(planner.fileName, planner.mmprojFile, vision.fileName, vision.mmprojFile)
    fun mappedModels() =
        File("/proc/self/maps")
            .readLines()
            .flatMap { line -> modelNames.filter { line.contains("/$it") } }
            .toSet()
    fun snapshot(label: String, response: String = "") {
      rows.put(
          JSONObject()
              .put("label", label)
              .put("resident", manager.residentModel.value?.model?.displayName)
              .put("bridge", manager.residentModel.value?.bridge?.name)
              .put("mapped_models", JSONArray(mappedModels().toList()))
              .put(
                  "rss_kb",
                  Regex("VmRSS:\\s+(\\d+)")
                      .find(File("/proc/self/status").readText())
                      ?.groupValues
                      ?.get(1))
              .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
              .put("response", response))
      reportFile.writeText(report.toString(2))
    }
    val started = SystemClock.elapsedRealtime()
    try {
      manager.loadModel(planner)
      val state =
          withTimeout(120000) {
            manager.uiState.first {
              it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
            }
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      snapshot("selected_without_loading")
      assertNull(manager.residentModel.value)
      assertTrue(mappedModels().isEmpty())

      val schema =
          "{\"type\":\"object\",\"properties\":{\"ready\":{\"type\":\"boolean\"}},\"required\":[\"ready\"],\"additionalProperties\":false}"
      val output =
          manager.generateChatResponse(
              "Return only JSON: {\"ready\":true}",
              expectedModel = planner,
              maxOutputTokens = 32,
              responseSchema = schema)
      snapshot("planner_loaded", output)
      assertTrue(ChatWorkflow.objectResponse(output).getBoolean("ready"))
      assertEquals(GgufBridge.TEXT, manager.residentModel.value?.bridge)
      assertTrue(mappedModels().contains(planner.fileName))
      assertFalse(mappedModels().contains(planner.mmprojFile))

      manager.withModelUnloaded(planner) {
        snapshot("released_for_search")
        assertNull(manager.residentModel.value)
        assertTrue("GGUF mappings must be released during search", mappedModels().isEmpty())
      }
      val read =
          manager.generateChatResponse(
              "What amount is labeled You Paid? Answer briefly.",
              bitmap,
              planner,
              maxOutputTokens = 64,
              visionModel = vision)
      snapshot("vision_loaded", read)
      assertTrue(read.isNotBlank())
      assertEquals(vision, manager.residentModel.value?.model)
      assertEquals(GgufBridge.VISION, manager.residentModel.value?.bridge)
      assertFalse(
          "3B must be unmapped before 450M loads", mappedModels().contains(planner.fileName))
      assertFalse(mappedModels().contains(planner.mmprojFile))

      val again =
          manager.generateChatResponse(
              "Return only JSON: {\"ready\":true}",
              expectedModel = planner,
              maxOutputTokens = 32,
              responseSchema = schema)
      snapshot("planner_reloaded", again)
      assertTrue(ChatWorkflow.objectResponse(again).getBoolean("ready"))
      assertFalse(
          "450M must be unmapped before 3B reloads", mappedModels().contains(vision.fileName))
      assertFalse(mappedModels().contains(vision.mmprojFile))
      withTimeout(30_000) { manager.residentModel.first { it == null } }
      snapshot("automatically_unloaded")
      assertTrue("Idle models must actually unmap their GGUF files", mappedModels().isEmpty())
      val afterIdle =
          manager.generateChatResponse(
              "Return only JSON: {\"ready\":true}",
              expectedModel = planner,
              maxOutputTokens = 32,
              responseSchema = schema)
      snapshot("reloaded_after_idle", afterIdle)
      assertTrue(ChatWorkflow.objectResponse(afterIdle).getBoolean("ready"))
      manager.unloadWhenIdle(planner)
      snapshot("finished_unloaded")
      assertNull(manager.residentModel.value)
      assertTrue("All GGUF files must be unmapped after the request", mappedModels().isEmpty())
      assertEquals(planner, manager.activeModelInfo.value)
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("passed", false).put("failure", e.message)
      throw e
    } finally {
      report.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
      reportFile.writeText(report.toString(2))
      bitmap.recycle()
      manager.closeAndWait()
    }
  }
}
