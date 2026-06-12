package com.deryk.skarmetoo.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EmbeddingGemma(private val context: Context) {

  private val embedMutex = Mutex()
  private var embeddingModel: GemmaEmbeddingModel? = null
  private var modelFile: File? = null
  private var lastError: String? = null

  companion object {
    private const val TAG = "EmbeddingGemma"
    private const val MAX_TEXT_CHARS = 4096

    const val DEFAULT_MODEL_NAME = "embeddinggemma-300m.tflite"
    const val SENTENCEPIECE_MODEL_NAME = "sentencepiece.model"
    const val MODEL_REPO_URL = "https://huggingface.co/litert-community/embeddinggemma-300m"
    const val MODEL_DISPLAY_NAME = "EmbeddingGemma"
    const val MODEL_FILE_NAME = "embeddinggemma-300M_seq512_mixed-precision.tflite"
    const val MODEL_DOWNLOAD_URL = "$MODEL_REPO_URL/resolve/main/$MODEL_FILE_NAME?download=true"
    const val SENTENCEPIECE_FILE_NAME = SENTENCEPIECE_MODEL_NAME
    const val SENTENCEPIECE_DOWNLOAD_URL =
        "$MODEL_REPO_URL/resolve/main/$SENTENCEPIECE_FILE_NAME?download=true"

    private val MODEL_NAME_CANDIDATES =
        listOf(
            DEFAULT_MODEL_NAME,
            MODEL_FILE_NAME,
            "embeddinggemma.tflite",
            "embedding_gemma.tflite",
            "embeddinggemma-300m-q8.tflite",
            "embeddinggemma-300m_seq256.tflite",
            "embeddinggemma-300m_seq512.tflite",
        )

    fun defaultModelFile(context: Context): File? {
      val filesDir = context.filesDir
      MODEL_NAME_CANDIDATES.map { File(filesDir, it) }
          .firstOrNull { it.exists() }
          ?.let {
            return it
          }
      return filesDir.listFiles()?.firstOrNull { file ->
        file.isFile &&
            file.extension.equals("tflite", ignoreCase = true) &&
            file.name.contains("embeddinggemma", ignoreCase = true)
      }
    }

    fun hasRequiredFiles(context: Context): Boolean =
        defaultModelFile(context)?.exists() == true &&
            File(context.filesDir, SENTENCEPIECE_MODEL_NAME).exists()

    fun migrateLegacyTokenizerName(context: Context) {
      val legacyFile = File(context.filesDir, "embeddinggemma-sentencepiece.model")
      val expectedFile = File(context.filesDir, SENTENCEPIECE_MODEL_NAME)
      if (expectedFile.exists() || !legacyFile.exists()) return

      try {
        legacyFile.copyTo(expectedFile, overwrite = true)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to copy legacy tokenizer file into expected name", e)
      }
    }

    suspend fun downloadRequiredFiles(
        context: Context,
        cookies: String?,
        onProgress: (Float) -> Unit = {},
    ) =
        withContext(Dispatchers.IO) {
          val files =
              listOf(
                  DownloadSpec(
                      url = MODEL_DOWNLOAD_URL,
                      outputName = DEFAULT_MODEL_NAME,
                      progressStart = 0f,
                      progressEnd = 0.98f,
                  ),
                  DownloadSpec(
                      url = SENTENCEPIECE_DOWNLOAD_URL,
                      outputName = SENTENCEPIECE_MODEL_NAME,
                      progressStart = 0.98f,
                      progressEnd = 1f,
                  ),
              )

          for (file in files) {
            downloadFile(context, file, cookies, onProgress)
          }
          onProgress(1f)
        }

    private data class DownloadSpec(
        val url: String,
        val outputName: String,
        val progressStart: Float,
        val progressEnd: Float,
    )

    private fun downloadFile(
        context: Context,
        spec: DownloadSpec,
        cookies: String?,
        onProgress: (Float) -> Unit,
    ) {
      val destination = File(context.filesDir, spec.outputName)
      val tmpFile = File(context.filesDir, "${spec.outputName}.tmp")
      var connection: HttpURLConnection? = null

      try {
        var currentUrl = spec.url
        var redirects = 0

        while (true) {
          connection = URL(currentUrl).openConnection() as HttpURLConnection
          connection.instanceFollowRedirects = false
          connection.setRequestProperty("Accept-Encoding", "identity")
          connection.connectTimeout = 60000
          connection.readTimeout = 60000

          if (currentUrl.contains("huggingface.co") && !cookies.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookies)
          }

          connection.connect()
          val status = connection.responseCode
          if (status in 300..399) {
            currentUrl =
                connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect missing Location header")
            redirects++
            if (redirects > 10) throw IllegalStateException("Too many redirects")
            connection.disconnect()
            continue
          }

          if (status != HttpURLConnection.HTTP_OK) {
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
              throw IllegalStateException(
                  "Unauthorized: sign in to Hugging Face and accept the EmbeddingGemma license.")
            }
            throw IllegalStateException(
                "Server returned HTTP $status ${connection.responseMessage}")
          }
          break
        }

        val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        BufferedInputStream(connection.inputStream).use { input ->
          FileOutputStream(tmpFile).use { output ->
            val buffer = ByteArray(8192)
            var downloaded = 0L
            var lastProgressUpdate = 0L

            while (true) {
              val read = input.read(buffer)
              if (read == -1) break
              output.write(buffer, 0, read)
              downloaded += read

              if (contentLength > 0L) {
                val now = SystemClock.uptimeMillis()
                if (now - lastProgressUpdate > 80L) {
                  val fileProgress = downloaded.toFloat() / contentLength.toFloat()
                  onProgress(
                      spec.progressStart +
                          (spec.progressEnd - spec.progressStart) * fileProgress.coerceIn(0f, 1f))
                  lastProgressUpdate = now
                }
              }
            }
          }
        }

        if (destination.exists()) destination.delete()
        if (!tmpFile.renameTo(destination)) {
          throw IllegalStateException("Failed to move downloaded file into place")
        }
      } catch (e: Exception) {
        if (tmpFile.exists()) tmpFile.delete()
        throw e
      } finally {
        connection?.disconnect()
      }
    }

    fun buildSearchText(entry: ScreenshotEntry): String =
        buildString {
              if (entry.summary.isNotBlank()) append(entry.summary.trim())
              val tags = entry.getTagList()
              if (tags.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(tags.joinToString(separator = ", "))
              }
              if (entry.note.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(entry.note.trim())
              }
            }
            .trim()

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
      val size = minOf(left.size, right.size)
      if (size == 0) return 0f

      var dot = 0f
      var leftNorm = 0f
      var rightNorm = 0f
      for (i in 0 until size) {
        dot += left[i] * right[i]
        leftNorm += left[i] * left[i]
        rightNorm += right[i] * right[i]
      }

      if (leftNorm == 0f || rightNorm == 0f) return 0f
      return dot / kotlin.math.sqrt(leftNorm * rightNorm)
    }
  }

  suspend fun initialize(customModelFile: File? = null): Boolean =
      withContext(Dispatchers.IO) {
        try {
          close()
          migrateLegacyTokenizerName(context)

          val fileToLoad = customModelFile ?: defaultModelFile(context)
          if (fileToLoad == null || !fileToLoad.exists()) {
            lastError = "EmbeddingGemma model not found in ${context.filesDir.absolutePath}"
            Log.d(TAG, lastError.orEmpty())
            return@withContext false
          }

          val tokenizerFile = File(context.filesDir, SENTENCEPIECE_MODEL_NAME)
          if (!tokenizerFile.exists()) {
            lastError = "EmbeddingGemma tokenizer not found: ${tokenizerFile.absolutePath}"
            Log.d(TAG, lastError.orEmpty())
            return@withContext false
          }

          embeddingModel =
              GemmaEmbeddingModel(
                  fileToLoad.absolutePath,
                  tokenizerFile.absolutePath,
                  false,
              )
          modelFile = fileToLoad
          Log.i(TAG, "EmbeddingGemma initialized from ${fileToLoad.absolutePath}")
          lastError = null
          true
        } catch (e: Exception) {
          lastError = e.localizedMessage ?: e.javaClass.simpleName
          Log.e(TAG, "Failed to initialize EmbeddingGemma", e)
          close()
          false
        }
      }

  suspend fun embed(text: String): FloatArray? = embedQuery(text)

  suspend fun embedQuery(text: String): FloatArray? =
      embed(text, EmbedData.TaskType.RETRIEVAL_QUERY, isQuery = true)

  suspend fun embedDocument(text: String): FloatArray? =
      embed(text, EmbedData.TaskType.RETRIEVAL_DOCUMENT, isQuery = false)

  private suspend fun embed(
      text: String,
      taskType: EmbedData.TaskType,
      isQuery: Boolean,
  ): FloatArray? =
      withContext(Dispatchers.Default) {
        val cleanedText = text.trim().take(MAX_TEXT_CHARS)
        if (cleanedText.isBlank()) return@withContext null

        val currentModel =
            embeddingModel
                ?: run {
                  Log.d(TAG, "EmbeddingGemma is not initialized")
                  return@withContext null
                }

        embedMutex.withLock {
          try {
            val start = SystemClock.uptimeMillis()
            val request =
                EmbeddingRequest.create(
                    listOf(EmbedData.create(cleanedText, taskType, isQuery)),
                )
            val vector =
                currentModel.getEmbeddings(request).get().map { it.toFloat() }.toFloatArray()
            if (vector.isEmpty()) {
              Log.w(TAG, "EmbeddingGemma returned an empty vector")
              return@withLock null
            }

            Log.d(TAG, "Generated text embedding in ${SystemClock.uptimeMillis() - start}ms")
            vector
          } catch (e: Exception) {
            lastError = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "Failed to generate EmbeddingGemma vector", e)
            null
          }
        }
      }

  suspend fun scoreEntries(
      query: String,
      entries: List<ScreenshotEntry>,
      limit: Int = 200,
  ): List<Pair<ScreenshotEntry, Float>> =
      withContext(Dispatchers.Default) {
        val queryVector = embed(query) ?: return@withContext emptyList()
        val scoredEntries = mutableListOf<Pair<ScreenshotEntry, Float>>()

        for (entry in entries) {
          val text = buildSearchText(entry)
          if (text.isBlank()) continue

          val entryVector = embedDocument(text) ?: continue
          scoredEntries.add(entry to cosineSimilarity(queryVector, entryVector))
        }

        scoredEntries.sortedByDescending { it.second }.take(limit)
      }

  fun isInitialized(): Boolean = embeddingModel != null

  fun getLoadedModelPath(): String? = modelFile?.absolutePath

  fun getLastError(): String? = lastError

  fun close() {
    embeddingModel = null
    modelFile = null
  }
}
