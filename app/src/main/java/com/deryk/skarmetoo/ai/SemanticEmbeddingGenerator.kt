package com.deryk.skarmetoo.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

class SemanticEmbeddingGenerator(private val context: Context) {

  private var interpreter: Interpreter? = null
  private var modelFile: File? = null

  companion object {
    private const val TAG = "SemanticEmbeddingGen"
    const val DEFAULT_MODEL_NAME = "mobilenet_v3_features.tflite"
  }

  /**
   * Initializes the TFLite interpreter asynchronously with a local model file. Uses CPU
   * multi-threading (4 threads) for exceptionally stable, cross-architecture runtimes.
   */
  suspend fun initialize(customModelFile: File? = null): Boolean =
      withContext(Dispatchers.IO) {
        try {
          // Close existing resources if re-initializing
          close()

          val fileToLoad = customModelFile ?: File(context.filesDir, DEFAULT_MODEL_NAME)
          if (!fileToLoad.exists()) {
            Log.w(TAG, "Embedding model file not found at: ${fileToLoad.absolutePath}")
            return@withContext false
          }

          modelFile = fileToLoad
          val options = Interpreter.Options().apply { setNumThreads(4) }

          val modelBuffer = loadModelFile(fileToLoad)
          val newInterpreter = Interpreter(modelBuffer, options)
          interpreter = newInterpreter

          // Warm up model
          val inputTensor = newInterpreter.getInputTensor(0)
          val outputTensor = newInterpreter.getOutputTensor(0)
          val inShape = inputTensor?.shape() ?: intArrayOf(1, 224, 224, 3)
          val outShape = outputTensor?.shape() ?: intArrayOf(1, 1024)

          Log.i(
              TAG,
              "Interpreter loaded. Input shape: ${inShape.joinToString()}, Output shape: ${outShape.joinToString()}")
          return@withContext true
        } catch (e: LinkageError) {
         Log.e(TAG, "Failed to link the visual embedding runtime", e)
          close()
          return@withContext false
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load visual embedding model", e)
          close()
          return@withContext false
        }
      }

  /**
   * Preprocesses the Bitmap, runs visual feature extraction, L2-normalizes the output, and returns
   * the final visual embedding vector (FloatArray).
   */
  suspend fun generateEmbedding(bitmap: Bitmap): FloatArray? =
      withContext(Dispatchers.Default) {
        val currentInterpreter =
            interpreter
                ?: run {
                  Log.e(TAG, "Cannot generate embedding: Interpreter is not initialized.")
                  return@withContext null
                }

        val startTime = SystemClock.uptimeMillis()

        try {
          val inputTensor =
              currentInterpreter.getInputTensor(0) ?: throw Exception("Input tensor is null")
          val outputTensor =
              currentInterpreter.getOutputTensor(0) ?: throw Exception("Output tensor is null")

          val inputShape = inputTensor.shape()
          val inputHeight: Int =
              if (inputShape != null && inputShape.size > 1) inputShape[1] else 224
          val inputWidth: Int =
              if (inputShape != null && inputShape.size > 2) inputShape[2] else 224

          val outputShape = outputTensor.shape()
          val embeddingDim: Int =
              if (outputShape != null && outputShape.size > 1) outputShape[1] else 1024

          // 1. Resize and crop Bitmap to model input size
          val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
          val byteBuffer = convertBitmapToByteBuffer(resized, inputWidth, inputHeight)
          if (resized != bitmap) resized.recycle()

          // 2. Prepare output FloatArray and ByteBuffer
          val outputBuffer =
              ByteBuffer.allocateDirect(1 * embeddingDim * 4).apply {
                order(ByteOrder.nativeOrder())
              }

          // 3. Run Inference synchronously on background thread
          currentInterpreter.run(byteBuffer, outputBuffer)

          // 4. Extract FloatArray
          outputBuffer.rewind()
          val rawEmbedding = FloatArray(embeddingDim)
          outputBuffer.asFloatBuffer().get(rawEmbedding)

          // 5. Apply L2 normalization to simplify similarity to a simple dot product
          val normalizedEmbedding = l2Normalize(rawEmbedding)

          val duration = SystemClock.uptimeMillis() - startTime
          Log.d(TAG, "Visual embedding generated in ${duration}ms, dimensions = $embeddingDim")
          return@withContext normalizedEmbedding
        } catch (e: Exception) {
          Log.e(TAG, "Error generating visual embedding", e)
          return@withContext null
        }
      }

  /**
   * Converts a resized Bitmap to a normalized float pixel ByteBuffer. Scales color channels to
   * [0, 1] range (or standard ImageNet mean/std normalization).
   */
  private fun convertBitmapToByteBuffer(bitmap: Bitmap, width: Int, height: Int): ByteBuffer {
    val byteBuffer =
        ByteBuffer.allocateDirect(1 * width * height * 3 * 4).apply {
          order(ByteOrder.nativeOrder())
        }

    val intValues = IntArray(width * height)
    bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    // MobileNet V3 / standard model expects float values in range [0, 1] or normalized.
    // We'll normalize to [0, 1] which is the standard default for Mobilenet feature extractors.
    for (pixelValue in intValues) {
      val r = ((pixelValue shr 16) and 0xFF) / 255.0f
      val g = ((pixelValue shr 8) and 0xFF) / 255.0f
      val b = (pixelValue and 0xFF) / 255.0f

      byteBuffer.putFloat(r)
      byteBuffer.putFloat(g)
      byteBuffer.putFloat(b)
    }
    return byteBuffer
  }

  /** Normalizes a float vector such that its Euclidean length (L2 norm) is 1.0. */
  private fun l2Normalize(vector: FloatArray): FloatArray {
    var sqSum = 0.0f
    for (x in vector) sqSum += x * x
    val magnitude = kotlin.math.sqrt(sqSum)
    if (magnitude == 0.0f) return vector

    val normalized = FloatArray(vector.size)
    for (i in vector.indices) {
      normalized[i] = vector[i] / magnitude
    }
    return normalized
  }

  private fun loadModelFile(file: File): ByteBuffer {
    val inputStream = FileInputStream(file)
    val fileChannel = inputStream.channel
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
  }

  fun isInitialized(): Boolean = interpreter != null

  fun getLoadedModelPath(): String? = modelFile?.absolutePath

  /** Frees native memory. */
  fun close() {
    try {
      interpreter?.close()
      interpreter = null
    } catch (e: Exception) {
      Log.e(TAG, "Error closing interpreter", e)
    }
    modelFile = null
  }
}
