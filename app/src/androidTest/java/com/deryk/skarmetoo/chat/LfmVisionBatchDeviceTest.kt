package com.deryk.skarmetoo.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.VisionBatch
import com.deryk.skarmetoo.ai.withVisionBatch
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native lifetime check using five reads of one fixture, not an accuracy benchmark of five images.
 */
@RunWith(AndroidJUnit4::class)
class LfmVisionBatchDeviceTest {
  @Test
  fun fiveReadsReuseOneAllocationAndCancellationReleasesIt(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = GgufLlmManager.getInstance(context)
    val planner = ChatWorkflowModels.planner
    val vision = ChatWorkflowModels.vision
    val image = File(context.cacheDir, "lfm-chat-test.jpg")
    assumeTrue(
        manager.isModelDownloaded(planner) && manager.isModelDownloaded(vision) && image.isFile)
    val bitmap = requireNotNull(BitmapFactory.decodeFile(image.absolutePath))
    val rows = JSONArray()
    val report = JSONObject().put("reads", rows).put("fixture", "five repetitions of TEST2")
    val reportFile = File(context.cacheDir, "lfm-vision-batch-device.json")
    val entered = CompletableDeferred<Unit>()
    val unloadRequested = CompletableDeferred<Unit>()
    val pendingUnload = launch {
      entered.await()
      unloadRequested.complete(Unit)
      manager.unloadWhenIdle(planner)
    }
    try {
      manager.loadModel(planner)
      val state =
          withTimeout(120000) {
            manager.uiState.first {
              it is GgufLlmManager.LlmState.Ready || it is GgufLlmManager.LlmState.Error
            }
          }
      check(state is GgufLlmManager.LlmState.Ready) { state.toString() }
      var retained: VisionBatch? = null
      manager.withVisionBatch(planner, vision) { batch ->
        retained = batch
        var allocation: Any? = null
        repeat(5) { index ->
          val started = SystemClock.elapsedRealtime()
          val response = batch.generate("What amount is labeled You Paid? Answer briefly.", bitmap)
          val current = requireNotNull(manager.residentModel.value)
          if (index == 0) {
            allocation = current
            entered.complete(Unit)
            unloadRequested.await()
          } else assertSame("The native allocation must be reused", allocation, current)
          assertEquals(vision, current.model)
          assertTrue(response.contains("5.72"))
          assertFalse("A queued unload must wait for the whole batch", pendingUnload.isCompleted)
          assertFalse(File("/proc/self/maps").readText().contains("/${planner.fileName}"))
          rows.put(
              JSONObject()
                  .put("response", response)
                  .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
          reportFile.writeText(report.toString(2))
        }
      }
      pendingUnload.join()
      assertNull(manager.residentModel.value)
      assertFalse(File("/proc/self/maps").readText().contains("/${vision.fileName}"))
      try {
        requireNotNull(retained).generate("Read the image.", bitmap)
        fail("A closed batch must reject inference")
      } catch (_: IllegalStateException) {}
      try {
        manager.withVisionBatch(planner, vision) { batch ->
          batch.generate("What amount is labeled You Paid? Answer briefly.", bitmap)
          throw CancellationException("Stopped")
        }
        fail("Cancellation must propagate")
      } catch (_: CancellationException) {}
      assertNull(manager.residentModel.value)
      assertFalse(manager.isInferenceRunning.value)
      assertEquals(planner, manager.activeModelInfo.value)
      assertFalse(File("/proc/self/maps").readText().contains("/${vision.fileName}"))
      report.put("passed", true)
    } catch (e: Throwable) {
      report.put("passed", false).put("failure", e.message)
      throw e
    } finally {
      pendingUnload.cancel()
      reportFile.writeText(report.toString(2))
      bitmap.recycle()
      manager.closeAndWait()
    }
  }
}
