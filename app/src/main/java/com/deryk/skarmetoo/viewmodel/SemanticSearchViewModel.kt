package com.deryk.skarmetoo.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ai.SemanticEmbeddingGenerator
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotVectorDatabase
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UnindexedDebugEntry(
    val entry: ScreenshotEntry,
    val reason: String,
    val detail: String = ""
)

class SemanticSearchViewModel(application: Application) : AndroidViewModel(application) {

  private val vectorDb = ScreenshotVectorDatabase(application)
  private val embeddingGen = SemanticEmbeddingGenerator(application)

  private val _isModelReady = MutableStateFlow(false)
  val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

  private val _isDownloading = MutableStateFlow(false)
  val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _indexingProgress = MutableStateFlow<Pair<Int, Int>?>(null) // (completed, total)
  val indexingProgress: StateFlow<Pair<Int, Int>?> = _indexingProgress.asStateFlow()

  private val _isIndexing = MutableStateFlow(false)
  val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

  private val _indexedCount = MutableStateFlow(0)
  val indexedCount: StateFlow<Int> = _indexedCount.asStateFlow()

  private val _targetScreenshot = MutableStateFlow<ScreenshotEntry?>(null)
  val targetScreenshot: StateFlow<ScreenshotEntry?> = _targetScreenshot.asStateFlow()

  // Similar screenshots list paired with match score % (0.0 to 1.0)
  private val _similarScreenshots =
      MutableStateFlow<List<Pair<ScreenshotEntry, Float>>>(emptyList())
  val similarScreenshots: StateFlow<List<Pair<ScreenshotEntry, Float>>> =
      _similarScreenshots.asStateFlow()

  private val _infoMessage = MutableStateFlow<String?>(null)
  val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

  private val _brokenEntries = MutableStateFlow<List<ScreenshotEntry>>(emptyList())
  val brokenEntries: StateFlow<List<ScreenshotEntry>> = _brokenEntries.asStateFlow()

  private val _isCheckingBroken = MutableStateFlow(false)
  val isCheckingBroken: StateFlow<Boolean> = _isCheckingBroken.asStateFlow()

  private val _unindexedDebugEntries = MutableStateFlow<List<UnindexedDebugEntry>>(emptyList())
  val unindexedDebugEntries: StateFlow<List<UnindexedDebugEntry>> =
      _unindexedDebugEntries.asStateFlow()

  private val _isInspectingUnindexed = MutableStateFlow(false)
  val isInspectingUnindexed: StateFlow<Boolean> = _isInspectingUnindexed.asStateFlow()

  private var indexingJob: Job? = null

  companion object {
    private const val TAG = "SemanticSearchVM"
    private const val MODEL_DOWNLOAD_URL =
        "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite"
  }

  init {
    checkModelPresenceAndInitialize()
    refreshIndexedCount()
  }

  /** Re-queries the local database to update the cached count of stored visual embeddings. */
  fun refreshIndexedCount() {
    viewModelScope.launch(Dispatchers.IO) {
      _indexedCount.value = vectorDb.getStoredEmbeddingCount()
    }
  }

  /** Checks if the default LiteRT model is already on device, and initializes it. */
  fun checkModelPresenceAndInitialize() {
    viewModelScope.launch(Dispatchers.IO) {
      val modelFile =
          File(
              getApplication<Application>().filesDir, SemanticEmbeddingGenerator.DEFAULT_MODEL_NAME)
      if (modelFile.exists()) {
        val initialized = embeddingGen.initialize(modelFile)
        _isModelReady.value = initialized
        if (initialized) {
          Log.d(TAG, "Embedding model successfully initialized from: ${modelFile.absolutePath}")
        }
      } else {
        _isModelReady.value = false
        Log.d(TAG, "Embedding model not present yet. Requires downloading.")
      }
    }
  }

  /** Downloads the tiny 5MB MobileNet model from Google APIs and initializes it. */
  fun downloadModel() {
    if (_isDownloading.value) return
    _isDownloading.value = true
    _downloadProgress.value = 0f
    _infoMessage.value = getApplication<Application>().getString(R.string.look_similar_searching)

    viewModelScope.launch(Dispatchers.IO) {
      val destFile =
          File(
              getApplication<Application>().filesDir, SemanticEmbeddingGenerator.DEFAULT_MODEL_NAME)
      val tmpFile =
          File(
              getApplication<Application>().filesDir,
              "${SemanticEmbeddingGenerator.DEFAULT_MODEL_NAME}.tmp")
      var connection: HttpURLConnection? = null

      try {
        val url = URL(MODEL_DOWNLOAD_URL)
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          throw Exception(
              "Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
        }

        val fileLength = connection.contentLengthLong
        connection.inputStream.use { input ->
          FileOutputStream(tmpFile).use { output ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes: Long = 0

            while (input.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
              totalBytes += bytesRead
              if (fileLength > 0) {
                _downloadProgress.value = totalBytes.toFloat() / fileLength.toFloat()
              }
            }
          }
        }

        if (destFile.exists()) destFile.delete()
        tmpFile.renameTo(destFile)

        _infoMessage.value = getApplication<Application>().getString(R.string.download_complete)
        val initialized = embeddingGen.initialize(destFile)
        _isModelReady.value = initialized
        _infoMessage.value =
            if (initialized) {
              getApplication<Application>().getString(R.string.ready)
            } else {
              getApplication<Application>().getString(R.string.status_offline)
            }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to download visual similarity model", e)
        _infoMessage.value =
            e.localizedMessage ?: getApplication<Application>().getString(R.string.status_offline)
        if (tmpFile.exists()) tmpFile.delete()
      } finally {
        connection?.disconnect()
        _isDownloading.value = false
      }
    }
  }

