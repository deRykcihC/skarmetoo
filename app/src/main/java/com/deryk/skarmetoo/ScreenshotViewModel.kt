package com.deryk.skarmetoo

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.data.DataManager
import com.deryk.skarmetoo.data.ImageHasher
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "ScreenshotVM"

data class AlbumInfo(
    val name: String,
    val bucketId: String,
    val count: Int,
)

data class AlbumWithThumbnails(
    val album: AlbumInfo,
    val thumbnailUris: List<Uri>,
)

enum class ModelType(val fileName: String, val displayName: String) {
  GEMMA_3N("gemma-3n-E2B-it-int4.litertlm", "Gemma 3n"),
  GEMMA_4("gemma-4-E2B-it.litertlm", "Gemma 4"),
}

class ScreenshotViewModel(application: Application) : AndroidViewModel(application) {
  private val db = ScreenshotDatabase(application)
  val llmManager = LlmManager.getInstance(application)

  private val _entries = MutableStateFlow<List<ScreenshotEntry>>(emptyList())
  val entries: StateFlow<List<ScreenshotEntry>> = _entries.asStateFlow()

  private val _isModelReady = MutableStateFlow(false)
  val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

  private val _modelStatus = MutableStateFlow("Please load a model")
  val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _analysisProgress = MutableStateFlow<Pair<Int, Int>?>(null)
  val analysisProgress: StateFlow<Pair<Int, Int>?> = _analysisProgress.asStateFlow()

  private val _detailLevel = MutableStateFlow(LlmManager.DetailLevel.DETAILED)
  val detailLevel: StateFlow<LlmManager.DetailLevel> = _detailLevel.asStateFlow()

  private val _customPrompt = MutableStateFlow("")
  val customPrompt: StateFlow<String> = _customPrompt.asStateFlow()

  private val _isDownloadingModel = MutableStateFlow(false)
  val isDownloadingModel: StateFlow<Boolean> = _isDownloadingModel.asStateFlow()

  private val _downloadingModelType = MutableStateFlow<ModelType?>(null)
  val downloadingModelType: StateFlow<ModelType?> = _downloadingModelType.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _currentImageProgress = MutableStateFlow(0f)
  val currentImageProgress: StateFlow<Float> = _currentImageProgress.asStateFlow()

  // Per-entry progress for concurrent analysis — maps entry ID to progress float
  private val _entryProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
  val entryProgressMap: StateFlow<Map<Long, Float>> = _entryProgressMap.asStateFlow()

  // Debounce refreshEntries to avoid UI jank from rapid calls during concurrent analysis
  private val _refreshRequest = MutableStateFlow(System.currentTimeMillis())
  private var refreshDebounceJob: kotlinx.coroutines.Job? = null

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

  private val prefs =
      application.getSharedPreferences("skarmetoo_prefs", android.content.Context.MODE_PRIVATE)

  private val _availableAlbums = MutableStateFlow<List<AlbumInfo>>(emptyList())
  val availableAlbums: StateFlow<List<AlbumInfo>> = _availableAlbums.asStateFlow()

  private val _albumThumbnails = MutableStateFlow<List<AlbumWithThumbnails>>(emptyList())
  val albumThumbnails: StateFlow<List<AlbumWithThumbnails>> = _albumThumbnails.asStateFlow()

  // Thumbnails for the "All" album (latest 4 images across all albums)
  private val _allAlbumThumbnailUris = MutableStateFlow<List<Uri>>(emptyList())
  val allAlbumThumbnailUris: StateFlow<List<Uri>> = _allAlbumThumbnailUris.asStateFlow()

  // Pinned album IDs — persisted in SharedPreferences
  private val _pinnedAlbumIds = MutableStateFlow<Set<String>>(emptySet())
  val pinnedAlbumIds: StateFlow<Set<String>> = _pinnedAlbumIds.asStateFlow()

  // Custom album ordering (list of bucket IDs) — persisted in SharedPreferences
  private val _albumOrder = MutableStateFlow<List<String>>(emptyList())
  val albumOrder: StateFlow<List<String>> = _albumOrder.asStateFlow()

  private val _selectedAlbums = MutableStateFlow<Set<String>>(emptySet())
  val selectedAlbums: StateFlow<Set<String>> = _selectedAlbums.asStateFlow()

  private val _isModelFound = MutableStateFlow(true)
  val isModelFound: StateFlow<Boolean> = _isModelFound.asStateFlow()

  private val _selectedModel = MutableStateFlow(ModelType.GEMMA_3N)
  val selectedModel: StateFlow<ModelType> = _selectedModel.asStateFlow()

  private val _isGemma3nDownloaded = MutableStateFlow(false)
  val isGemma3nDownloaded: StateFlow<Boolean> = _isGemma3nDownloaded.asStateFlow()

  private val _isGemma4Downloaded = MutableStateFlow(false)
  val isGemma4Downloaded: StateFlow<Boolean> = _isGemma4Downloaded.asStateFlow()

  private val _sourceFolders = MutableStateFlow<Set<String>>(emptySet())
  val sourceFolders: StateFlow<Set<String>> = _sourceFolders.asStateFlow()

  private val _folderImageCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
  val folderImageCounts: StateFlow<Map<String, Int>> = _folderImageCounts.asStateFlow()

  private val isAnalyzing = java.util.concurrent.atomic.AtomicBoolean(false)
  private var analysisJob: kotlinx.coroutines.Job? = null

  private fun launchAnalysisQueue() {
    if (isAnalyzing.get() || _isAnalysisPaused.value) return
    analysisJob = viewModelScope.launch(Dispatchers.IO) { startAnalysisQueue() }
  }

  private val _imageResolution = MutableStateFlow(prefs.getInt("image_resolution", 1024))
  val imageResolution: StateFlow<Int> = _imageResolution.asStateFlow()

  private val _showPlayPauseToggle = MutableStateFlow(prefs.getBoolean("show_play_pause", false))
  val showPlayPauseToggle: StateFlow<Boolean> = _showPlayPauseToggle.asStateFlow()

  fun setShowPlayPauseToggle(show: Boolean) {
    _showPlayPauseToggle.value = show
    prefs.edit().putBoolean("show_play_pause", show).apply()
  }

  private val _isAnalysisPaused = MutableStateFlow(false)
  val isAnalysisPaused: StateFlow<Boolean> = _isAnalysisPaused.asStateFlow()

  fun toggleAnalysisPause() {
    val newPaused = !_isAnalysisPaused.value
    _isAnalysisPaused.value = newPaused
    if (newPaused) {
      viewModelScope.launch(Dispatchers.IO) {
        isAnalyzing.set(false)
        analysisJob?.join()
      }
    } else {
      launchAnalysisQueue()
    }
  }

