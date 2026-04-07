package com.deryk.skarmetoo

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deryk.skarmetoo.data.DataManager
import com.deryk.skarmetoo.data.ImageHasher
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "ScreenshotVM"

data class AlbumInfo(
    val name: String,
    val bucketId: String,
    val count: Int,
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val prefs = application.getSharedPreferences("skarmetoo_prefs", android.content.Context.MODE_PRIVATE)

    private val _availableAlbums = MutableStateFlow<List<AlbumInfo>>(emptyList())
    val availableAlbums: StateFlow<List<AlbumInfo>> = _availableAlbums.asStateFlow()

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

    private val _appLanguage = MutableStateFlow("en")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _analysisLanguage = MutableStateFlow("en")
    val analysisLanguage: StateFlow<String> = _analysisLanguage.asStateFlow()

    private val _isSortDescending = MutableStateFlow(true)
    val isSortDescending: StateFlow<Boolean> = _isSortDescending.asStateFlow()

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

        // Restore selected model from prefs
        val savedModelType = prefs.getString("selected_model", ModelType.GEMMA_3N.name)
        _selectedModel.value =
            try {
                ModelType.valueOf(savedModelType ?: ModelType.GEMMA_3N.name)
            } catch (e: Exception) {
                ModelType.GEMMA_3N
            }

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
                        viewModelScope.launch(Dispatchers.IO) {
                            startAnalysisQueue()
                        }
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
                    val fallback = if (selected == ModelType.GEMMA_3N) ModelType.GEMMA_4 else ModelType.GEMMA_3N
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
            g3File.exists() && g3File.length() > 100L * 1024 * 1024 &&
                !(isDownloading && downloadingType == ModelType.GEMMA_3N)

        val g4File = java.io.File(context.filesDir, ModelType.GEMMA_4.fileName)
        _isGemma4Downloaded.value =
            g4File.exists() && g4File.length() > 100L * 1024 * 1024 &&
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
        
        llmManager.initializeModel(path, useGpu = useGpu, isGemma4 = isGemma4)
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

                    // Only send auth headers to HuggingFace, but DROP them for subsequent AWS S3 CDN redirects
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
                                throw Exception("Unauthorized: Please sign in to Hugging Face and accept the model's license agreement.")
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

                withContext(Dispatchers.Main) {
                    _modelStatus.value = "Downloaded to internal storage"
                }

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
                withContext(Dispatchers.Main) {
                }
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
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

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

            _availableAlbums.value =
                albums.values
                    .sortedByDescending { it.count }
                    .toList()

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

    fun loadSelectedAlbums() {
        val selected = _selectedAlbums.value
        if (selected.isEmpty()) {
            return
        }

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val uris = mutableListOf<Uri>()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID)

            for (bucketId in selected) {
                try {
                    val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
                    val selectionArgs = arrayOf(bucketId)

                    context.contentResolver.query(
                        uri,
                        projection,
                        selection,
                        selectionArgs,
                        "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                    )?.use { cursor ->
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

            Log.d(TAG, "Loading ${uris.size} images from ${selected.size} albums")

            // Use existing addScreenshots logic (handles dedup, hash, etc.)
            withContext(Dispatchers.Main) {
                addScreenshots(uris)
            }
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
                    } catch (_: Exception) {
                    }

                    val bitmap = loadBitmap(uri) ?: continue
                    val hash = ImageHasher.computeDHash(bitmap)

                    val existing = db.getEntryByHash(hash)
                    if (existing != null) {
                        if (existing.imageUri.isBlank()) {
                            db.linkImageToHash(hash, uri.toString())
                            Log.d(TAG, "Linked imported entry: $hash")
                        } else {
                            Log.d(TAG, "Duplicate skipped: $hash")
                        }
                        continue
                    }

                    val entry =
                        ScreenshotEntry(
                            imageUri = uri.toString(),
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
                startAnalysisQueue()
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
            withContext(Dispatchers.Main) {
            }
        }
    }

    fun removeSourceFolder(uriStr: String) {
        val currentUris = prefs.getStringSet("saved_folder_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentUris.remove(uriStr)) {
            prefs.edit().putStringSet("saved_folder_uris", currentUris).apply()
            _sourceFolders.value = currentUris
            _folderImageCounts.value = _folderImageCounts.value.toMutableMap().apply { remove(uriStr) }

            viewModelScope.launch(Dispatchers.IO) {
                val context = getApplication<Application>()
                db.deleteAllEntries()
                refreshImagesInternal()
                refreshEntries()
                withContext(Dispatchers.Main) {
                }
            }
        }
    }

    fun loadImagesFromFolder(treeUri: Uri) {
        val context = getApplication<Application>()
        _isRefreshing.value = true

        val currentUris = prefs.getStringSet("saved_folder_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentUris.add(treeUri.toString())
        prefs.edit().putStringSet("saved_folder_uris", currentUris).apply()
        _sourceFolders.value = currentUris

        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }

            refreshImagesInternal()

            withContext(Dispatchers.Main) {
                _isRefreshing.value = false
            }
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
                val childrenUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri) ?: return
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
                _folderImageCounts.value.toMutableMap().apply {
                    put(treeUri.toString(), imageUris.size)
                }

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
                        context.contentResolver.openAssetFileDescriptor(Uri.parse(entry.imageUri), "r")?.use { true } == true
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

            withContext(Dispatchers.Main) {
                _isRefreshing.value = false
            }
        }
    }

    fun analyzeUnprocessed() {
        if (!_isModelReady.value) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            startAnalysisQueue()
        }
    }

    fun forceAnalyzeUnprocessed() {
        if (!_isModelReady.value) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
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
            isAnalyzing.set(false)
            refreshEntries()

            withContext(Dispatchers.Main) {
            }
            startAnalysisQueue()
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
            var counts = 0

            while (counts < total) {
                // Fetch the latest unanalyzed image from DB again to handle new inserts dynamically
                val currentUnprocessed =
                    db.getAllEntries().filter {
                        it.analyzedAt == 0L && !it.isAnalyzing && it.imageUri.isNotBlank()
                    }.sortedByDescending { it.id }

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

                // Allow underlying C++ LiteRT engine time to gracefully finalize callback threads naturally before the next iteration rips it away
                Log.d(TAG, "Delaying 2s to allow JNI engine teardown...")
                kotlinx.coroutines.delay(2000L)
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
     * Analyze a single entry, suspending until complete.
     * Returns true on success, false on failure.
     */
    private suspend fun analyzeEntrySuspend(entry: ScreenshotEntry): Boolean {
        db.setAnalyzing(entry.id, true)
        refreshEntries()
        _currentImageProgress.value = 0f

        val bitmap = loadBitmap(Uri.parse(entry.imageUri))
        if (bitmap == null) {
            db.setAnalyzing(entry.id, false)
            refreshEntries()
            return false
        }

        val result =
            kotlinx.coroutines.withTimeoutOrNull(300_000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    llmManager.analyzeScreenshot(
                        bitmap = bitmap,
                        detailLevel = _detailLevel.value,
                        customPrompt = _customPrompt.value.takeIf { it.isNotBlank() },
                        targetLanguage = when (_analysisLanguage.value) {
                            "en" -> "English"
                            "zh-rTW" -> "Traditional Chinese"
                            "hi" -> "Hindi"
                            "es" -> "Spanish"
                            "ar" -> "Arabic"
                            "fr" -> "French"
                            "ru" -> "Russian"
                            else -> "English"
                        },
                        onProgress = { progress ->
                            _currentImageProgress.value = progress
                        },
                        onResult = { summary, tags, modelUsed ->
                            viewModelScope.launch(Dispatchers.IO) {
                                db.updateAnalysis(entry.id, summary, tags, modelUsed)
                                refreshEntries()
                                Log.d(TAG, "Analysis complete: id=${entry.id}")
                                if (continuation.isActive) continuation.resume(true)
                            }
                        },
                        onError = { error ->
                            viewModelScope.launch(Dispatchers.IO) {
                                db.setAnalyzing(entry.id, false)
                                refreshEntries()
                                Log.e(TAG, "Analysis failed for ${entry.id}: $error")
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                    )
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

    /**
     * Re-analyze a single entry (triggered from Detail screen).
     */
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
            withContext(Dispatchers.Main) {
            }
        }
    }

    fun importData(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val count = DataManager.importFromUri(context, uri, db)
            refreshEntries()
            withContext(Dispatchers.Main) {
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val context = getApplication<Application>()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
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
