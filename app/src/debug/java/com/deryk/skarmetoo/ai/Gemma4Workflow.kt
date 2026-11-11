package com.deryk.skarmetoo.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WorkflowBackend {
  CPU_CPU,
  GPU_GPU,
  CPU_GPU,
  GPU_CPU
}

data class GemmaWorkflowReply(
    val text: String,
    val totalMs: Long,
    val preprocessingMs: Long,
    val firstTokenMs: Long?,
    val metrics: BenchmarkInfo?,
    val decodingMode: String,
)

/** A separate request-owned adapter for the existing Gemma file. Never downloads a Gemma model. */
@OptIn(ExperimentalApi::class)
class Gemma4Workflow(private val context: Context) {
  val modelFile = File(context.filesDir, "gemma-4-E2B-it.litertlm")
  private val mutex = Mutex()
  private var engine: Engine? = null
  private var configuration: WorkflowBackend? = null
  @Volatile private var activeConversation: Conversation? = null
  var initializationMs: Long = 0
    private set

  var decodingMode: String = "Native JSON Schema (unverified)"
    private set

  var lastNativeFailure: String? = null
    private set

  private var grammarAvailable = true
  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var draining: Job? = null

  suspend fun initialize(backend: WorkflowBackend = WorkflowBackend.CPU_CPU) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          check(draining?.isActive != true) {
            "A cancelled native request is still draining. Try again shortly."
          }
          if (engine != null && configuration == backend) return@withLock
          require(modelFile.isFile) { "Download your existing Gemma 4 E2B in Settings first." }
          engine?.close()
          engine = null
          val llm =
              if (backend in setOf(WorkflowBackend.GPU_GPU, WorkflowBackend.GPU_CPU)) Backend.GPU()
              else Backend.CPU(threadCount = 4)
          val vision =
              if (backend in setOf(WorkflowBackend.GPU_GPU, WorkflowBackend.CPU_GPU)) Backend.GPU()
              else Backend.CPU(threadCount = 4)
          ExperimentalFlags.enableBenchmark = true
          val start = SystemClock.elapsedRealtime()
          val candidate =
              Engine(
                  EngineConfig(
                      modelFile.absolutePath,
                      backend = llm,
                      visionBackend = vision,
                      maxNumTokens = 4096,
                      maxNumImages = 1,
                      cacheDir = context.cacheDir.absolutePath))
          try {
            candidate.initialize()
            engine = candidate
            configuration = backend
            initializationMs = SystemClock.elapsedRealtime() - start
            grammarAvailable = true
          } catch (failure: Throwable) {
            if (candidate.isInitialized()) candidate.close()
            configuration = null
            throw failure
          }
        }
      }

  fun cancel() {
    runCatching { activeConversation?.cancelProcess() }
  }

  suspend fun generate(
      system: String,
      prompt: String,
      schema: String? = null,
      bitmap: Bitmap? = null,
      tools: List<ToolProvider> = emptyList(),
      timeoutMs: Long = 120_000,
      forceParserOnly: Boolean = false,
      finalAnswer: Boolean = false,
  ): GemmaWorkflowReply =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val current = engine ?: error("Gemma workflow has not been initialized.")
          check(draining?.isActive != true) { "A cancelled native request is still draining." }
          currentCoroutineContext().ensureActive()
          val start = SystemClock.elapsedRealtime()
          val preStart = start
          val inputs = mutableListOf<Content>()
          if (bitmap != null) {
            val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
            val resized =
                if (scale < 1f)
                    Bitmap.createScaledBitmap(
                        bitmap,
                        maxOf(1, (bitmap.width * scale).toInt()),
                        maxOf(1, (bitmap.height * scale).toInt()),
                        true)
                else bitmap
            try {
              val bytes =
                  ByteArrayOutputStream().use { output ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 95, output)
                    output.toByteArray()
                  }
              inputs += Content.ImageBytes(bytes)
            } finally {
              if (resized !== bitmap) resized.recycle()
            }
          }
          inputs += Content.Text(prompt)
          val preprocess = SystemClock.elapsedRealtime() - preStart
          val useGrammar = schema != null && grammarAvailable && !forceParserOnly
          val conversation =
              current.createConversation(
                  ConversationConfig(
                      systemInstruction = Contents.of(system),
                      tools = tools,
                      automaticToolCalling = true,
                      samplerConfig =
                          if (finalAnswer) SamplerConfig(40, 0.9, 0.3)
                          else if (forceParserOnly) SamplerConfig(40, 0.9, 0.2)
                          else SamplerConfig(1, 1.0, 0.0),
                      maxOutputToken = if (finalAnswer) 1024 else 512,
                      thinkingConfig = ThinkingConfig(enableThinking = false),
                      extraContext = mapOf("enable_thinking" to false),
                      enableResponseFormat = useGrammar))
          activeConversation = conversation
          val completion = CompletableDeferred<String>()
          val buffer = StringBuilder()
          var first: Long? = null
          var deferredCleanup = false
          try {
            conversation.sendMessageAsync(
                Contents.of(inputs),
                object : MessageCallback {
                  override fun onMessage(message: Message) {
                    if (first == null) first = SystemClock.elapsedRealtime() - start
                    buffer.append(message.toString())
                  }

                  override fun onDone() {
                    completion.complete(buffer.toString().trim())
                  }

                  override fun onError(throwable: Throwable) {
                    completion.completeExceptionally(throwable)
                  }
                },
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                responseFormat = if (useGrammar) ResponseFormat.json(schema!!) else null)
            val text = withTimeout(timeoutMs) { completion.await() }
            val nativeMetrics = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            if (schema != null)
                decodingMode =
                    if (useGrammar && tools.isNotEmpty())
                        "Native JSON Schema after tool result; initial tool turn unconstrained"
                    else if (useGrammar) "Native JSON Schema"
                    else "Parser validation with bounded retries"
            GemmaWorkflowReply(
                text,
                SystemClock.elapsedRealtime() - start,
                preprocess,
                first,
                nativeMetrics,
                decodingMode)
          } catch (cancelled: CancellationException) {
            conversation.cancelProcess()
            // Keep the native conversation alive until the cancellation callback has completed.
            val stopped =
                withContext(NonCancellable) {
                  withTimeoutOrNull(30_000) {
                    runCatching { completion.await() }
                    true
                  } ?: false
                }
            if (!stopped) {
              // Never free a native object while its worker may still access it. Quarantine the
              // engine, return cancellation to the UI, and release after the callback arrives.
              deferredCleanup = true
              draining =
                  cleanupScope.launch {
                    runCatching { completion.await() }
                    mutex.withLock {
                      conversation.close()
                      current.close()
                      engine = null
                      configuration = null
                    }
                  }
            }
            throw cancelled
          } catch (failure: Throwable) {
            if (useGrammar) {
              grammarAvailable = false
              lastNativeFailure = failure.message ?: failure.javaClass.simpleName
              decodingMode = "Parser validation with bounded retries"
            }
            throw failure
          } finally {
            activeConversation = null
            if (!deferredCleanup) conversation.close()
          }
        }
      }

  suspend fun close() =
      withContext(Dispatchers.IO) {
        this@Gemma4Workflow.cancel()
        mutex.withLock {
          check(draining?.isActive != true) {
            "Native cancellation is still draining; the engine remains quarantined."
          }
          engine?.close()
          engine = null
          configuration = null
        }
      }
}