  fun setImageResolution(value: Int) {
    _imageResolution.value = value
    prefs.edit().putInt("image_resolution", value).apply()
  }

  private val _analysisInstanceCount = MutableStateFlow(prefs.getInt("analysis_instance_count", 1))
  val analysisInstanceCount: StateFlow<Int> = _analysisInstanceCount.asStateFlow()

  fun setAnalysisInstanceCount(value: Int) {
    _analysisInstanceCount.value = value
    prefs.edit().putInt("analysis_instance_count", value).apply()
  }

  private val _maxTokens = MutableStateFlow(prefs.getInt("max_tokens", 4096))
  val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

  fun setMaxTokens(value: Int) {
    _maxTokens.value = value
    prefs.edit().putInt("max_tokens", value).apply()
  }

  /**
   * Force apply advanced settings (image resolution / concurrency) by cancelling any running
   * analysis and restarting the queue so that new values take effect immediately.
   */
  fun applyAdvancedSettings() {
    viewModelScope.launch {
      // Halt any in-progress analysis
      isAnalyzing.set(false)
      // Wait for existing workers to completely finish their current image and exit
      analysisJob?.join()
      refreshEntries()
      withContext(Dispatchers.Main) {}
      // Restart with the newly persisted values
      launchAnalysisQueue()
    }
  }

  private val _appLanguage = MutableStateFlow("en")
  val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

  private val _isDarkMode = MutableStateFlow(prefs.getBoolean("is_dark_mode", false))
  val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

  fun setDarkMode(enabled: Boolean) {
    _isDarkMode.value = enabled
    prefs.edit().putBoolean("is_dark_mode", enabled).apply()
  }

  private val _analysisLanguage = MutableStateFlow("en")
  val analysisLanguage: StateFlow<String> = _analysisLanguage.asStateFlow()

  private val _isSortDescending = MutableStateFlow(true)
  val isSortDescending: StateFlow<Boolean> = _isSortDescending.asStateFlow()

  // Selected album in experimental screen — persisted so it survives navigation
  private val _selectedExperimentalAlbumId = MutableStateFlow<String?>(null)
  val selectedExperimentalAlbumId: StateFlow<String?> = _selectedExperimentalAlbumId.asStateFlow()

  fun setSelectedExperimentalAlbumId(bucketId: String?) {
    _selectedExperimentalAlbumId.value = bucketId
    prefs.edit().putString("selected_experimental_album_id", bucketId).apply()
  }

  private val _hasSeenOnboarding = MutableStateFlow(false)
  val hasSeenOnboarding: StateFlow<Boolean> = _hasSeenOnboarding.asStateFlow()

  fun setAppLanguage(lang: String) {
    _appLanguage.value = lang
    prefs.edit().putString("app_language", lang).apply()
  }

  fun setAnalysisLanguage(lang: String) {
    _analysisLanguage.value = lang
    prefs.edit().putString("analysis_language", lang).apply()
  }

  fun toggleSortOrder() {
    _isSortDescending.value = !_isSortDescending.value
    prefs.edit().putBoolean("is_sort_descending", _isSortDescending.value).apply()
  }

  fun setHasSeenOnboarding(seen: Boolean) {
    _hasSeenOnboarding.value = seen
    prefs.edit().putBoolean("has_seen_onboarding", seen).apply()
  }

  /** Toggle pin/unpin for an album by its bucket ID. */
  fun togglePinAlbum(bucketId: String) {
    val current = _pinnedAlbumIds.value.toMutableSet()
    if (current.contains(bucketId)) {
      current.remove(bucketId)
    } else {
      current.add(bucketId)
    }
    _pinnedAlbumIds.value = current
    prefs.edit().putStringSet("pinned_album_ids", current).apply()
  }

  /** Persist a fully custom album order (list of bucket IDs). */
  fun updateAlbumOrder(orderedBucketIds: List<String>) {
    _albumOrder.value = orderedBucketIds
    prefs.edit().putString("album_order", orderedBucketIds.joinToString(",")).apply()
  }

