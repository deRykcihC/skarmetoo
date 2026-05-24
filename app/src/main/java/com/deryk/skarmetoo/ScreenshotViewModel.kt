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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.data.DataManager
import com.deryk.skarmetoo.data.ImageHasher
import com.deryk.skarmetoo.data.JsonFolderManager
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.google.mlkit.genai.common.FeatureStatus
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
import kotlinx.coroutines.withTimeout

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
  GGUF("", "GGUF Model"),
  AICORE("", "Gemini Nano"),
}

class ScreenshotViewModel(application: Application) : AndroidViewModel(application) {
  private val db = ScreenshotDatabase(application)
  val llmManager = LlmManager.getInstance(application)
  private val ggufManager = GgufLlmManager.getInstance(application)
  private val aicoreManager = AicoreManager.getInstance(application)
  private val currentAppVersionCode =
      try {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        @Suppress("DEPRECATION") packageInfo.longVersionCode
      } catch (_: Exception) {
        0L
      }
  private val prefs =
      application.getSharedPreferences("skarmetoo_prefs", android.content.Context.MODE_PRIVATE)
  private val onboardingPrefs =
      application.getSharedPreferences(
          "skarmetoo_onboarding_prefs", android.content.Context.MODE_PRIVATE)

  private val _entries = MutableStateFlow<List<ScreenshotEntry>>(emptyList())
  val entries: StateFlow<List<ScreenshotEntry>> = _entries.asStateFlow()

  private val _totalImageCount = MutableStateFlow(0)
  val totalImageCount: StateFlow<Int> = _totalImageCount.asStateFlow()

  private val _analyzedImageCount = MutableStateFlow(0)
  val analyzedImageCount: StateFlow<Int> = _analyzedImageCount.asStateFlow()

  private val _pendingImageCount = MutableStateFlow(0)
  val pendingImageCount: StateFlow<Int> = _pendingImageCount.asStateFlow()

  private val _analyzingImageCount = MutableStateFlow(0)
  val analyzingImageCount: StateFlow<Int> = _analyzingImageCount.asStateFlow()

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

  // Stored cached status for AICore to avoid querying Play Services on launch
  private val _aicoreCachedStatus =
      MutableStateFlow<Int>(prefs.getInt("aicore_cached_status", -999))
  val aicoreCachedStatus: StateFlow<Int> = _aicoreCachedStatus.asStateFlow()

  private val _downloadProgress = MutableStateFlow(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _currentImageProgress = MutableStateFlow(0f)
  val currentImageProgress: StateFlow<Float> = _currentImageProgress.asStateFlow()

  // Stays true for the whole batch queue, not per-entry
  private val _isAnalysisRunning = MutableStateFlow(false)
  val isAnalysisRunning: StateFlow<Boolean> = _isAnalysisRunning.asStateFlow()

  // Per-entry progress for concurrent analysis — maps entry ID to progress float
  private val _entryProgressMap = MutableStateFlow<Map<Long, Float>>(emptyMap())
  val entryProgressMap: StateFlow<Map<Long, Float>> = _entryProgressMap.asStateFlow()

  // Dedicated set of entry IDs that are actively being analyzed. Updated synchronously
  // at the start/end of each entry's analysis so the UI never flickers the progress bar.
  private val _activeAnalysisIds = MutableStateFlow<Set<Long>>(emptySet())
  val activeAnalysisIds: StateFlow<Set<Long>> = _activeAnalysisIds.asStateFlow()

  // Debounce refreshEntries to avoid UI jank from rapid calls during concurrent analysis
  private val _refreshRequest = MutableStateFlow(System.currentTimeMillis())
  private var refreshDebounceJob: kotlinx.coroutines.Job? = null

  private val _isRefreshing = MutableStateFlow(false)
  val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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

  private val isSwitchingModel = java.util.concurrent.atomic.AtomicBoolean(false)
  private val analysisSessionToken = java.util.concurrent.atomic.AtomicLong(0L)
  private var loadingStatusJob: kotlinx.coroutines.Job? = null
  private val loadingStatusDelayMs = 250L
  private var modelSwitchStatusJob: kotlinx.coroutines.Job? = null
  private var modelSwitchStatusTarget: ModelType? = null
  private val modelSwitchObservedLoading = java.util.concurrent.atomic.AtomicBoolean(false)
  private val modelSwitchStatusTimeoutMs = 45000L
  private val _isModelSwitchTransitionLoading = MutableStateFlow(false)
  val isModelSwitchTransitionLoading: StateFlow<Boolean> =
      _isModelSwitchTransitionLoading.asStateFlow()

  private val _jsonSaveFolderUri = MutableStateFlow<Uri?>(null)
  val jsonSaveFolderUri: StateFlow<Uri?> = _jsonSaveFolderUri.asStateFlow()

  private val _jsonSaveFolderName = MutableStateFlow<String?>(null)
  val jsonSaveFolderName: StateFlow<String?> = _jsonSaveFolderName.asStateFlow()

  private val _jsonLastBackupFilename = MutableStateFlow<String?>(null)
  val jsonLastBackupFilename: StateFlow<String?> = _jsonLastBackupFilename.asStateFlow()

  private val _jsonLastBackupTime = MutableStateFlow<String?>(null)
  val jsonLastBackupTime: StateFlow<String?> = _jsonLastBackupTime.asStateFlow()

  private fun getFolderFriendlyName(uri: Uri): String {
    val context = getApplication<Application>()
    return try {
      val documentFile = DocumentFile.fromTreeUri(context, uri)
      documentFile?.name ?: uri.path?.substringAfterLast("/") ?: "External Folder"
    } catch (e: Exception) {
      uri.path?.substringAfterLast("/") ?: "External Folder"
    }
  }

  fun setJsonSaveFolderUri(uri: Uri?) {
    _jsonSaveFolderUri.value = uri
    _jsonSaveFolderName.value = uri?.let { getFolderFriendlyName(it) }
    if (uri != null) {
      prefs.edit().putString("json_save_folder_uri", uri.toString()).apply()
      // Trigger a sync immediately
      syncWithExternalFolder()
    } else {
      prefs.edit().remove("json_save_folder_uri").apply()
      prefs.edit().remove("json_last_backup_filename").apply()
      prefs.edit().remove("json_last_backup_time").apply()
      _jsonLastBackupFilename.value = null
      _jsonLastBackupTime.value = null
    }
  }

  fun saveDatabaseToExternalFolder() {
    val uri = _jsonSaveFolderUri.value ?: return
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      val filename = JsonFolderManager.saveDatabaseToFolder(context, uri, db)
      if (filename != null) {
        _jsonLastBackupFilename.value = filename
        prefs.edit().putString("json_last_backup_filename", filename).apply()

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val timeStr = sdf.format(java.util.Date())
        _jsonLastBackupTime.value = timeStr
        prefs.edit().putString("json_last_backup_time", timeStr).apply()
      }
    }
  }