  /**
   * Starts an asynchronous background indexing run for all gallery screenshots that are missing
   * embeddings.
   */
  fun startIndexing(allEntries: List<ScreenshotEntry>) {
    if (_isIndexing.value || !_isModelReady.value) return
    _isIndexing.value = true

    indexingJob =
        viewModelScope.launch(Dispatchers.Default) {
          try {
            val storedUris = vectorDb.getAllStoredUris()
            // Find which ones need indexing. Use URI key to avoid hash-collision skips.
            val pendingEntries =
                allEntries.filter { it.imageUri.isNotBlank() && it.imageUri !in storedUris }

            if (pendingEntries.isEmpty()) {
              _indexingProgress.value = Pair(allEntries.size, allEntries.size)
              _infoMessage.value =
                  getApplication<Application>().getString(R.string.embeddings_index_ready)
              return@launch
            }

            val total = pendingEntries.size
            var completed = 0

            _indexingProgress.value = Pair(completed, total)

            for (entry in pendingEntries) {
              if (indexingJob?.isCancelled == true) break

              val bitmap = loadBitmapFromUri(entry.imageUri)
              if (bitmap != null) {
                val embedding = embeddingGen.generateEmbedding(bitmap)
                bitmap.recycle()

                if (embedding != null) {
                  vectorDb.saveEmbedding(entry.imageHash, entry.imageUri, embedding)
                  refreshIndexedCount()
                }
              }

              completed++
              _indexingProgress.value = Pair(completed, total)
            }

            _infoMessage.value =
                getApplication<Application>().getString(R.string.look_similar_title)
          } catch (e: Exception) {
            Log.e(TAG, "Error running gallery vector indexing", e)
            _infoMessage.value =
                e.localizedMessage
                    ?: getApplication<Application>().getString(R.string.status_offline)
          } finally {
            _isIndexing.value = false
            _indexingProgress.value = null
          }
        }
  }

  /** Cancels active indexing run. */
  fun stopIndexing() {
    indexingJob?.cancel()
    _isIndexing.value = false
    _indexingProgress.value = null
    _infoMessage.value = getApplication<Application>().getString(R.string.pause)
  }

  /** Selects a screenshot as the target and queries all visually similar screenshots offline. */
  fun selectTargetScreenshot(target: ScreenshotEntry, allEntries: List<ScreenshotEntry>) {
    _targetScreenshot.value = target
    _similarScreenshots.value = emptyList()

    viewModelScope.launch(Dispatchers.Default) {
      _infoMessage.value = getApplication<Application>().getString(R.string.look_similar_searching)

      // 1. Get or generate target embedding
      var targetEmbedding =
          if (target.imageUri.isNotBlank()) {
            vectorDb.getEmbeddingByUri(target.imageUri)?.embedding
          } else {
            null
          }
      if (targetEmbedding == null && target.imageUri.isNotBlank()) {
        val bitmap = loadBitmapFromUri(target.imageUri)
        if (bitmap != null) {
          targetEmbedding = embeddingGen.generateEmbedding(bitmap)
          bitmap.recycle()

          if (targetEmbedding != null) {
            vectorDb.saveEmbedding(target.imageHash, target.imageUri, targetEmbedding)
          }
        }
      }

      if (targetEmbedding == null) {
        _infoMessage.value =
            getApplication<Application>().getString(R.string.no_highly_similar_screenshots_short)
        return@launch
      }

      // 2. Perform offline similarity sweep
      val matches = vectorDb.findSimilarScreenshots(targetEmbedding, similarityThreshold = 0.45f)

      // 3. Map matches back to original ScreenshotEntry files
      val mappedMatches =
          matches.mapNotNull { (embeddingRecord, score) ->
            // Skip the target screenshot itself in results
            if (embeddingRecord.imageUri == target.imageUri) return@mapNotNull null

            val originalEntry =
                allEntries.find { it.imageUri == embeddingRecord.imageUri }
                    ?: allEntries.find { it.imageHash == embeddingRecord.imageHash }
            if (originalEntry != null) {
              Pair(originalEntry, score)
            } else {
              null
            }
          }

      _similarScreenshots.value = mappedMatches
      _infoMessage.value =
          if (mappedMatches.isEmpty()) {
            getApplication<Application>().getString(R.string.look_similar_no_results)
          } else {
            getApplication<Application>().getString(R.string.results_count, mappedMatches.size)
          }
    }
  }