  init {
    val currentUris = prefs.getStringSet("saved_folder_uris", emptySet()) ?: emptySet()
    _sourceFolders.value = currentUris

    val savedLevel = prefs.getString("detail_level", LlmManager.DetailLevel.DETAILED.name)
    _detailLevel.value =
        try {
          LlmManager.DetailLevel.valueOf(savedLevel ?: LlmManager.DetailLevel.DETAILED.name)
        } catch (e: Exception) {
          LlmManager.DetailLevel.DETAILED
        }
    _customPrompt.value = prefs.getString("custom_prompt", "") ?: ""

    val savedLang = prefs.getString("app_language", "en") ?: "en"
    _appLanguage.value = savedLang

    val savedAnalysisLang = prefs.getString("analysis_language", "en") ?: "en"
    _analysisLanguage.value = savedAnalysisLang

    _isSortDescending.value = prefs.getBoolean("is_sort_descending", true)
    _hasSeenOnboarding.value = prefs.getBoolean("has_seen_onboarding", false)

    // Restore pinned album IDs
    _pinnedAlbumIds.value = prefs.getStringSet("pinned_album_ids", emptySet()) ?: emptySet()
    // Restore custom album order
    val savedOrder = prefs.getString("album_order", null)
    _albumOrder.value = savedOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    // Restore selected media albums
    _selectedAlbums.value = prefs.getStringSet("selected_album_ids", emptySet()) ?: emptySet()

    // Restore selected model from prefs
    val savedModelType = prefs.getString("selected_model", ModelType.GEMMA_3N.name)
    _selectedModel.value =
        try {
          ModelType.valueOf(savedModelType ?: ModelType.GEMMA_3N.name)
        } catch (e: Exception) {
          ModelType.GEMMA_3N
        }

    // Restore selected experimental album
    _selectedExperimentalAlbumId.value = prefs.getString("selected_experimental_album_id", null)

    viewModelScope.launch {
      llmManager.uiState.collect { state ->
        when (state) {
          is LlmManager.LlmState.Initial -> _modelStatus.value = "Please load a model"
          is LlmManager.LlmState.Loading -> {
            _modelStatus.value = "Loading model..."
            _isModelReady.value = false
          }
          is LlmManager.LlmState.Ready -> {
            _modelStatus.value = "Ready"
            _isModelReady.value = true
            launchAnalysisQueue()
          }
          is LlmManager.LlmState.Generating -> _modelStatus.value = "Analyzing..."
          is LlmManager.LlmState.Error -> {
            _modelStatus.value = "Error: ${state.message}"
            _isModelReady.value = false
          }
        }
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        db.resetAllAnalyzingFlags()
      } catch (e: Exception) {
        Log.e(TAG, "Error resetting flags", e)
      }
      refreshEntries()
      loadAlbums()

      try {
        refreshModelDownloadStatus()

        val selected = _selectedModel.value
        val selectedModelFile = java.io.File(application.filesDir, selected.fileName)
        if (selectedModelFile.exists()) {
          initializeModel(selectedModelFile.absolutePath, isGemma4 = selected == ModelType.GEMMA_4)
        } else {
          val fallback =
              if (selected == ModelType.GEMMA_3N) ModelType.GEMMA_4 else ModelType.GEMMA_3N
          val fallbackFile = java.io.File(application.filesDir, fallback.fileName)
          if (fallbackFile.exists()) {
            _selectedModel.value = fallback
            prefs.edit().putString("selected_model", fallback.name).apply()
            initializeModel(fallbackFile.absolutePath, isGemma4 = fallback == ModelType.GEMMA_4)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error looking for model automatically", e)
      }
    }

    // Auto-refresh images on startup if a folder was selected
    refreshImages()

    // Load images from previously selected media albums
    if (_selectedAlbums.value.isNotEmpty()) {
      loadSelectedAlbums()
    }
  }

  fun setSelectedModel(model: ModelType) {
    _selectedModel.value = model
    prefs.edit().putString("selected_model", model.name).apply()
    checkModelExists()
  }

  private fun refreshModelDownloadStatus() {
    val context = getApplication<Application>()
    val isDownloading = _isDownloadingModel.value
    val downloadingType = _downloadingModelType.value

    val g3File = java.io.File(context.filesDir, ModelType.GEMMA_3N.fileName)
    _isGemma3nDownloaded.value =
        g3File.exists() &&
            g3File.length() > 100L * 1024 * 1024 &&
            !(isDownloading && downloadingType == ModelType.GEMMA_3N)

    val g4File = java.io.File(context.filesDir, ModelType.GEMMA_4.fileName)
    _isGemma4Downloaded.value =
        g4File.exists() &&
            g4File.length() > 100L * 1024 * 1024 &&
            !(isDownloading && downloadingType == ModelType.GEMMA_4)
  }

  fun checkModelExists() {
    viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      refreshModelDownloadStatus()
      val selectedFile = java.io.File(context.filesDir, _selectedModel.value.fileName)
      val exists = selectedFile.exists()
      _isModelFound.value = exists
      if (!exists) {
        _modelStatus.value = "Model not found"
        _isModelReady.value = false
      } else if (_modelStatus.value == "Model not found") {
        _modelStatus.value = "Please load a model"
      }
    }
  }

  fun initializeModel(
      path: String,
      useGpu: Boolean = false,
      isGemma4: Boolean = false,
  ) {
    checkModelExists()
    val requestedFile = java.io.File(path)
    if (!requestedFile.exists()) return

    llmManager.initializeModel(
        path, useGpu = useGpu, maxTokens = _maxTokens.value, isGemma4 = isGemma4)
  }

  fun refreshEntries() {
    viewModelScope.launch(Dispatchers.IO) {
      val query = _searchQuery.value
      val result =
          if (query.isBlank()) {
            db.getAllEntries()
          } else {
            db.searchEntries(query)
          }
      _entries.value = result
      rebuildExperimentalStatuses()
    }
  }

  /**
   * Debounced version of refreshEntries for use during concurrent analysis. Coalesces rapid calls
   * within a 500ms window into a single DB read + UI update, eliminating the jank caused by N
   * workers each calling refreshEntries() independently.
   */
  fun refreshEntriesDebounced() {
    refreshDebounceJob?.cancel()
    refreshDebounceJob =
        viewModelScope.launch(Dispatchers.IO) {
          kotlinx.coroutines.delay(500L)
          val query = _searchQuery.value
          val result =
              if (query.isBlank()) {
                db.getAllEntries()
              } else {
                db.searchEntries(query)
              }
          _entries.value = result
          rebuildExperimentalStatuses()
        }
  }

  fun setSearchQuery(query: String) {
    _searchQuery.value = query
    refreshEntries()
  }

  fun setDetailLevel(level: LlmManager.DetailLevel) {
    _detailLevel.value = level
    prefs.edit().putString("detail_level", level.name).apply()
  }

  fun setCustomPrompt(prompt: String) {
    _customPrompt.value = prompt
    prefs.edit().putString("custom_prompt", prompt).apply()
  }

  fun downloadModel(
      url: String,
      token: String,
      cookies: String?,
      useGpu: Boolean,
      modelType: ModelType = ModelType.GEMMA_3N,
  ) {
    if (_isDownloadingModel.value) return
    _isDownloadingModel.value = true
    _downloadingModelType.value = modelType
    _downloadProgress.value = 0f

    viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      val destFile = java.io.File(context.filesDir, modelType.fileName)
      try {
        var currentUrl = url
        var connection: java.net.HttpURLConnection
        var redirects = 0

        while (true) {
          connection = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
          connection.instanceFollowRedirects = false
          connection.setRequestProperty("Accept-Encoding", "identity")
          connection.connectTimeout = 60000
          connection.readTimeout = 60000

          // Only send auth headers to HuggingFace, but DROP them for subsequent AWS S3 CDN
          // redirects
          if (currentUrl.contains("huggingface.co") && !currentUrl.contains("cdn-lfs")) {
            if (token.isNotBlank()) {
              connection.setRequestProperty("Authorization", "Bearer $token")
            } else if (!cookies.isNullOrBlank()) {
              connection.setRequestProperty("Cookie", cookies)
            }
          }

          connection.connect()

          val status = connection.responseCode
          if (status in 300..399) {
            val newUrl = connection.getHeaderField("Location")
            if (newUrl != null) {
              currentUrl = newUrl
              redirects++
              if (redirects > 10) throw Exception("Too many redirects")
              connection.disconnect()
              continue
            } else {
              throw Exception("Redirect missing Location header")
            }
          }

          if (status != java.net.HttpURLConnection.HTTP_OK) {
            try {
              if (status == 401) {
                throw Exception(
                    "Unauthorized: Please sign in to Hugging Face and accept the model's license agreement.")
              }
              val errorStream = connection.errorStream ?: connection.inputStream
              val err = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
              throw Exception("Server returned HTTP $status ${connection.responseMessage}. $err")
            } catch (e: Exception) {
              if (e.message?.contains("Unauthorized:") == true) {
                throw e
              }
              throw Exception("Server returned HTTP $status ${connection.responseMessage}")
            }
          }
          break // Connected successfully
        }

        val fileLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val input = java.io.BufferedInputStream(connection.inputStream)
        val tmpFile = java.io.File(context.filesDir, "${modelType.fileName}.tmp")
        val output = java.io.FileOutputStream(tmpFile)

        val data = ByteArray(8192)
        var total: Long = 0
        var count: Int

        var lastUIUpdateStamp = System.currentTimeMillis()
        while (input.read(data).also { count = it } != -1) {
          total += count.toLong()
          if (fileLength > 0L) {
            val now = System.currentTimeMillis()
            if (now - lastUIUpdateStamp > 50) {
              _downloadProgress.value = (total.toDouble() / fileLength.toDouble()).toFloat()
              lastUIUpdateStamp = now
            }
          }
          output.write(data, 0, count)
        }

        _downloadProgress.value = 1.0f
        output.flush()
        output.close()
        input.close()
        connection.disconnect()

        if (destFile.exists()) destFile.delete()
        tmpFile.renameTo(destFile)

        // Update download status
        refreshModelDownloadStatus()

        withContext(Dispatchers.Main) { _modelStatus.value = "Downloaded to internal storage" }

        // Auto-select and load the just-downloaded model
        _selectedModel.value = modelType
        prefs.edit().putString("selected_model", modelType.name).apply()
        _isModelFound.value = true
        initializeModel(
            destFile.absolutePath,
            useGpu = useGpu,
            isGemma4 = modelType == ModelType.GEMMA_4,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed downloading model", e)
        val tmpFile = java.io.File(context.filesDir, "${modelType.fileName}.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        withContext(Dispatchers.Main) {}
      } finally {
        _isDownloadingModel.value = false
        _downloadingModelType.value = null
        refreshModelDownloadStatus()
      }
    }
  }

  // ====== Album functions ======

  fun loadAlbums() {
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      val albums = mutableMapOf<String, AlbumInfo>()
      val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      val projection =
          arrayOf(
              MediaStore.Images.Media.BUCKET_ID,
              MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
          )

      try {
        context.contentResolver
            .query(
                uri,
                projection,
                null,
                null,
                null,
            )
            ?.use { cursor ->
              val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
              val bucketNameCol =
                  cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

              while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"

                val existing = albums[bucketId]
                if (existing != null) {
                  albums[bucketId] = existing.copy(count = existing.count + 1)
                } else {
                  albums[bucketId] = AlbumInfo(bucketName, bucketId, 1)
                }
              }
            }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load albums", e)
      }

      _availableAlbums.value = albums.values.sortedByDescending { it.count }.toList()

      Log.d(TAG, "Found ${albums.size} albums")
    }
  }