  fun syncWithExternalFolder() {
    val uri = _jsonSaveFolderUri.value ?: return
    val context = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
      try {
        JsonFolderManager.importEntriesFromFolder(context, uri, db)
        val filename = JsonFolderManager.saveDatabaseToFolder(context, uri, db)
        if (filename != null) {
          _jsonLastBackupFilename.value = filename
          prefs.edit().putString("json_last_backup_filename", filename).apply()

          val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
          val timeStr = sdf.format(java.util.Date())
          _jsonLastBackupTime.value = timeStr
          prefs.edit().putString("json_last_backup_time", timeStr).apply()
        }
        refreshEntries()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to sync with external folder", e)
      }
    }
  }

  private fun setModelStatusImmediate(status: String) {
    loadingStatusJob?.cancel()
    loadingStatusJob = null
    _modelStatus.value = status
  }

  private fun setLoadingStatusDelayed(expectedModel: ModelType) {
    loadingStatusJob?.cancel()
    loadingStatusJob =
        viewModelScope.launch(Dispatchers.Main) {
          delay(loadingStatusDelayMs)
          if (_selectedModel.value == expectedModel) {
            _modelStatus.value = "Loading model..."
          }
        }
  }

  private fun beginModelSwitchStatus(target: ModelType) {
    modelSwitchStatusJob?.cancel()
    modelSwitchStatusTarget = target
    modelSwitchObservedLoading.set(false)
    _isModelSwitchTransitionLoading.value = true
    setModelStatusImmediate("Loading model...")
    modelSwitchStatusJob =
        viewModelScope.launch(Dispatchers.Main) {
          delay(modelSwitchStatusTimeoutMs)
          if (modelSwitchStatusTarget == target && _selectedModel.value == target) {
            clearModelSwitchStatus(target)
            if (_isModelFound.value) {
              setModelStatusImmediate("Please load a model")
            } else {
              setModelStatusImmediate("Model not found")
            }
          }
        }
  }

  private fun clearModelSwitchStatus(target: ModelType? = null) {
    if (target == null || modelSwitchStatusTarget == target) {
      modelSwitchStatusJob?.cancel()
      modelSwitchStatusJob = null
      modelSwitchStatusTarget = null
      modelSwitchObservedLoading.set(false)
      _isModelSwitchTransitionLoading.value = false
    }
  }

  private fun isModelSwitchStatusActive(target: ModelType): Boolean {
    return modelSwitchStatusTarget == target
  }

  private fun invalidateAnalysisSession(reason: String): Long {
    val token = analysisSessionToken.incrementAndGet()
    Log.d(TAG, "Invalidated analysis session: token=$token reason=$reason")
    return token
  }

  private fun isAnalysisSessionActive(token: Long): Boolean = analysisSessionToken.get() == token

  /**
   * Stops queue workers and closes both model backends before switching model type.
   *
   * This prevents stale callbacks from old model instances from writing results after a user
   * switches to a different model.
   */
  private suspend fun stopAllAnalysisForModelSwitch(reason: String) {
    val token = invalidateAnalysisSession(reason)
    loadingStatusJob?.cancel()
    loadingStatusJob = null
    isAnalyzing.set(false)
    _isAnalysisRunning.value = false
    _analysisProgress.value = null
    _currentImageProgress.value = 0f
    _activeAnalysisIds.value = emptySet()
    _entryProgressMap.value = emptyMap()

    analysisJob?.cancel()
    try {
      analysisJob?.let { withTimeout(3000L) { it.join() } }
    } catch (_: Exception) {}
    analysisJob = null

    try {
      withTimeout(5000L) { llmManager.closeAndWait() }
    } catch (e: Exception) {
      Log.w(TAG, "Timed out waiting for Gemma manager close; falling back to async close", e)
      llmManager.close()
    }
    try {
      withTimeout(5000L) { ggufManager.closeAndWait() }
    } catch (e: Exception) {
      Log.w(TAG, "Timed out waiting for GGUF manager close; falling back to async close", e)
      ggufManager.close()
    }
    db.resetAllAnalyzingFlags()
    stopAnalysisService()
    refreshEntries()
    Log.d(TAG, "Stopped all active analysis before model switch (token=$token)")
  }

  private fun launchAnalysisQueue() {
    if (isSwitchingModel.get()) {
      Log.d(TAG, "Skipped queue launch: model switch in progress")
      return
    }
    if (_selectedModel.value == ModelType.GGUF) {
      if (ggufManager.isInferenceRunning.value) {
        Log.w(TAG, "Skipped queue launch: GGUF instance is already running")
        return
      }
      _analysisInstanceCount.value = 1
    }
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

  private val _backgroundProcessEnabled =
      MutableStateFlow(prefs.getBoolean("background_process_enabled", false))
  val backgroundProcessEnabled: StateFlow<Boolean> = _backgroundProcessEnabled.asStateFlow()

  fun setBackgroundProcessEnabled(enabled: Boolean) {
    _backgroundProcessEnabled.value = enabled
    prefs.edit().putBoolean("background_process_enabled", enabled).apply()
    if (!enabled) {
      stopAnalysisService()
    }
  }

  private val _isAnalysisPaused = MutableStateFlow(false)
  val isAnalysisPaused: StateFlow<Boolean> = _isAnalysisPaused.asStateFlow()

  fun toggleAnalysisPause() {
    val newPaused = !_isAnalysisPaused.value
    _isAnalysisPaused.value = newPaused
    if (newPaused) {
      viewModelScope.launch(Dispatchers.IO) {
        isAnalyzing.set(false)
        _activeAnalysisIds.value = emptySet()
        _entryProgressMap.value = emptyMap()
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

  private val _galleryPageSize = MutableStateFlow(prefs.getInt("gallery_page_size", 10))
  val galleryPageSize: StateFlow<Int> = _galleryPageSize.asStateFlow()

  fun setGalleryPageSize(value: Int) {
    _galleryPageSize.value = value
    prefs.edit().putInt("gallery_page_size", value).apply()
  }

  /**
   * Force apply advanced settings (image resolution / concurrency) by cancelling any running
   * analysis and restarting the queue so that new values take effect immediately.
   */
  fun applyAdvancedSettings() {
    viewModelScope.launch(Dispatchers.IO) {
      // 1. Kill any active background scanning, reset database flags, and close/teardown active
      // model runtimes
      stopAllAnalysisForModelSwitch("applyAdvancedSettings")

      val context = getApplication<Application>()
      val model = _selectedModel.value

      // 2. Re-initialize / restart the model engine using the new advanced parameters
      if (model == ModelType.GEMMA_3N || model == ModelType.GEMMA_4) {
        val modelPath = java.io.File(context.filesDir, model.fileName)
        if (modelPath.exists()) {
          initializeModel(modelPath.absolutePath, isGemma4 = model == ModelType.GEMMA_4)
        }
      } else if (model == ModelType.GGUF) {
        val lastGgufFile = prefs.getString("last_gguf_model", null)
        val modelInfo =
            ggufManager.getDownloadedModels().find { it.fileName == lastGgufFile }
                ?: ggufManager.getDownloadedModels().firstOrNull()
        if (modelInfo != null) {
          ggufManager.loadModel(modelInfo)
        }
      } else if (model == ModelType.AICORE) {
        triggerAicoreRescan()
      }

      // 3. Restart the background scanner queue to process unprocessed images with the new settings
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
  private val onboardingVersionKey = "last_seen_onboarding_version_code"

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
    onboardingPrefs
        .edit()
        .putLong(onboardingVersionKey, if (seen) currentAppVersionCode else 0L)
        .apply()
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
    val lastSeenOnboardingVersion = onboardingPrefs.getLong(onboardingVersionKey, 0L)
    _hasSeenOnboarding.value = lastSeenOnboardingVersion >= currentAppVersionCode

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

    // Restore external save folder for JSON backup
    val savedJsonFolderUriString = prefs.getString("json_save_folder_uri", null)
    _jsonSaveFolderUri.value = savedJsonFolderUriString?.let { Uri.parse(it) }
    _jsonSaveFolderName.value = _jsonSaveFolderUri.value?.let { getFolderFriendlyName(it) }
    _jsonLastBackupFilename.value = prefs.getString("json_last_backup_filename", null)
    _jsonLastBackupTime.value = prefs.getString("json_last_backup_time", null)

    // Trigger initial scan for AICore status once on very first launch
    if (_aicoreCachedStatus.value == -999) {
      triggerAicoreRescan()
    }

    viewModelScope.launch {
      kotlinx.coroutines.flow
          .combine(_selectedModel, llmManager.uiState, ggufManager.uiState, _aicoreCachedStatus) {
              selected,
              llmState,
              ggufState,
              cachedAicoreStatus ->
            if (selected == ModelType.AICORE) {
              if (cachedAicoreStatus == FeatureStatus.AVAILABLE) {
                clearModelSwitchStatus(ModelType.AICORE)
                setModelStatusImmediate("Ready (AICore)")
                _isModelReady.value = true
                launchAnalysisQueue()
              } else if (cachedAicoreStatus == FeatureStatus.DOWNLOADING) {
                setModelStatusImmediate("AICore downloading...")
                _isModelReady.value = false
              } else {
                setModelStatusImmediate("AICore unsupported")
                _isModelReady.value = false
              }
            } else if (selected == ModelType.GGUF) {
              when (ggufState) {
                is GgufLlmManager.LlmState.Initial -> {
                  if (isModelSwitchStatusActive(ModelType.GGUF)) {
                    setModelStatusImmediate("Loading model...")
                  } else if (_modelStatus.value != "Ready") {
                    setModelStatusImmediate("Please load a model")
                  }
                  _isModelReady.value = false
                }
                is GgufLlmManager.LlmState.Loading -> {
                  if (isModelSwitchStatusActive(ModelType.GGUF)) {
                    modelSwitchObservedLoading.set(true)
                    setModelStatusImmediate("Loading model...")
                  } else {
                    setLoadingStatusDelayed(ModelType.GGUF)
                  }
                  _isModelReady.value = false
                }
                is GgufLlmManager.LlmState.Ready -> {
                  if (isModelSwitchStatusActive(ModelType.GGUF) &&
                      !modelSwitchObservedLoading.get()) {
                    setModelStatusImmediate("Loading model...")
                    _isModelReady.value = false
                    return@combine
                  }
                  clearModelSwitchStatus(ModelType.GGUF)
                  setModelStatusImmediate("Ready (GGUF)")
                  _isModelReady.value = true
                  launchAnalysisQueue()
                }
                is GgufLlmManager.LlmState.Generating -> {
                  if (isModelSwitchStatusActive(ModelType.GGUF) &&
                      !modelSwitchObservedLoading.get()) {
                    setModelStatusImmediate("Loading model...")
                    _isModelReady.value = false
                    return@combine
                  }
                  clearModelSwitchStatus(ModelType.GGUF)
                  setModelStatusImmediate("Analyzing...")
                  _isModelReady.value = true
                }
                is GgufLlmManager.LlmState.Error -> {
                  if (isModelSwitchStatusActive(ModelType.GGUF)) {
                    setModelStatusImmediate("Loading model...")
                  } else {
                    setModelStatusImmediate(
                        "Error: ${(ggufState as GgufLlmManager.LlmState.Error).message}")
                  }
                  _isModelReady.value = false
                }
              }
            } else {
              when (llmState) {
                is LlmManager.LlmState.Initial -> {
                  if (isModelSwitchStatusActive(selected)) {
                    setModelStatusImmediate("Loading model...")
                  } else if (_modelStatus.value != "Ready") {
                    setModelStatusImmediate("Please load a model")
                  }
                  _isModelReady.value = false
                }
                is LlmManager.LlmState.Loading -> {
                  if (isModelSwitchStatusActive(selected)) {
                    modelSwitchObservedLoading.set(true)
                    setModelStatusImmediate("Loading model...")
                  } else {
                    setLoadingStatusDelayed(selected)
                  }
                  _isModelReady.value = false
                }
                is LlmManager.LlmState.Ready -> {
                  if (isModelSwitchStatusActive(selected) && !modelSwitchObservedLoading.get()) {
                    setModelStatusImmediate("Loading model...")
                    _isModelReady.value = false
                    return@combine
                  }
                  clearModelSwitchStatus(selected)
                  setModelStatusImmediate("Ready (Gemma)")
                  _isModelReady.value = true
                  launchAnalysisQueue()
                }
                is LlmManager.LlmState.Generating -> {
                  if (isModelSwitchStatusActive(selected) && !modelSwitchObservedLoading.get()) {
                    setModelStatusImmediate("Loading model...")
                    _isModelReady.value = false
                    return@combine
                  }
                  clearModelSwitchStatus(selected)
                  setModelStatusImmediate("Analyzing...")
                  _isModelReady.value = true
                }
                is LlmManager.LlmState.Error -> {
                  if (isModelSwitchStatusActive(selected)) {
                    setModelStatusImmediate("Loading model...")
                  } else {
                    setModelStatusImmediate(
                        "Error: ${(llmState as LlmManager.LlmState.Error).message}")
                  }
                  _isModelReady.value = false
                }
              }
            }
          }
          .collect {
            // Flow emission processed in the block
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
        if (selected == ModelType.GGUF) {
          val lastGgufFile = prefs.getString("last_gguf_model", null)
          if (lastGgufFile != null) {
            val allModels = LFM2_5_VARIANTS + PRESET_GGUF_MODELS
            val modelInfo = allModels.find { it.fileName == lastGgufFile }
            if (modelInfo != null) {
              ggufManager.loadModel(modelInfo)
            }
          }
        } else {
          val selectedModelFile = java.io.File(application.filesDir, selected.fileName)
          if (selectedModelFile.exists()) {
            initializeModel(
                selectedModelFile.absolutePath, isGemma4 = selected == ModelType.GEMMA_4)
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
    if (_selectedModel.value == model) return
    viewModelScope.launch(Dispatchers.IO) {
      isSwitchingModel.set(true)
      try {
        // Update selection immediately so the UI frame highlights without waiting for teardown.
        _selectedModel.value = model
        prefs.edit().putString("selected_model", model.name).apply()
        beginModelSwitchStatus(model)
        stopAllAnalysisForModelSwitch("setSelectedModel:${model.name}")
        withContext(Dispatchers.Main) {}
        checkModelExists()
        if (model != ModelType.AICORE) {
          val modelPath = java.io.File(getApplication<Application>().filesDir, model.fileName)
          if (modelPath.exists()) {
            initializeModel(modelPath.absolutePath, isGemma4 = model == ModelType.GEMMA_4)
          } else {
            refreshModelDownloadStatus()
          }
        } else {
          triggerAicoreRescan()
        }
      } finally {
        isSwitchingModel.set(false)
      }
    }
  }

  fun triggerAicoreRescan() {
    viewModelScope.launch(Dispatchers.IO) {
      val status = aicoreManager.checkStatus()
      _aicoreCachedStatus.value = status
      prefs.edit().putInt("aicore_cached_status", status).apply()

      // Update model ready states dynamically if currently selected model is AICORE
      if (_selectedModel.value == ModelType.AICORE) {
        if (status == FeatureStatus.AVAILABLE) {
          clearModelSwitchStatus(ModelType.AICORE)
          setModelStatusImmediate("Ready (AICore)")
          _isModelReady.value = true
          _isModelFound.value = true
          launchAnalysisQueue()
        } else if (status == FeatureStatus.DOWNLOADING) {
          setModelStatusImmediate("AICore downloading...")
          _isModelReady.value = false
          _isModelFound.value = false
        } else {
          setModelStatusImmediate("AICore unsupported")
          _isModelReady.value = false
          _isModelFound.value = false
        }
      }
    }
  }

  fun setGgufModelAsActive(modelInfo: GgufModelInfo) {
    viewModelScope.launch(Dispatchers.IO) {
      isSwitchingModel.set(true)
      try {
        // Update selection immediately so the GGUF frame responds instantly to user tap.
        _selectedModel.value = ModelType.GGUF
        prefs.edit().putString("selected_model", ModelType.GGUF.name).apply()
        prefs.edit().putString("last_gguf_model", modelInfo.fileName).apply()
        beginModelSwitchStatus(ModelType.GGUF)
        stopAllAnalysisForModelSwitch("setGgufModelAsActive:${modelInfo.fileName}")
        setAnalysisInstanceCount(1)
        _analysisInstanceCount.value = 1
        withContext(Dispatchers.Main) {}
        checkModelExists()
        ggufManager.loadModel(modelInfo)
      } finally {
        isSwitchingModel.set(false)
      }
    }
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
      val exists =
          if (_selectedModel.value == ModelType.GGUF) {
            ggufManager.getDownloadedModels().isNotEmpty()
          } else if (_selectedModel.value == ModelType.AICORE) {
            _aicoreCachedStatus.value == FeatureStatus.AVAILABLE
          } else {
            val selectedFile = java.io.File(context.filesDir, _selectedModel.value.fileName)
            selectedFile.exists()
          }
      _isModelFound.value = exists
      if (!exists) {
        if (!isModelSwitchStatusActive(_selectedModel.value)) {
          setModelStatusImmediate("Model not found")
        }
        _isModelReady.value = false
      } else if (_modelStatus.value == "Model not found") {
        setModelStatusImmediate("Please load a model")
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
      val all = db.getAllEntries()
      _totalImageCount.value = all.size
      _analyzedImageCount.value = all.count { it.summary.isNotBlank() }
      _pendingImageCount.value = all.count { it.summary.isBlank() && !it.isAnalyzing }
      _analyzingImageCount.value = all.count { it.isAnalyzing }
      val query = _searchQuery.value
      val result =
          if (query.isBlank()) {
            all
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
          val all = db.getAllEntries()
          _totalImageCount.value = all.size
          _analyzedImageCount.value = all.count { it.summary.isNotBlank() }
          _pendingImageCount.value = all.count { it.summary.isBlank() && !it.isAnalyzing }
          _analyzingImageCount.value = all.count { it.isAnalyzing }
          val query = _searchQuery.value
          val result =
              if (query.isBlank()) {
                all
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

        withContext(Dispatchers.Main) { setModelStatusImmediate("Downloaded to internal storage") }

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

  private val _galleryGridColumns = MutableStateFlow(prefs.getInt("gallery_grid_columns", 4))
  val galleryGridColumns: StateFlow<Int> = _galleryGridColumns.asStateFlow()

  fun setGalleryGridColumns(columns: Int) {
    val clamped = columns.coerceIn(4, 7)
    _galleryGridColumns.value = clamped
    prefs.edit().putInt("gallery_grid_columns", clamped).apply()
  }

  private val _galleryIsGalleryStyle =
      MutableStateFlow(prefs.getBoolean("gallery_is_gallery_style", false))
  val galleryIsGalleryStyle: StateFlow<Boolean> = _galleryIsGalleryStyle.asStateFlow()

  fun setGalleryIsGalleryStyle(value: Boolean) {
    _galleryIsGalleryStyle.value = value
    prefs.edit().putBoolean("gallery_is_gallery_style", value).apply()
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

  fun getOrCreateEntryForUri(uri: Uri, onResult: (Long) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      val uriString = uri.toString()
      val existing = db.getEntryByImageUri(uriString)
      if (existing != null) {
        withContext(Dispatchers.Main) { onResult(existing.id) }
        return@launch
      }

      val bitmap = loadBitmap(uri)
      val hash = if (bitmap != null) ImageHasher.computeDHash(bitmap) else ""

      if (hash.isNotEmpty()) {
        val hashExisting = db.getEntryByHash(hash)
        if (hashExisting != null) {
          if (hashExisting.imageUri.isBlank()) {
            db.linkImageToHash(hash, uriString)
            refreshEntries()
            withContext(Dispatchers.Main) { onResult(hashExisting.id) }
          } else {
            // Duplicate image with same hash. Copy already analyzed metadata to avoid re-analysis!
            val entry =
                ScreenshotEntry(
                    imageUri = uriString,
                    imageHash = hash,
                    summary = hashExisting.summary,
                    tags = hashExisting.tags,
                    analyzedAt = hashExisting.analyzedAt,
                    note = hashExisting.note,
                    modelUsed = hashExisting.modelUsed)
            val newId = db.insertEntry(entry)
            refreshEntries()
            withContext(Dispatchers.Main) { onResult(newId) }
          }
          return@launch
        }
      }

      val entry =
          ScreenshotEntry(
              imageUri = uriString,
              imageHash = hash,
          )
      val newId = db.insertEntry(entry)
      refreshEntries()

      if (_isModelReady.value) {
        launchAnalysisQueue()
      }

      withContext(Dispatchers.Main) { onResult(newId) }
    }
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
          if (existing != null) {
            if (existing.imageUri.isBlank()) {
              db.linkImageToHash(hash, uriString)
              Log.d(TAG, "Linked imported entry: $hash")
              continue
            } else {
              // Duplicate image with same hash. Copy already analyzed metadata to avoid
              // re-analysis!
              val entry =
                  ScreenshotEntry(
                      imageUri = uriString,
                      imageHash = hash,
                      summary = existing.summary,
                      tags = existing.tags,
                      analyzedAt = existing.analyzedAt,
                      note = existing.note,
                      modelUsed = existing.modelUsed)
              db.insertEntry(entry)
              Log.d(TAG, "Added duplicate screenshot with copied metadata: $hash")
              continue
            }
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

  /**
   * Called when the app returns to the foreground. If analysis was running but got stalled (e.g.,
   * LiteRT callbacks blocked by paused main looper), this resets the stuck state and restarts the
   * queue.
   */
  fun resumeAnalysisIfNeeded() {
    if (_isAnalysisPaused.value || !_isModelReady.value) return

    viewModelScope.launch(Dispatchers.IO) {
      val hasUnprocessed =
          db.getAllEntries().any { it.analyzedAt == 0L && it.imageUri.isNotBlank() }
      if (!hasUnprocessed) return@launch

      if (isAnalyzing.get()) {
        // Analysis was supposedly running but may be stalled — check if the job is still active
        val job = analysisJob
        if (job == null || !job.isActive) {
          // Job died silently; reset and restart
          Log.d(TAG, "resumeAnalysisIfNeeded: analysis job died, restarting")
          isAnalyzing.set(false)
          _isAnalysisRunning.value = false
          // Reset any stuck isAnalyzing flags in DB
          val all = db.getAllEntries()
          for (entry in all) {
            if (entry.isAnalyzing) {
              db.setAnalyzing(entry.id, false)
            }
          }
          _activeAnalysisIds.value = emptySet()
          _entryProgressMap.value = emptyMap()
          refreshEntries()
          launchAnalysisQueue()
        }
        // else: job is still active, analysis is genuinely running — do nothing
      } else {
        // Not analyzing but there are unprocessed items — start the queue
        Log.d(TAG, "resumeAnalysisIfNeeded: starting analysis for unprocessed items")
        // Reset any stuck isAnalyzing flags
        val all = db.getAllEntries()
        for (entry in all) {
          if (entry.isAnalyzing) {
            db.setAnalyzing(entry.id, false)
          }
        }
        refreshEntries()
        launchAnalysisQueue()
      }
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
      _activeAnalysisIds.value = emptySet()
      _entryProgressMap.value = emptyMap()
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
    val sessionToken = analysisSessionToken.get()

    try {
      var allUnprocessed =
          db.getAllEntries().filter {
            it.analyzedAt == 0L && !it.isAnalyzing && it.imageUri.isNotBlank()
          }
      if (allUnprocessed.isEmpty()) {
        return
      }
      _isAnalysisRunning.value = true

      val total = allUnprocessed.size
      val concurrency =
          if (_selectedModel.value == ModelType.GGUF) 1
          else _analysisInstanceCount.value.coerceIn(1, 5)
      Log.d(TAG, "Starting analysis queue: $total entries, concurrency=$concurrency")
      updateAnalysisService(total)

      if (concurrency <= 1) {
        // Sequential mode (original behavior)
        while (true) {
          if (!isAnalyzing.get() || !isAnalysisSessionActive(sessionToken)) break
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
          val remaining = currentUnprocessed.size
          _analysisProgress.value = remaining to total
          updateAnalysisService(remaining)
          Log.d(TAG, "Analyzing $remaining/$total: id=${entry.id}")

          val success = analyzeEntrySuspend(entry = entry, sessionToken = sessionToken)

          if (!success) {
            Log.w(TAG, "Analysis failed for id=${entry.id}, continuing to next...")
          }

          // Allow underlying C++ engine time to gracefully finalize callback threads
          // naturally before the next iteration rips it away
          Log.d(TAG, "Delaying 2s to allow JNI engine teardown...")
          _currentImageProgress.value = 0.1f
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
              if (!isAnalyzing.get() || !isAnalysisSessionActive(sessionToken)) {
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
                    if (!isAnalyzing.get() || !isAnalysisSessionActive(sessionToken)) break
                    val current = processed.incrementAndGet()
                    val remaining = total - current + 1
                    _analysisProgress.value = remaining to total
                    updateAnalysisService(remaining)
                    Log.d(TAG, "Analyzing $remaining/$total: id=${entry.id} (worker-$workerId)")

                    val success =
                        analyzeEntrySuspend(
                            entry = entry, useConcurrent = true, sessionToken = sessionToken)

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

      if (isAnalysisSessionActive(sessionToken)) {
        _analysisProgress.value = 0 to total

        // Clear progress after a moment
        kotlinx.coroutines.delay(1500)
        _analysisProgress.value = null
      } else {
        Log.d(TAG, "Skipped stale queue progress cleanup for token=$sessionToken")
      }
    } finally {
      if (isAnalysisSessionActive(sessionToken)) {
        isAnalyzing.set(false)
        _isAnalysisRunning.value = false
        stopAnalysisService()
      } else {
        Log.d(TAG, "Skipped stale queue finalizer for token=$sessionToken")
      }
    }
  }

  @Volatile private var isServiceRunning = false

  private fun updateAnalysisService(remaining: Int) {
    if (!_backgroundProcessEnabled.value) return
    val context = getApplication<Application>()
    if (remaining <= 0) {
      stopAnalysisService()
      return
    }
    val intent =
        android.content.Intent(context, AnalysisService::class.java).apply {
          action =
              if (isServiceRunning) AnalysisService.ACTION_UPDATE else AnalysisService.ACTION_START
          putExtra(AnalysisService.EXTRA_REMAINING, remaining)
        }
    try {
      if (!isServiceRunning) {
        isServiceRunning = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } else {
        context.startService(intent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update AnalysisService", e)
      isServiceRunning = false
    }
  }

  private fun stopAnalysisService() {
    if (!isServiceRunning) return
    isServiceRunning = false
    val context = getApplication<Application>()
    val intent =
        android.content.Intent(context, AnalysisService::class.java).apply {
          action = AnalysisService.ACTION_STOP
        }
    try {
      context.startService(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop AnalysisService", e)
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
      useConcurrent: Boolean = false,
      sessionToken: Long = analysisSessionToken.get(),
  ): Boolean {
    if (!isAnalysisSessionActive(sessionToken)) {
      Log.d(TAG, "Skipping stale analysis start for id=${entry.id}, token=$sessionToken")
      return false
    }
    db.setAnalyzing(entry.id, true)
    _activeAnalysisIds.value = _activeAnalysisIds.value + entry.id
    _entryProgressMap.value = _entryProgressMap.value + (entry.id to 0.1f)
    if (useConcurrent) {
      refreshEntriesDebounced()
    } else {
      refreshEntries()
    }

    val bitmap = loadBitmap(Uri.parse(entry.imageUri))
    if (bitmap == null) {
      db.setAnalyzing(entry.id, false)
      _activeAnalysisIds.value = _activeAnalysisIds.value - entry.id
      _entryProgressMap.value = _entryProgressMap.value - entry.id
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

    val onProgress: (Float) -> Unit = onProgressLabel@{ progress ->
      if (!isAnalysisSessionActive(sessionToken)) return@onProgressLabel
      val currentProgress = _entryProgressMap.value[entry.id] ?: 0f
      val newProgress = maxOf(currentProgress, progress)
      if (newProgress > 0f) {
        _currentImageProgress.value = newProgress
        _entryProgressMap.value = _entryProgressMap.value + (entry.id to newProgress)
      }
    }
    val onResult: (String, String, String) -> Unit = { summary, tags, modelUsed ->
      viewModelScope.launch(Dispatchers.IO) {
        if (!isAnalysisSessionActive(sessionToken)) {
          Log.d(TAG, "Dropped stale result for id=${entry.id}, token=$sessionToken")
          return@launch
        }
        db.updateAnalysis(entry.id, summary, tags, modelUsed)
        saveDatabaseToExternalFolder()

        // Delay clearing UI state slightly so the 100% progress bar has time to be seen
        kotlinx.coroutines.delay(600L)

        _activeAnalysisIds.value = _activeAnalysisIds.value - entry.id
        _entryProgressMap.value = _entryProgressMap.value - entry.id
        if (useConcurrent) {
          refreshEntriesDebounced()
        } else {
          refreshEntries()
        }
        Log.d(TAG, "Analysis complete: id=${entry.id}")
      }
    }
    val onError: (String) -> Unit = { error ->
      viewModelScope.launch(Dispatchers.IO) {
        if (!isAnalysisSessionActive(sessionToken)) {
          Log.d(TAG, "Dropped stale error for id=${entry.id}, token=$sessionToken: $error")
          return@launch
        }
        db.setAnalyzing(entry.id, false)
        _activeAnalysisIds.value = _activeAnalysisIds.value - entry.id
        // Keep progress in map on active session so UI bar doesn't disappear on error/retry
        if (useConcurrent) {
          refreshEntriesDebounced()
        } else {
          refreshEntries()
        }
        Log.e(TAG, "Analysis failed for ${entry.id}: $error")
      }
    }

    val result =
        try {
          kotlinx.coroutines.withTimeoutOrNull(300_000L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
              val ggufState = ggufManager.uiState.value
              val useGguf =
                  _selectedModel.value == ModelType.GGUF &&
                      (ggufState is GgufLlmManager.LlmState.Ready ||
                          ggufState is GgufLlmManager.LlmState.Generating)

              val useAicore = _selectedModel.value == ModelType.AICORE

              if (useAicore) {
                val promptText =
                    when (_detailLevel.value) {
                      LlmManager.DetailLevel.BRIEF ->
                          """Describe this image briefly in $targetLang. Respond with EXACTLY this format and nothing else:
SUMMARY: [your one sentence description]
TAGS: [tag1, tag2, tag3]"""
                      LlmManager.DetailLevel.DETAILED ->
                          """Describe this image in detail in $targetLang. Write 2-3 sentences. Respond with EXACTLY this format and nothing else:
SUMMARY: [your detailed 2-3 sentence description]
TAGS: [tag1, tag2, tag3, tag4, tag5]"""
                      LlmManager.DetailLevel.COMPREHENSIVE ->
                          """Describe this image with maximum detail in $targetLang, using a single paragraph. Respond with EXACTLY this format and nothing else:
SUMMARY: [your comprehensive paragraph describing absolutely everything visible in the image]
TAGS: [tag1, tag2, tag3, tag4, tag5, tag6, tag7, tag8]"""
                      LlmManager.DetailLevel.CUSTOM ->
                          """${_customPrompt.value.takeIf { it.isNotBlank() } ?: "Describe this screenshot."} Output your summary in $targetLang.
Respond with EXACTLY this format and nothing else:
SUMMARY: [your response based on the instruction]
TAGS: [extracted tag1, tag2, tag3]"""
                    }
                viewModelScope.launch(Dispatchers.IO) {
                  var attempts = 0
                  val maxAttempts = 3
                  var success = false
                  var lastError: Throwable? = null
                  var parsedResult: Pair<String, String>? = null

                  while (attempts < maxAttempts && !success) {
                    attempts++
                    try {
                      onProgress(0.1f + (attempts - 1) * 0.2f)
                      val analysisResult = aicoreManager.analyzeScreenshot(bitmap, promptText)
                      if (analysisResult.isSuccess) {
                        parsedResult = analysisResult.getOrNull()
                        success = true
                      } else {
                        lastError = analysisResult.exceptionOrNull()
                        Log.w(
                            "ScreenshotViewModel",
                            "AICore try $attempts failed for entry ${entry.id}: ${lastError?.message}")
                        if (attempts < maxAttempts) {
                          kotlinx.coroutines.delay(500L * attempts)
                        }
                      }
                    } catch (e: Exception) {
                      lastError = e
                      Log.e(
                          "ScreenshotViewModel",
                          "AICore exception on try $attempts for entry ${entry.id}: ${e.message}")
                      if (attempts < maxAttempts) {
                        kotlinx.coroutines.delay(500L * attempts)
                      }
                    }
                  }

                  if (success && parsedResult != null) {
                    val (summary, tags) = parsedResult
                    onProgress(1.0f)
                    onResult(summary, tags, "Gemini Nano")
                    viewModelScope.launch(Dispatchers.IO) {
                      kotlinx.coroutines.delay(600L)
                      if (continuation.isActive) continuation.resume(true)
                    }
                  } else {
                    val errStr = lastError?.message ?: ""
                    val errType = lastError?.javaClass?.simpleName ?: ""
                    val isPolicyOrSafety =
                        errType.contains("Block") ||
                            errType.contains("Safety") ||
                            errType.contains("Policy") ||
                            errStr.contains("block", ignoreCase = true) ||
                            errStr.contains("safety", ignoreCase = true) ||
                            errStr.contains("policy", ignoreCase = true) ||
                            errStr.contains("restrict", ignoreCase = true) ||
                            errStr.contains("filter", ignoreCase = true)

                    val fallbackSummary =
                        if (isPolicyOrSafety) {
                          "This screenshot could not be analyzed due to local AI model policy restrictions or safety filters. Please re-analyze this image using another model (e.g. Gemma or GGUF)."
                        } else {
                          "This screenshot could not be processed by the on-device AI core due to hardware or format limitations. Please re-analyze this image using another model (e.g. Gemma or GGUF)."
                        }
                    val fallbackTags = "restricted"
                    onProgress(1.0f)
                    onResult(fallbackSummary, fallbackTags, "Gemini Nano")
                    viewModelScope.launch(Dispatchers.IO) {
                      kotlinx.coroutines.delay(600L)
                      if (continuation.isActive) continuation.resume(true)
                    }
                  }
                }
              } else if (useGguf) {
                val detailLevelName =
                    when (_detailLevel.value) {
                      LlmManager.DetailLevel.BRIEF -> "brief"
                      LlmManager.DetailLevel.DETAILED -> "detailed"
                      LlmManager.DetailLevel.COMPREHENSIVE -> "comprehensive"
                      LlmManager.DetailLevel.CUSTOM -> "custom"
                    }
                ggufManager.analyzeScreenshot(
                    bitmap = bitmap,
                    detailLevel = detailLevelName,
                    customPrompt = _customPrompt.value.takeIf { it.isNotBlank() },
                    targetLanguage = targetLang,
                    imageResolution = _imageResolution.value,
                    onProgress = onProgress,
                    onResult = { s, t, m ->
                      onResult(s, t, m)
                      viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(600L)
                        if (continuation.isActive) continuation.resume(true)
                      }
                    },
                    onError = { e ->
                      onError(e)
                      if (continuation.isActive) continuation.resume(false)
                    },
                )
              } else if (useConcurrent) {
                llmManager.analyzeScreenshotConcurrent(
                    bitmap = bitmap,
                    detailLevel = _detailLevel.value,
                    customPrompt = _customPrompt.value.takeIf { it.isNotBlank() },
                    targetLanguage = targetLang,
                    imageResolution = _imageResolution.value,
                    onProgress = onProgress,
                    onResult = { s, t, m ->
                      onResult(s, t, m)
                      viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(600L)
                        if (continuation.isActive) continuation.resume(true)
                      }
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
                      viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(600L)
                        if (continuation.isActive) continuation.resume(true)
                      }
                    },
                    onError = { e ->
                      onError(e)
                      if (continuation.isActive) continuation.resume(false)
                    },
                )
              }
            }
          }
        } finally {
          bitmap.recycle()
        }

    if (result == null && isAnalysisSessionActive(sessionToken)) {
      // Un-hang the database if timeout occurred
      db.setAnalyzing(entry.id, false)
      _activeAnalysisIds.value = _activeAnalysisIds.value - entry.id
      _entryProgressMap.value = _entryProgressMap.value - entry.id
      refreshEntries()
      Log.e(TAG, "Analysis timed out after 300s for id=${entry.id}")
      return false
    }

    if (result == null) {
      Log.d(TAG, "Ignored timeout cleanup for stale session on id=${entry.id}")
      return false
    }

    return result
  }

  /** Re-analyze a single entry (triggered from Detail screen). */
  fun analyzeEntry(entry: ScreenshotEntry) {
    if (!_isModelReady.value) return
    viewModelScope.launch(Dispatchers.IO) {
      val wasQueueRunning = isAnalyzing.get()
      if (wasQueueRunning) {
        // Stop the background queue and wait for it to fully drain so the GGUF
        // inferMutex is released before we attempt the priority analysis.
        isAnalyzing.set(false)
        _activeAnalysisIds.value = emptySet()
        _entryProgressMap.value = emptyMap()
        analysisJob?.join()
      }

      // Reset any stuck isAnalyzing flags from the interrupted queue
      val all = db.getAllEntries()
      for (e in all) {
        if (e.isAnalyzing) {
          db.setAnalyzing(e.id, false)
        }
      }
      refreshEntries()

      _analysisProgress.value = 1 to 1
      analyzeEntrySuspend(entry)
      _analysisProgress.value = null

      if (wasQueueRunning) {
        // Resume the background queue now that the priority image is done
        launchAnalysisQueue()
      }
    }
  }

  fun updateSummary(
      id: Long,
      summary: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateSummary(id, summary)
      refreshEntries()
      saveDatabaseToExternalFolder()
    }
  }

  fun updateTags(
      id: Long,
      tags: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateTags(id, tags)
      refreshEntries()
      saveDatabaseToExternalFolder()
    }
  }

  fun deleteEntry(id: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      db.deleteEntry(id)
      refreshEntries()
      saveDatabaseToExternalFolder()
    }
  }

  fun updateNote(
      id: Long,
      note: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      db.updateNote(id, note)
      refreshEntries()
      saveDatabaseToExternalFolder()
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
    clearModelSwitchStatus()
    llmManager.close()
    ggufManager.close()
  }
}