  /** Resets target screenshot and search results. */
  fun clearTargetScreenshot() {
    _targetScreenshot.value = null
    _similarScreenshots.value = emptyList()
    _infoMessage.value = null
  }

  /** Clears all embeddings to force a re-index. */
  fun resetEmbeddingsDatabase() {
    viewModelScope.launch(Dispatchers.IO) {
      vectorDb.clearAll()
      _targetScreenshot.value = null
      _similarScreenshots.value = emptyList()
      _indexedCount.value = 0
      _infoMessage.value = getApplication<Application>().getString(R.string.clear_index)
    }
  }

  private fun loadBitmapFromUri(uriString: String): Bitmap? {
    val context = getApplication<Application>()
    return try {
      val uri = Uri.parse(uriString)
      val contentResolver = context.contentResolver
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
          decoder.isMutableRequired = true
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
      } else {
        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load bitmap from uri: $uriString", e)
      null
    }
  }

  /**
   * Scans all entries to find ones whose image URIs can't be loaded (stale, revoked, or corrupt).
   */
  fun findBrokenEntries(allEntries: List<ScreenshotEntry>) {
    if (_isCheckingBroken.value) return
    _isCheckingBroken.value = true
    _brokenEntries.value = emptyList()

    viewModelScope.launch(Dispatchers.IO) {
      val broken = mutableListOf<ScreenshotEntry>()
      for (entry in allEntries) {
        if (entry.imageUri.isBlank()) {
          broken.add(entry)
          continue
        }
        val bitmap = loadBitmapFromUri(entry.imageUri)
        if (bitmap != null) {
          bitmap.recycle()
        } else {
          broken.add(entry)
        }
      }
      _brokenEntries.value = broken
      _isCheckingBroken.value = false
      Log.d(TAG, "Found ${broken.size} broken entries out of ${allEntries.size} total")
    }
  }

  fun clearInfoMessage() {
    _infoMessage.value = null
  }

  /**
   * Builds a debug list that explains why some gallery entries are not counted as indexed.
   *
   * Notes:
   * - Embeddings are keyed by image URI.
   * - Hash collisions do not block indexing; each URI can be indexed independently.
   */
  fun inspectUnindexedEntries(allEntries: List<ScreenshotEntry>) {
    if (_isInspectingUnindexed.value) return
    _isInspectingUnindexed.value = true
    _unindexedDebugEntries.value = emptyList()

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val storedUris = vectorDb.getAllStoredUris()

        val results = mutableListOf<UnindexedDebugEntry>()

        for (entry in allEntries) {
          val hash = entry.imageHash
          if (hash.isBlank()) {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Missing hash",
                    detail = "Cannot store embedding without image hash."))
            continue
          }

          if (entry.imageUri.isBlank()) {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Missing URI",
                    detail = "Image URI is blank, cannot load bitmap."))
            continue
          }

          if (entry.imageUri in storedUris) {
            continue
          }

          if (!_isModelReady.value) {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Model not ready",
                    detail = "Indexing model must be downloaded/initialized first."))
            continue
          }

          val bitmap = loadBitmapFromUri(entry.imageUri)
          if (bitmap == null) {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Bitmap load failed",
                    detail = "URI may be stale, deleted, moved, or permission was revoked."))
            continue
          }

          val embedding = embeddingGen.generateEmbedding(bitmap)
          bitmap.recycle()

          if (embedding == null) {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Embedding failed",
                    detail = "Model returned no vector for this image."))
          } else {
            results.add(
                UnindexedDebugEntry(
                    entry = entry,
                    reason = "Not indexed yet",
                    detail = "Image is indexable and should index on the next run."))
          }
        }

        _unindexedDebugEntries.value = results
        Log.d(
            TAG, "Unindexed inspection complete: ${results.size} entries out of ${allEntries.size}")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to inspect unindexed entries", e)
        _unindexedDebugEntries.value =
            listOf(
                UnindexedDebugEntry(
                    entry = ScreenshotEntry(imageHash = ""),
                    reason = "Inspection error",
                    detail = e.localizedMessage ?: "Unknown error"))
      } finally {
        _isInspectingUnindexed.value = false
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    indexingJob?.cancel()
    embeddingGen.close()
    vectorDb.close()
  }
}