  fun toggleAlbum(bucketId: String) {
    val current = _selectedAlbums.value.toMutableSet()
    if (current.contains(bucketId)) {
      current.remove(bucketId)
    } else {
      current.add(bucketId)
    }
    _selectedAlbums.value = current
  }

  fun applySelectedMediaAlbums(selectedBucketIds: Set<String>) {
    val previousSelected = _selectedAlbums.value
    _selectedAlbums.value = selectedBucketIds
    prefs.edit().putStringSet("selected_album_ids", selectedBucketIds).apply()

    val newlyDeselected = previousSelected - selectedBucketIds
    val newlySelected = selectedBucketIds - previousSelected

    viewModelScope.launch(Dispatchers.IO) {
      // Remove images from deselected albums
      if (newlyDeselected.isNotEmpty()) {
        removeImagesFromAlbums(newlyDeselected)
      }

      // Add images from newly selected albums
      if (newlySelected.isNotEmpty()) {
        loadSelectedAlbums(newlySelected)
      } else {
        refreshEntries()
      }
    }
  }

  private suspend fun removeImagesFromAlbums(bucketIds: Set<String>) {
    val context = getApplication<Application>()
    val urisToRemove = mutableListOf<String>()

    for (bucketId in bucketIds) {
      try {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)

        context.contentResolver
            .query(
                uri,
                projection,
                selection,
                selectionArgs,
                null,
            )
            ?.use { cursor ->
              val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
              while (cursor.moveToNext()) {
                val imageId = cursor.getLong(idCol)
                val imageUri =
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId,
                    )
                urisToRemove.add(imageUri.toString())
              }
            }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to query album $bucketId for removal", e)
      }
    }

    // Delete entries from database whose imageUri matches
    val deletedCount = db.deleteEntriesByImageUris(urisToRemove)

    Log.d(
        TAG,
        "Removed $deletedCount/${urisToRemove.size} images from ${bucketIds.size} deselected albums")
    refreshEntries()
  }

  fun clearSelectedAlbums() {
    _selectedAlbums.value = emptySet()
  }

  // Batch loading: store ALL uris separately, expose only loaded subset
  private val _allExperimentalImageUris = MutableStateFlow<List<Uri>>(emptyList())
  private val _experimentalImageUris = MutableStateFlow<List<Uri>>(emptyList())
  val experimentalImageUris: StateFlow<List<Uri>> = _experimentalImageUris.asStateFlow()

  private val _experimentalLoadedCount = MutableStateFlow(0)
  val experimentalLoadedCount: StateFlow<Int> = _experimentalLoadedCount.asStateFlow()

  private val _hasMoreExperimentalImages = MutableStateFlow(false)
  val hasMoreExperimentalImages: StateFlow<Boolean> = _hasMoreExperimentalImages.asStateFlow()

  // Total count of ALL images (including unloaded) — used for accurate scrollbar sizing
  private val _experimentalTotalCount = MutableStateFlow(0)
  val experimentalTotalCount: StateFlow<Int> = _experimentalTotalCount.asStateFlow()

  // Maps MediaStore URI string → (Entry ID, isAnalyzed)
  private val _experimentalStatuses = MutableStateFlow<Map<String, Pair<Long, Boolean>>>(emptyMap())
  val experimentalStatuses: StateFlow<Map<String, Pair<Long, Boolean>>> =
      _experimentalStatuses.asStateFlow()

  // Maps file relative path → MediaStore URI string (built during loadExperimentalImages)
  private val _experimentalPathMap = MutableStateFlow<Map<String, String>>(emptyMap())

  // Migrate old default (3) to new default (6) for better performance with many images
  private val _experimentalGridColumns =
      MutableStateFlow(
          run {
            var cols = prefs.getInt("experimental_grid_columns", 6)
            if (cols == 3 && !prefs.contains("experimental_grid_columns_migrated")) {
              cols = 6
              prefs
                  .edit()
                  .putInt("experimental_grid_columns", 6)
                  .putBoolean("experimental_grid_columns_migrated", true)
                  .apply()
            }
            cols
          })
  val experimentalGridColumns: StateFlow<Int> = _experimentalGridColumns.asStateFlow()

  fun setExperimentalGridColumns(columns: Int) {
    _experimentalGridColumns.value = columns
    prefs.edit().putInt("experimental_grid_columns", columns).apply()
  }

  /**
   * Adaptive batch size: fewer columns = larger images = smaller batches to reduce memory pressure
   */
  private fun getAdaptiveBatchSize(): Int {
    val cols = _experimentalGridColumns.value
    return when {
      cols <= 2 -> 200
      cols <= 3 -> 400
      cols <= 5 -> 700
      else -> EXPERIMENTAL_BATCH_SIZE
    }
  }

  companion object {
    private const val EXPERIMENTAL_BATCH_SIZE = 1000
  }

  // Loading state so UI can show a spinner instead of "no screenshots yet"
  // when switching albums (old thumbnails stay visible during the transition).
  private val _isExperimentalLoading = MutableStateFlow(false)
  val isExperimentalLoading: StateFlow<Boolean> = _isExperimentalLoading.asStateFlow()

  // Guard against concurrent loads: cancel the previous job and use a generation
  // counter so that stale results from an older coroutine are discarded.
  private var loadExperimentalJob: kotlinx.coroutines.Job? = null
  private var loadExperimentalGeneration = 0L

  // Exposed so the UI can reset staggered-reveal when a new album's images arrive
  private val _experimentalImageGeneration = MutableStateFlow(0L)
  val experimentalImageGeneration: StateFlow<Long> = _experimentalImageGeneration.asStateFlow()

  @Suppress("DEPRECATION")
  fun loadExperimentalImages(bucketId: String? = null) {
    val context = getApplication<Application>()
    val generation = ++loadExperimentalGeneration

    // Cancel any in-flight load so its results can't overwrite ours
    loadExperimentalJob?.cancel()

    // Mark as loading — keep old thumbnails visible during the transition
    // so the UI never flashes "no screenshots yet" between album switches.
    _isExperimentalLoading.value = true

    loadExperimentalJob =
        viewModelScope.launch(Dispatchers.IO) {
          val uris = mutableListOf<Uri>()
          val pathToUri = mutableMapOf<String, String>() // relativePath → mediaStoreUriString
          val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
          val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
          val externalRoot = Environment.getExternalStorageDirectory().absolutePath

          try {
            val (selection, selectionArgs) =
                if (bucketId != null) {
                  "${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId)
                } else {
                  null to null
                }

            context.contentResolver
                .query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )
                ?.use { cursor ->
                  val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                  val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                  while (cursor.moveToNext()) {
                    val imageId = cursor.getLong(idCol)
                    val imageUri =
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            imageId,
                        )
                    uris.add(imageUri)

                    // Extract relative file path for matching against SAF URIs
                    val filePath = cursor.getString(dataCol) ?: ""
                    if (filePath.isNotEmpty()) {
                      val relativePath = filePath.removePrefix(externalRoot).trimStart('/')
                      pathToUri[relativePath] = imageUri.toString()
                    }
                  }
                }
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to load experimental images", e)
          }

          // Discard results if a newer load has been started since this one
          if (generation != loadExperimentalGeneration) return@launch

          // Store ALL uris and expose them all at once (no batch loading)
          _allExperimentalImageUris.value = uris
          _experimentalTotalCount.value = uris.size
          _experimentalLoadedCount.value = uris.size
          _experimentalImageUris.value = uris
          _hasMoreExperimentalImages.value = false
          _experimentalPathMap.value = pathToUri
          rebuildExperimentalStatuses()
          _isExperimentalLoading.value = false
          _experimentalImageGeneration.value += 1
        }
  }

  /** Load the next batch of experimental images. Called when user scrolls near the end. */
  fun loadMoreExperimentalImages() {
    if (!_hasMoreExperimentalImages.value) return
    val allUris = _allExperimentalImageUris.value
    val currentCount = _experimentalLoadedCount.value
    val nextCount = minOf(currentCount + getAdaptiveBatchSize(), allUris.size)
    if (nextCount == currentCount) return

    _experimentalLoadedCount.value = nextCount
    _experimentalImageUris.value = allUris.take(nextCount)
    _hasMoreExperimentalImages.value = nextCount < allUris.size
  }

  /**
   * Cross-reference gallery entries (SAF URIs) with experimental images (MediaStore URIs) by
   * extracting relative file paths from both URI types.
   */
  fun rebuildExperimentalStatuses() {
    val entries = _entries.value
    val pathToMediaUri = _experimentalPathMap.value
    if (pathToMediaUri.isEmpty()) return

    // Build a map of relativePath → entry from gallery entries (for SAF URIs)
    // and a map of mediaStore URI → entry (for MediaStore URIs from album selection)
    val entryByPath = mutableMapOf<String, ScreenshotEntry>()
    val entryByMediaUri = mutableMapOf<String, ScreenshotEntry>()
    for (entry in entries) {
      val relativePath = extractRelativePathFromSafUri(entry.imageUri)
      if (relativePath != null) {
        entryByPath[relativePath] = entry
      }
      // Also index entries that already have MediaStore URIs (from album selection)
      if (entry.imageUri.startsWith("content://media/")) {
        entryByMediaUri[entry.imageUri] = entry
      }
    }

    // Match: for each experimental image path, check if there's a gallery entry
    val statuses = mutableMapOf<String, Pair<Long, Boolean>>()
    for ((relativePath, mediaUriString) in pathToMediaUri) {
      val entry = entryByPath[relativePath] ?: entryByMediaUri[mediaUriString]
      if (entry != null) {
        statuses[mediaUriString] = entry.id to (entry.analyzedAt > 0L)
      }
    }

    _experimentalStatuses.value = statuses
  }

  /**
   * Extract the relative file path from a SAF document URI. e.g.
   * content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fphoto.jpg →
   * DCIM/photo.jpg
   */
  private fun extractRelativePathFromSafUri(uriString: String): String? {
    if (uriString.isBlank()) return null
    return try {
      val uri = Uri.parse(uriString)
      val docId = DocumentsContract.getDocumentId(uri)
      val colonIndex = docId.indexOf(':')
      if (colonIndex >= 0) docId.substring(colonIndex + 1) else null
    } catch (e: Exception) {
      null
    }
  }

  fun loadAlbumThumbnails() {
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      // Query albums directly from MediaStore instead of relying on
      // _availableAlbums which may not be populated yet (race condition).
      val albums = mutableMapOf<String, AlbumInfo>()
      try {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection =
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
          val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
          val bucketNameCol =
              cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
          while (cursor.moveToNext()) {
            val bucketId = cursor.getString(bucketIdCol) ?: continue
            val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"
            val existing = albums[bucketId]
            if (existing != null) {
              albums[bucketId] = existing.copy(count = existing.count + 1)
            } else {
              albums[bucketId] = AlbumInfo(bucketName, bucketId, 1)
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load albums for thumbnails", e)
      }

      val sortedAlbums = albums.values.sortedByDescending { it.count }
      val result = mutableListOf<AlbumWithThumbnails>()

      for (album in sortedAlbums) {
        val thumbnailUris = mutableListOf<Uri>()
        try {
          val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
          val selectionArgs = arrayOf(album.bucketId)
          val projection = arrayOf(MediaStore.Images.Media._ID)
          val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

          context.contentResolver
              .query(
                  uri,
                  projection,
                  selection,
                  selectionArgs,
                  "${MediaStore.Images.Media.DATE_ADDED} DESC",
              )
              ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && thumbnailUris.size < 4) {
                  val imageId = cursor.getLong(idCol)
                  val imageUri =
                      ContentUris.withAppendedId(
                          MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                          imageId,
                      )
                  thumbnailUris.add(imageUri)
                }
              }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load thumbnails for album ${album.name}", e)
        }
        result.add(AlbumWithThumbnails(album, thumbnailUris))
      }

      // Capture "All" album thumbnails: latest 4 images across all albums
      val allThumbnailUris = mutableListOf<Uri>()
      try {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
            ?.use { cursor ->
              val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
              while (cursor.moveToNext() && allThumbnailUris.size < 4) {
                val imageId = cursor.getLong(idCol)
                allThumbnailUris.add(
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId,
                    ),
                )
              }
            }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load All album thumbnails", e)
      }
      _allAlbumThumbnailUris.value = allThumbnailUris

      // Also update availableAlbums so the rest of the UI stays in sync
      _availableAlbums.value = sortedAlbums
      _albumThumbnails.value = result
    }
  }

  fun loadSelectedAlbums() {
    viewModelScope.launch(Dispatchers.IO) { loadSelectedAlbums(_selectedAlbums.value) }
  }

  private suspend fun loadSelectedAlbums(bucketIds: Set<String>) {
    if (bucketIds.isEmpty()) return

    val context = getApplication<Application>()
    val uris = mutableListOf<Uri>()
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)

    for (bucketId in bucketIds) {
      try {
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)

        context.contentResolver
            .query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )
            ?.use { cursor ->
              val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
              while (cursor.moveToNext()) {
                val imageId = cursor.getLong(idCol)
                val imageUri =
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId,
                    )
                uris.add(imageUri)
              }
            }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load album $bucketId", e)
      }
    }

    Log.d(TAG, "Loading ${uris.size} images from ${bucketIds.size} albums")

    // Use existing addScreenshots logic (handles dedup, hash, etc.)
    withContext(Dispatchers.Main) { addScreenshots(uris) }
  }

  fun addScreenshots(uris: List<Uri>): kotlinx.coroutines.Job {
    val context = getApplication<Application>()
    return viewModelScope.launch(Dispatchers.IO) {
      for (uri in uris) {
        try {
          try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (_: Exception) {}

          val uriString = uri.toString()
          if (db.getEntryByImageUri(uriString) != null) {
            continue // Skip if this exact image URI is already added
          }

          val bitmap = loadBitmap(uri) ?: continue
          val hash = ImageHasher.computeDHash(bitmap)

          val existing = db.getEntryByHash(hash)
          // Link if it's an imported JSON hash that currently has no local image URI
          if (existing != null && existing.imageUri.isBlank()) {
            db.linkImageToHash(hash, uriString)
            Log.d(TAG, "Linked imported entry: $hash")
            continue
          }

          val entry =
              ScreenshotEntry(
                  imageUri = uriString,
                  imageHash = hash,
              )
          db.insertEntry(entry)
          Log.d(TAG, "Added screenshot: hash=$hash")
        } catch (e: Exception) {
          Log.e(TAG, "Failed to add screenshot", e)
        }
      }
      refreshEntries()

      // Auto-analyze if model is ready
      if (_isModelReady.value) {
        launchAnalysisQueue()
      }
    }
  }

  fun clearSourceFolders() {
    prefs.edit().remove("saved_folder_uris").apply()
    _sourceFolders.value = emptySet()
    _folderImageCounts.value = emptyMap()
    viewModelScope.launch(Dispatchers.IO) {
      db.deleteAllEntries()
      refreshEntries()
      withContext(Dispatchers.Main) {}
    }
  }

  fun removeSourceFolder(uriStr: String) {
    val currentUris =
        prefs.getStringSet("saved_folder_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
    if (currentUris.remove(uriStr)) {
      prefs.edit().putStringSet("saved_folder_uris", currentUris).apply()
      _sourceFolders.value = currentUris
      _folderImageCounts.value = _folderImageCounts.value.toMutableMap().apply { remove(uriStr) }

      viewModelScope.launch(Dispatchers.IO) {
        val context = getApplication<Application>()
        db.deleteAllEntries()
        refreshImagesInternal()
        refreshEntries()
        withContext(Dispatchers.Main) {}
      }
    }
  }

  fun loadImagesFromFolder(treeUri: Uri) {
    val context = getApplication<Application>()
    _isRefreshing.value = true

    val currentUris =
        prefs.getStringSet("saved_folder_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
    currentUris.add(treeUri.toString())
    prefs.edit().putStringSet("saved_folder_uris", currentUris).apply()
    _sourceFolders.value = currentUris

    viewModelScope.launch(Dispatchers.IO) {
      try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      } catch (_: Exception) {}

      refreshImagesInternal()

      withContext(Dispatchers.Main) { _isRefreshing.value = false }
    }
  }

  private suspend fun scanAndAddFolder(
      treeUri: Uri,
      context: android.content.Context,
      globalSeenFiles: MutableSet<String>,
  ) {
    try {
      val imageUris = mutableListOf<Uri>()
      val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

      fun scanFolder(folderUri: Uri) {
        val childrenUri =
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri) ?: return
        childrenUri.listFiles().forEach { file ->
          if (file.isDirectory) {
            val name = file.name ?: ""
            if (!name.startsWith(".")) {
              file.uri.let { scanFolder(it) }
            }
          } else if (file.isFile) {
            val name = file.name?.lowercase() ?: ""
            if (name.startsWith(".")) return@forEach
            val ext = name.substringAfterLast('.', "")
            if (ext in imageExtensions) {
              val uniqueId = "$name-${file.length()}"
              if (globalSeenFiles.add(uniqueId)) {
                imageUris.add(file.uri)
              }
            }
          }
        }
      }

      scanFolder(treeUri)

      Log.d(TAG, "Found ${imageUris.size} images in folder $treeUri")
      _folderImageCounts.value =
          _folderImageCounts.value.toMutableMap().apply { put(treeUri.toString(), imageUris.size) }

      if (imageUris.isNotEmpty()) {
        val job = addScreenshots(imageUris)
        job.join()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning folder: $treeUri", e)
    }
  }

  private suspend fun refreshImagesInternal() {
    val savedUris = prefs.getStringSet("saved_folder_uris", emptySet()) ?: emptySet()
    val context = getApplication<Application>()

    // Wipe old counts to cleanly recalculate fresh deduplicated counts
    _folderImageCounts.value = emptyMap()

    val globalSeenFiles = mutableSetOf<String>()

    for (uriStr in savedUris) {
      val uri = Uri.parse(uriStr)
      scanAndAddFolder(uri, context, globalSeenFiles)
    }
  }

  private suspend fun cleanupMissingImages() {
    val context = getApplication<Application>()
    val allEntries = db.getAllEntries()
    var deletedCount = 0
    for (entry in allEntries) {
      if (entry.imageUri.isNotBlank()) {
        val stillExists =
            try {
              context.contentResolver.openAssetFileDescriptor(Uri.parse(entry.imageUri), "r")?.use {
                true
              } == true
            } catch (e: Exception) {
              false
            }
        if (!stillExists) {
          db.deleteEntry(entry.id)
          deletedCount++
        }
      }
    }
    if (deletedCount > 0) {
      Log.d(TAG, "Removed $deletedCount missing entries from DB")
      refreshEntries()
    }
  }

  fun refreshImages() {
    _isRefreshing.value = true
    viewModelScope.launch(Dispatchers.IO) {
      val savedUris = prefs.getStringSet("saved_folder_uris", emptySet())
      if (!savedUris.isNullOrEmpty()) {
        refreshImagesInternal()
      }
      cleanupMissingImages()

      withContext(Dispatchers.Main) { _isRefreshing.value = false }
    }
  }

  fun analyzeUnprocessed() {
    if (!_isModelReady.value || isAnalyzing.get()) {
      return
    }
    _isAnalysisPaused.value = false
    launchAnalysisQueue()
  }

  fun forceAnalyzeUnprocessed() {
    if (!_isModelReady.value) {
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      isAnalyzing.set(false)
      analysisJob?.join()
      val all = db.getAllEntries()
      var fixed = 0
      for (entry in all) {
        if (entry.isAnalyzing) {
          db.setAnalyzing(entry.id, false)
          fixed++
        }
      }
      if (fixed > 0) {
        Log.d(TAG, "Force reset isAnalyzing flag for $fixed entries")
      }
      refreshEntries()

      withContext(Dispatchers.Main) {}
      _isAnalysisPaused.value = false
      launchAnalysisQueue()
    }
  }

  private suspend fun startAnalysisQueue() {
    if (!isAnalyzing.compareAndSet(false, true)) {
      Log.d(TAG, "Analysis already in progress, skipping")
      return
    }

    try {
      var allUnprocessed =
          db.getAllEntries().filter {
            it.analyzedAt == 0L && !it.isAnalyzing && it.imageUri.isNotBlank()
          }
      if (allUnprocessed.isEmpty()) {
        return
      }

      val total = allUnprocessed.size
      val concurrency = _analysisInstanceCount.value.coerceIn(1, 5)
      Log.d(TAG, "Starting analysis queue: $total entries, concurrency=$concurrency")

      if (concurrency <= 1) {
        // Sequential mode (original behavior)
        var counts = 0

        while (counts < total) {
          if (!isAnalyzing.get()) break
          // Fetch the latest unanalyzed image from DB again to handle new inserts dynamically
          val currentUnprocessed =
              db.getAllEntries()
                  .filter { it.analyzedAt == 0L && !it.isAnalyzing && it.imageUri.isNotBlank() }
                  .let { list ->
                    if (_isSortDescending.value) list.sortedByDescending { it.sortKey }
                    else list.sortedBy { it.sortKey }
                  }

          if (currentUnprocessed.isEmpty()) break

          val entry = currentUnprocessed.first()
          val remaining = total - counts
          _analysisProgress.value = remaining to total
          Log.d(TAG, "Analyzing $remaining/$total: id=${entry.id}")

          val success = analyzeEntrySuspend(entry)

          counts++

          if (!success) {
            Log.w(TAG, "Analysis failed for id=${entry.id}, continuing to next...")
          }

          // Allow underlying C++ LiteRT engine time to gracefully finalize callback threads
          // naturally before the next iteration rips it away
          Log.d(TAG, "Delaying 2s to allow JNI engine teardown...")
          kotlinx.coroutines.delay(2000L)
        }
      } else {
        // Concurrent mode: process multiple entries simultaneously using a channel
        val processed = java.util.concurrent.atomic.AtomicInteger(0)

        kotlinx.coroutines.coroutineScope {
          val channel = Channel<ScreenshotEntry>()

          // Producer: feed entries into channel
          launch {
            val sortedUnprocessed =
                if (_isSortDescending.value) {
                  allUnprocessed.sortedByDescending { it.sortKey }
                } else {
                  allUnprocessed.sortedBy { it.sortKey }
                }
            for (entry in sortedUnprocessed) {
              if (!isAnalyzing.get()) {
                channel.close()
                return@launch
              }
              channel.send(entry)
            }
            channel.close()
          }

          // N workers consuming from channel
          val jobs = mutableListOf<kotlinx.coroutines.Job>()
          for (workerId in 1..concurrency) {
            jobs.add(
                launch(Dispatchers.IO) {
                  for (entry in channel) {
                    if (!isAnalyzing.get()) break
                    val current = processed.incrementAndGet()
                    val remaining = total - current + 1
                    _analysisProgress.value = remaining to total
                    Log.d(TAG, "Analyzing $remaining/$total: id=${entry.id} (worker-$workerId)")

                    val success = analyzeEntrySuspend(entry, useConcurrent = true)

                    if (!success) {
                      Log.w(TAG, "Analysis failed for id=${entry.id}, continuing to next...")
                    }

                    kotlinx.coroutines.delay(2000L)
                  }
                })
          }

          for (job in jobs) {
            job.join()
          }
        }
      }

      _analysisProgress.value = 0 to total

      // Clear progress after a moment
      kotlinx.coroutines.delay(1500)
      _analysisProgress.value = null
    } finally {
      isAnalyzing.set(false)
    }
  }

  /**
   * Analyze a single entry, suspending until complete. Returns true on success, false on failure.
   *
   * @param useConcurrent If true, uses the engine pool for true parallel analysis (no mutex
   *   blocking).
   */
  private suspend fun analyzeEntrySuspend(
      entry: ScreenshotEntry,
      useConcurrent: Boolean = false
  ): Boolean {
    db.setAnalyzing(entry.id, true)
    if (useConcurrent) refreshEntriesDebounced() else refreshEntries()
    _currentImageProgress.value = 0f
    if (useConcurrent) _entryProgressMap.value = _entryProgressMap.value + (entry.id to 0f)

    val bitmap = loadBitmap(Uri.parse(entry.imageUri))
    if (bitmap == null) {
      db.setAnalyzing(entry.id, false)
      refreshEntries()
      return false
    }

    val targetLang =
        when (_analysisLanguage.value) {
          "en" -> "English"
          "zh-rTW" -> "Traditional Chinese"
          "hi" -> "Hindi"
          "es" -> "Spanish"
          "ar" -> "Arabic"
          "fr" -> "French"
          "ru" -> "Russian"
          else -> "English"
        }

    val onProgress: (Float) -> Unit = { progress ->
      _currentImageProgress.value = progress
      if (useConcurrent) {
        _entryProgressMap.value = _entryProgressMap.value + (entry.id to progress)
      }
    }
    val onResult: (String, String, String) -> Unit = { summary, tags, modelUsed ->
      viewModelScope.launch(Dispatchers.IO) {
        db.updateAnalysis(entry.id, summary, tags, modelUsed)
        if (useConcurrent) {
          _entryProgressMap.value = _entryProgressMap.value - entry.id
          refreshEntriesDebounced()
        } else {
          refreshEntries()
        }
        Log.d(TAG, "Analysis complete: id=${entry.id}")
      }
    }
    val onError: (String) -> Unit = { error ->
      viewModelScope.launch(Dispatchers.IO) {
        db.setAnalyzing(entry.id, false)
        if (useConcurrent) {
          _entryProgressMap.value = _entryProgressMap.value - entry.id
          refreshEntriesDebounced()
        } else {
          refreshEntries()
        }
        Log.e(TAG, "Analysis failed for ${entry.id}: $error")
      }
    }

    val result =
        kotlinx.coroutines.withTimeoutOrNull(300_000L) {
          suspendCancellableCoroutine<Boolean> { continuation ->
            if (useConcurrent) {
              llmManager.analyzeScreenshotConcurrent(
                  bitmap = bitmap,
                  detailLevel = _detailLevel.value,
                  customPrompt = _customPrompt.value.takeIf { it.isNotBlank() },
                  targetLanguage = targetLang,
                  imageResolution = _imageResolution.value,
                  onProgress = onProgress,
                  onResult = { s, t, m ->
                    onResult(s, t, m)
                    if (continuation.isActive) continuation.resume(true)
                  },
                  onError = { e ->
                    onError(e)
                    if (continuation.isActive) continuation.resume(false)
                  },
              )
            } else {
              llmManager.analyzeScreenshot(
                  bitmap = bitmap,
                  detailLevel = _detailLevel.value,
                  customPrompt = _customPrompt.value.takeIf { it.isNotBlank() },
                  targetLanguage = targetLang,
                  imageResolution = _imageResolution.value,
                  onProgress = onProgress,
                  onResult = { s, t, m ->
                    onResult(s, t, m)
                    if (continuation.isActive) continuation.resume(true)
                  },
                  onError = { e ->
                    onError(e)
                    if (continuation.isActive) continuation.resume(false)
                  },
              )
            }
          }
        }

    if (result == null) {
      // Un-hang the database if timeout occurred
      db.setAnalyzing(entry.id, false)
      refreshEntries()
      Log.e(TAG, "Analysis timed out after 300s for id=${entry.id}")
      return false
    }

    return result
  }

  /** Re-analyze a single entry (triggered from Detail screen). */
  fun analyzeEntry(entry: ScreenshotEntry) {
    if (!_isModelReady.value) return
    viewModelScope.launch(Dispatchers.IO) {
      _analysisProgress.value = 1 to 1
      analyzeEntrySuspend(entry)
      _analysisProgress.value = null
    }
  }

  fun updateSummary(
      id: Long,
      summary: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateSummary(id, summary)
      refreshEntries()
    }
  }

  fun updateTags(
      id: Long,
      tags: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateTags(id, tags)
      refreshEntries()
    }
  }

  fun deleteEntry(id: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      db.deleteEntry(id)
      refreshEntries()
    }
  }

  fun updateNote(
      id: Long,
      note: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateNote(id, note)
      refreshEntries()
    }
  }

  fun getEntryById(id: Long): ScreenshotEntry? {
    return db.getEntryById(id)
  }

  fun getStats(): Pair<Int, Int> {
    return db.getEntryCount() to db.getAnalyzedCount()
  }

  fun exportData(uri: Uri) {
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      val success = DataManager.exportToUri(context, uri, db)
      withContext(Dispatchers.Main) {}
    }
  }

  fun importData(uri: Uri) {
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      val count = DataManager.importFromUri(context, uri, db)
      refreshEntries()
      withContext(Dispatchers.Main) {}
    }
  }

  private fun loadBitmap(uri: Uri): Bitmap? {
    val context = getApplication<Application>()
    return try {
      val targetSize = _imageResolution.value // Configurable resolution for AI analysis
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          decoder.isMutableRequired = true

          // Calculate sample size for initial downsampling
          val width = info.size.width
          val height = info.size.height
          var sampleSize = 1
          while (width / (sampleSize * 2) >= targetSize &&
              height / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
          }
          if (sampleSize > 1) {
            decoder.setTargetSampleSize(sampleSize)
          }
        }
      } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
          BitmapFactory.decodeStream(it, null, options)
        }

        var sampleSize = 1
        while (options.outWidth / (sampleSize * 2) >= targetSize &&
            options.outHeight / (sampleSize * 2) >= targetSize) {
          sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use {
          BitmapFactory.decodeStream(it, null, decodeOptions)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load bitmap from $uri", e)
      null
    }
  }

  override fun onCleared() {
    super.onCleared()
    llmManager.close()
  }
}
