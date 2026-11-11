package com.deryk.skarmetoo.ai.agentic

import android.content.Context
import com.google.ai.edge.litertlm.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sole new model download. Old text embeddings/model files are never touched. */
class EmbeddingGemma2(private val context: Context) {
  val modelFile = File(context.filesDir, FILENAME)
  private val lock = Mutex()
  private var engine: EmbeddingEngine? = null
  private var integrityStamp: Pair<Long, Long>? = null
  val available
    get() = modelFile.isFile && modelFile.length() == BYTES

  suspend fun download(progress: (Float) -> Unit) =
      withContext(Dispatchers.IO) {
        lock.withLock {
          if (available && sha256(modelFile) == SHA256) return@withLock
          check(!modelFile.exists()) {
            "An unrecognized embedding model occupies this filename; it was preserved."
          }
          val partial = File(context.filesDir, "$FILENAME.part")
          val connection =
              URL(DOWNLOAD_URL + "?download=true&transfer=${System.currentTimeMillis()}")
                  .openConnection() as HttpURLConnection
          connection.connectTimeout = 30_000
          connection.readTimeout = 30_000
          try {
            check(connection.responseCode == 200) { "Download HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
              partial.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                  currentCoroutineContext().ensureActive()
                  val n = input.read(buffer)
                  if (n < 0) break
                  total += n
                  check(total <= BYTES)
                  output.write(buffer, 0, n)
                  progress(total.toFloat() / BYTES)
                }
              }
            }
            check(partial.length() == BYTES && sha256(partial) == SHA256) {
              "Embedding model integrity check failed"
            }
            check(partial.renameTo(modelFile)) { "Cannot finalize model download" }
          } finally {
            connection.disconnect()
          }
        }
      }

  suspend fun initialize() =
      withContext(Dispatchers.IO) {
        lock.withLock {
          if (engine != null) return@withLock
          check(available) { "Download EmbeddingGemma 2 Text + Vision first." }
          val stamp = modelFile.length() to modelFile.lastModified()
          if (integrityStamp != stamp) {
            check(sha256(modelFile) == SHA256) { "Embedding model checksum mismatch" }
            integrityStamp = stamp
          }
          val candidate =
              EmbeddingEngine(
                  EmbeddingEngineConfig(
                      modelPath = modelFile.absolutePath,
                      backend = Backend.CPU(threadCount = 4),
                      visionBackend = Backend.CPU(threadCount = 4),
                      cacheDir = context.cacheDir.absolutePath,
                      maxInputLength = 512,
                      visionTokensPerImage = 70))
          try {
            candidate.initialize()
            engine = candidate
          } catch (e: Throwable) {
            if (candidate.isInitialized()) candidate.close()
            throw e
          }
        }
      }

  suspend fun text(text: String): FloatArray = embed(listOf(InputData.Text(text.take(4096))))

  suspend fun image(bytes: ByteArray): FloatArray = embed(listOf(InputData.Image(bytes)))

  suspend fun images(bytes: List<ByteArray>): List<FloatArray> =
      withContext(Dispatchers.IO) {
        lock.withLock {
          require(bytes.isNotEmpty() && bytes.size <= 2) {
            "Image embedding batches are limited to two"
          }
          currentCoroutineContext().ensureActive()
          val vectors =
              (engine ?: error("Embedding model is not initialized"))
                  .computeEmbeddingBatch(
                      bytes.map { listOf(InputData.Image(it)) },
                      EmbeddingOptions(
                          normalize = true, outputSize = DIMENSIONS, visionTokensPerImage = 70))
                  .map { it.embedding }
          require(
              vectors.size == bytes.size &&
                  vectors.all {
                    it.size == DIMENSIONS &&
                        it.all { value -> value.isFinite() } &&
                        it.any { value -> value != 0f }
                  })
          currentCoroutineContext().ensureActive()
          vectors
        }
      }

  private suspend fun embed(data: List<InputData>): FloatArray =
      withContext(Dispatchers.IO) {
        lock.withLock {
          currentCoroutineContext().ensureActive()
          val vector =
              (engine ?: error("Embedding model is not initialized"))
                  .computeEmbedding(
                      data,
                      EmbeddingOptions(
                          normalize = true, outputSize = DIMENSIONS, visionTokensPerImage = 70))
                  .embedding
          currentCoroutineContext().ensureActive()
          require(
              vector.size == DIMENSIONS &&
                  vector.all { it.isFinite() } &&
                  vector.any { it != 0f }) {
                "Invalid embedding vector"
              }
          vector
        }
      }

  suspend fun close() =
      withContext(Dispatchers.IO) {
        lock.withLock {
          engine?.close()
          engine = null
        }
      }

  companion object {
    const val FILENAME = "embeddinggemma-2-text-vision-440m.litertlm"
    const val BYTES = 387_710_976L
    const val DIMENSIONS = 768
    const val REVISION = "e301f74d5551b0c2641bd5cb4652a76239d5c5f8"
    const val SHA256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"
    const val MODEL_VERSION = "embeddinggemma2-440m:$REVISION:768:normalized:vision70:v1"
    const val DOWNLOAD_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/$REVISION/$FILENAME"

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: java.io.InputStream): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(128 * 1024)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
      require(a.size == b.size && a.isNotEmpty())
      var dot = 0.0
      var aa = 0.0
      var bb = 0.0
      a.indices.forEach {
        dot += a[it] * b[it].toDouble()
        aa += a[it] * a[it].toDouble()
        bb += b[it] * b[it].toDouble()
      }
      require(aa > 0 && bb > 0)
      return (dot / kotlin.math.sqrt(aa * bb)).coerceIn(-1.0, 1.0)
    }
  }
}
