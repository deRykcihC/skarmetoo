package com.deryk.skarmetoo.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.LFM2_5_3B_MODEL
import com.deryk.skarmetoo.ai.generateChatResponse
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.viewmodel.ModelType
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatTurn(
    val question: String,
    val sources: List<ScreenshotEntry> = emptyList(),
    val answer: String = "",
    val detail: String = "",
    val retrievalNote: String = "",
    val working: Boolean = true,
    val steps: List<ChatWorkflowStep> = emptyList(),
)

class RagChatViewModel(application: Application) : AndroidViewModel(application) {
  private val gguf = GgufLlmManager.getInstance(application)
  val ggufModel = gguf.activeModelInfo
  val ggufState = gguf.uiState
  private val _receiptDateOrder = MutableStateFlow(ChatReceiptDates.get(application))
  val receiptDateOrder = _receiptDateOrder.asStateFlow()

  fun setReceiptDateOrder(order: ChatReceiptDateOrder) {
    if (_busy.value) return
    ChatReceiptDates.set(getApplication(), order)
    _receiptDateOrder.value = order
  }

  fun hasWorkflowModels() =
      gguf.isModelDownloaded(ChatWorkflowModels.planner) &&
          gguf.isModelDownloaded(ChatWorkflowModels.vision)

  private val _testResult = MutableStateFlow<ChatModelTestResult?>(null)
  val testResult = _testResult.asStateFlow()
  private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
  val turns = _turns.asStateFlow()
  private val _busy = MutableStateFlow(false)
  val busy = _busy.asStateFlow()
  private val stopped = AtomicBoolean(false)

  private fun update(transform: (ChatTurn) -> ChatTurn) {
    _turns.value = _turns.value.dropLast(1) + transform(_turns.value.last())
  }

  fun stop() {
    stopped.set(true)
    if (_turns.value.lastOrNull()?.working == true)
        update { it.copy(detail = "Stopping after the current model request finishes…") }
  }

  fun testGguf(prompt: String, imageUri: String?, selectedModel: ModelType?) {
    if (_busy.value || prompt.isBlank()) return
    val model = gguf.activeModelInfo.value
    if (selectedModel != ModelType.GGUF || model == null) {
      _testResult.value =
          ChatModelTestResult(error = "Select and load a GGUF model using Manage models.")
      return
    }
    _busy.value = true
    stopped.set(false)
    _testResult.value = ChatModelTestResult(model = model.displayName, working = true)
    viewModelScope.launch {
      val started = SystemClock.elapsedRealtime()
      var bitmap: Bitmap? = null
      try {
        if (imageUri != null)
            bitmap =
                withContext(Dispatchers.IO) {
                  ImageDecoder.decodeBitmap(
                      ImageDecoder.createSource(
                          getApplication<Application>().contentResolver, Uri.parse(imageUri))) {
                          decoder,
                          info,
                          _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(
                            maxOf(1, (info.size.width * scale).toInt()),
                            maxOf(1, (info.size.height * scale).toInt()))
                      }
                }
        val output =
            gguf.generateChatResponse(prompt, bitmap, model) { token ->
              _testResult.value = _testResult.value?.let { it.copy(output = it.output + token) }
            }
        _testResult.value = _testResult.value?.copy(output = output)
      } catch (e: Exception) {
        _testResult.value = _testResult.value?.copy(error = e.message ?: "GGUF request failed")
      } finally {
        bitmap?.recycle()
        withContext(NonCancellable) { gguf.unloadWhenIdle(model) }
        _testResult.value =
            _testResult.value?.copy(
                working = false, elapsedMs = SystemClock.elapsedRealtime() - started)
        _busy.value = false
      }
    }
  }

  fun clear() {
    if (!_busy.value) _turns.value = emptyList()
  }

  private suspend fun checkStop() {
    currentCoroutineContext().ensureActive()
    if (stopped.get()) throw CancellationException("Stopped")
  }

  fun ask(question: String, selectedModel: ModelType?) {
    if (question.isBlank() || _busy.value) return
    val model = gguf.activeModelInfo.value
    if (selectedModel != ModelType.GGUF ||
        model?.fileName != LFM2_5_3B_MODEL.fileName ||
        !hasWorkflowModels()) {
      _turns.value +=
          ChatTurn(
              question.trim(),
              answer =
                  "Chat needs LFM2.5 VL 3B for prompt generation and LFM2.5 VL 450M for images. Download both with their matching projectors, then activate 3B using Set up chat models.",
              detail = "Model required",
              working = false)
      return
    }
    if (question.length > 2000) {
      _turns.value +=
          ChatTurn(
              question.trim(),
              answer = "Please shorten your question to 2,000 characters or fewer.",
              detail = "Question too long",
              working = false)
      return
    }
    _busy.value = true
    stopped.set(false)
    _turns.value += ChatTurn(question.trim(), detail = "Understanding your question")
    viewModelScope.launch {
      val repository = ChatRepository(getApplication())
      val cache = ChatWorkflowCache(getApplication())
      try {
        val result =
            ChatWorkflowRunner(
                    gguf,
                    model,
                    getApplication<Application>().filesDir,
                    repository,
                    cache,
                    ::checkStop,
                    onSteps = { steps -> update { it.copy(steps = steps) } },
                    onProgress = { progress -> update { it.copy(detail = progress) } },
                    onRetrieval = { query, retrieval ->
                      update {
                        it.copy(
                            sources =
                                (it.sources + retrieval.candidates.map { c -> c.entry })
                                    .distinctBy { s -> s.id },
                            retrievalNote =
                                listOf(
                                        it.retrievalNote,
                                        "${query.question}\n${retrieval.mode} · ${query.period}\n${query.dateBasis.label}\nReceipt dates: ${repository.numericDateOrder.label}\n" +
                                            "${retrieval.libraryCount} saved descriptions → ${retrieval.relevantCount} relevant images → ${retrieval.candidates.size} candidates after date filtering.")
                                    .filter { note -> note.isNotBlank() }
                                    .joinToString("\n\n"))
                      }
                    })
                .run(question.trim())
        checkStop()
        update { it.copy(answer = result.answer, sources = result.sources) }
      } catch (e: CancellationException) {
        update { it.copy(answer = "Stopped. No complete result was produced.") }
      } catch (e: Exception) {
        update {
          it.copy(answer = "I couldn't complete this question: ${e.message ?: "please retry"}")
        }
      } finally {
        withContext(NonCancellable + Dispatchers.IO) {
          runCatching { repository.close() }
          runCatching { cache.close() }
        }
        update { it.copy(working = false, detail = if (stopped.get()) "Stopped" else "Finished") }
        _busy.value = false
      }
    }
  }
}
