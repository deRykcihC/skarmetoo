package com.deryk.skarmetoo.ai.agentic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.ai.WorkflowBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AgenticUiState(
    val progress: AgenticProgress = AgenticProgress(),
    val busy: Boolean = false,
    val modelReady: Boolean = false,
    val embeddingReady: Boolean = false,
    val indexed: Int = 0,
    val downloadProgress: Float? = null,
    val result: WorkflowResult? = null,
    val error: String? = null,
    val backend: WorkflowBackend = WorkflowBackend.CPU_CPU,
    val sources: List<AgenticImage> = emptyList(),
    val runtimeMode: String = "Not tested"
)

class AgenticViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences =
      application.getSharedPreferences("chat_settings", Application.MODE_PRIVATE)
  private val savedBackend =
      runCatching {
            WorkflowBackend.valueOf(
                preferences.getString("backend", WorkflowBackend.CPU_CPU.name)!!)
          }
          .getOrDefault(WorkflowBackend.CPU_CPU)
  private val pipeline = AgenticPipeline(application)
  private val mutable =
      MutableStateFlow(
          AgenticUiState(
              modelReady = pipeline.gemma.modelFile.exists(),
              embeddingReady = pipeline.embedding.available,
              backend = savedBackend))
  val state = mutable.asStateFlow()
  private var task: Job? = null
  private var cleanup: Job? = null
  private var modelsAcquired = false

  init {
    pipeline.backend = savedBackend
    viewModelScope.launch(Dispatchers.IO) { refresh() }
  }

  private fun refresh() {
    val sources = pipeline.index.images()
    mutable.update { current ->
      current.copy(
          modelReady = pipeline.gemma.modelFile.exists(),
          embeddingReady = pipeline.embedding.available,
          indexed = sources.size,
          sources = sources)
    }
  }

  fun refreshAvailability() {
    viewModelScope.launch(Dispatchers.IO) { refresh() }
  }

  fun onLibraryChanged() {
    cancel()
    mutable.update { it.copy(result = null, sources = emptyList(), indexed = 0) }
    viewModelScope.launch(Dispatchers.IO) {
      task?.join()
      pipeline.clearReport()
      refresh()
    }
  }

  fun selectBackend(backend: WorkflowBackend) {
    if (!mutable.value.busy) {
      pipeline.backend = backend
      preferences.edit().putString("backend", backend.name).apply()
      mutable.value = mutable.value.copy(backend = backend)
    }
  }

  private fun start(
      acquire: suspend () -> Unit,
      usesModels: Boolean = true,
      work: suspend () -> Unit
  ) {
    if (task?.isActive == true) return
    mutable.value = mutable.value.copy(busy = true, error = null, result = null)
    task =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            cleanup?.join()
            acquire()
            if (usesModels) modelsAcquired = true
            work()
            refresh()
          } catch (e: CancellationException) {
            mutable.value =
                mutable.value.copy(progress = AgenticProgress(WorkflowState.CANCELLED, "Cancelled"))
            throw e
          } catch (e: Exception) {
            mutable.value =
                mutable.value.copy(
                    error = e.message,
                    progress = AgenticProgress(WorkflowState.ERROR, e.message ?: "Failed"))
          } finally {
            mutable.value =
                mutable.value.copy(
                    busy = false,
                    runtimeMode =
                        "${pipeline.toolMode}; ${pipeline.lastReport.optString("verification_decoding_mode", pipeline.gemma.decodingMode)}")
          }
        }
  }

  fun download() =
      start({}, usesModels = false) {
        pipeline.embedding.download { mutable.value = mutable.value.copy(downloadProgress = it) }
        mutable.value = mutable.value.copy(downloadProgress = null)
      }

  fun index(acquire: suspend () -> Unit) =
      start(acquire) {
        pipeline.indexImages { mutable.value = mutable.value.copy(progress = it) }
        pipeline.embedding.close()
        mutable.value =
            mutable.value.copy(progress = AgenticProgress(WorkflowState.COMPLETED, "Index ready"))
      }

  fun ask(question: String, acquire: suspend () -> Unit) =
      start(acquire) {
        val result = pipeline.run(question) { mutable.value = mutable.value.copy(progress = it) }
        mutable.value = mutable.value.copy(result = result)
      }

  fun cancel() {
    pipeline.cancel()
    task?.cancel()
  }

  fun leave(release: () -> Unit) {
    val departingTask = task
    val priorCleanup = cleanup
    cancel()
    cleanup =
        viewModelScope.launch(Dispatchers.IO) {
          priorCleanup?.join()
          departingTask?.join()
          try {
            pipeline.close()
            if (modelsAcquired) {
              release()
              modelsAcquired = false
            }
          } catch (e: Exception) {
            mutable.value = mutable.value.copy(error = e.message)
          }
        }
  }

  override fun onCleared() {
    cancel()
    CoroutineScope(Dispatchers.IO).launch {
      runCatching {
        pipeline.close()
        pipeline.index.close()
      }
    }
  }
}
