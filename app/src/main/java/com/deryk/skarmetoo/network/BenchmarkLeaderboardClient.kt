package com.deryk.skarmetoo.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class BenchmarkUploadStatus {
  WAITING,
  UPLOADING,
  SYNCED,
  FAILED,
  UNAVAILABLE,
}

/** Uploads only locally verified improvements. No image data or local media IDs are sent. */
class BenchmarkLeaderboardClient(context: Context) {
  private val appContext = context.applicationContext
  private val preferences =
      appContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
  private val localRecordLock = Any()
  @Volatile private var lastSyncedFastestDurationMillis = loadSyncedFastestDuration()
  @Volatile private var pendingBenchmark = loadPendingBenchmark()
  @Volatile private var uploadedRecordsByModel = loadUploadedRecords()
  @Volatile private var inFlightEntryId: String? = null
  val deviceId: String by lazy { loadOrCreateDeviceId() }
  private val firestore: FirebaseFirestore? by lazy { configuredFirestore() }
  private val uploadRevision = AtomicLong(0L)
  private val resetGeneration = AtomicLong(0L)
  private val _leaderboardOptedIn =
      MutableStateFlow(preferences.getBoolean(PREF_LEADERBOARD_OPTED_IN, false))
  val leaderboardOptedIn: StateFlow<Boolean> = _leaderboardOptedIn.asStateFlow()
  private val _uploadStatus =
      MutableStateFlow(
          if (lastSyncedFastestDurationMillis == null) {
            BenchmarkUploadStatus.WAITING
          } else {
            BenchmarkUploadStatus.SYNCED
          })
  val uploadStatus: StateFlow<BenchmarkUploadStatus> = _uploadStatus.asStateFlow()

  init {
    if (_leaderboardOptedIn.value) {
      syncLeaderboardNotificationTopic(enabled = true)
      pendingBenchmark?.let(::uploadPending)
    }
  }

  /** Enables or disables participation in the public benchmark leaderboard. */
  fun setLeaderboardOptedIn(enabled: Boolean) {
    if (_leaderboardOptedIn.value == enabled) return
    preferences.edit().putBoolean(PREF_LEADERBOARD_OPTED_IN, enabled).apply()
    _leaderboardOptedIn.value = enabled

    if (enabled) {
      syncLeaderboardNotificationTopic(enabled = true)
      pendingBenchmark?.let(::uploadPending)
    } else {
      syncLeaderboardNotificationTopic(enabled = false)
      _uploadStatus.value = BenchmarkUploadStatus.WAITING
    }
  }

  /** Keeps the FCM topic membership aligned with the user's leaderboard opt-in choice. */
  private fun syncLeaderboardNotificationTopic(enabled: Boolean) {
    val task =
        runCatching {
              if (enabled) {
                FirebaseMessaging.getInstance()
                    .subscribeToTopic(LEADERBOARD_UPDATES_TOPIC)
              } else {
                FirebaseMessaging.getInstance()
                    .unsubscribeFromTopic(LEADERBOARD_UPDATES_TOPIC)
              }
            }
            .getOrNull()
            ?: return

    task
        .addOnSuccessListener {
          Log.d(
              TAG,
              if (enabled) {
                "Subscribed to leaderboard update notifications"
              } else {
                "Unsubscribed from leaderboard update notifications"
              },
          )
        }
        .addOnFailureListener { error ->
          Log.w(
              TAG,
              if (enabled) {
                "Could not subscribe to leaderboard update notifications"
              } else {
                "Could not unsubscribe from leaderboard update notifications"
              },
              error,
          )
        }
  }

  /**
   * Uploads [durationMillis] only when it beats this device's fastest successfully synced result.
   *
   * Every improvement gets a new entry ID. A failed upload remains pending with the same entry ID
   * so a retry cannot create a duplicate if Firestore accepted the first request but its response
   * was interrupted.
   */
  fun submitFastest(
      durationMillis: Long,
      modelUsed: String?,
      imageWidthPixels: Int?,
      imageHeightPixels: Int?,
      generatedTextCharacterCount: Int?,
  ) {
    if (durationMillis <= 0L) return
    if (!_leaderboardOptedIn.value) {
      _uploadStatus.value = BenchmarkUploadStatus.WAITING
      return
    }

    val candidate =
        synchronized(localRecordLock) {
          val localFastest = lastSyncedFastestDurationMillis
          val pending = pendingBenchmark

          if (localFastest != null && durationMillis >= localFastest) {
            if (pending != null && pending.durationMillis < localFastest) {
              pending
            } else {
              null
            }
          } else if (pending == null || durationMillis < pending.durationMillis) {
            PendingBenchmark(
                    entryId = UUID.randomUUID().toString(),
                    durationMillis = durationMillis,
                    modelUsed = modelUsed.orEmpty().ifBlank { "unknown" },
                    recordedAtClientMillis = System.currentTimeMillis(),
                    imageWidthPixels = imageWidthPixels?.takeIf { it > 0 },
                    imageHeightPixels = imageHeightPixels?.takeIf { it > 0 },
                    generatedTextCharacterCount = generatedTextCharacterCount?.takeIf { it >= 0 },
                )
                .also { rememberPendingBenchmark(it) }
          } else {
            pending
          }
        }

    if (candidate == null) {
      _uploadStatus.value = BenchmarkUploadStatus.SYNCED
      Log.d(
          TAG,
          "Skipped benchmark duration=${durationMillis}ms; " +
              "local best=${lastSyncedFastestDurationMillis}ms",
      )
      return
    }

    uploadPending(candidate)
  }

  /** Clears debug-only local benchmark state without changing the stable device ID. */
  fun resetLocalBenchmarkForDebug() {
    if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    synchronized(localRecordLock) {
      resetGeneration.incrementAndGet()
      uploadRevision.incrementAndGet()
          lastSyncedFastestDurationMillis = null
          pendingBenchmark = null
          uploadedRecordsByModel = emptyMap()
          inFlightEntryId = null
          deleteLocalFile(SYNCED_FASTEST_FILE)
          deleteLocalFile(PENDING_BENCHMARK_FILE)
          preferences.edit().remove(PREF_UPLOADED_RECORDS).apply()
    }
    _uploadStatus.value = BenchmarkUploadStatus.WAITING
    Log.d(TAG, "Reset local benchmark record for device=$deviceId")
  }

  private fun uploadPending(candidate: PendingBenchmark) {
    val database = firestore ?: return
    val generation = resetGeneration.get()
    val revision =
        synchronized(localRecordLock) {
          if (inFlightEntryId == candidate.entryId) return
          inFlightEntryId = candidate.entryId
          uploadRevision.incrementAndGet()
        }
    _uploadStatus.value = BenchmarkUploadStatus.UPLOADING
    val entryRef = database.collection(ENTRY_COLLECTION).document(candidate.entryId)
    val packageInfo =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }
            .getOrNull()
    val appVersionCode = packageInfo?.let { @Suppress("DEPRECATION") it.longVersionCode } ?: 0L
    val result =
        hashMapOf<String, Any>(
            "deviceId" to deviceId,
            "entryId" to candidate.entryId,
            "durationMillis" to candidate.durationMillis,
            "durationSeconds" to candidate.durationMillis / 1000.0,
            "deviceManufacturer" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "androidSdk" to Build.VERSION.SDK_INT,
            "appId" to appContext.packageName,
            "appVersionCode" to appVersionCode,
            "appVersionName" to (packageInfo?.versionName ?: "unknown"),
            "modelUsed" to candidate.modelUsed,
            "recordedAtClientMillis" to candidate.recordedAtClientMillis,
            "recordedAt" to FieldValue.serverTimestamp(),
            "benchmarkVersion" to BENCHMARK_VERSION,
        )
    candidate.imageWidthPixels?.let { result["analyzedImageWidthPixels"] = it }
    candidate.imageHeightPixels?.let { result["analyzedImageHeightPixels"] = it }
    if (candidate.imageWidthPixels != null && candidate.imageHeightPixels != null) {
      result["analyzedImagePixelCount"] =
          candidate.imageWidthPixels.toLong() * candidate.imageHeightPixels.toLong()
    }
    candidate.generatedTextCharacterCount?.let { result["generatedTextCharacterCount"] = it }

    entryRef
        .set(result)
        .addOnSuccessListener {
          if (resetGeneration.get() != generation) {
            Log.d(TAG, "Ignored upload completion after a local benchmark reset")
            return@addOnSuccessListener
          }
          rememberSyncedFastestDuration(candidate.durationMillis)
          rememberUploadedRecord(candidate)
          clearPendingBenchmark(candidate.entryId)
          synchronized(localRecordLock) {
            if (inFlightEntryId == candidate.entryId) inFlightEntryId = null
          }
          if (uploadRevision.get() == revision) {
            _uploadStatus.value = BenchmarkUploadStatus.SYNCED
          }
          Log.d(TAG, "Uploaded benchmark entry=${candidate.entryId} for device=$deviceId")
        }
        .addOnFailureListener { error ->
          if (resetGeneration.get() != generation) {
            Log.d(TAG, "Ignored upload failure after a local benchmark reset")
            return@addOnFailureListener
          }
          synchronized(localRecordLock) {
            if (inFlightEntryId == candidate.entryId) inFlightEntryId = null
          }
          if (uploadRevision.get() == revision) {
            _uploadStatus.value = BenchmarkUploadStatus.FAILED
          }
          Log.w(TAG, "Benchmark upload failed", error)
        }
  }

  private fun configuredFirestore(): FirebaseFirestore? =
      try {
        val firebaseApp =
            FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(appContext)
        if (firebaseApp == null) {
          _uploadStatus.value = BenchmarkUploadStatus.UNAVAILABLE
          Log.i(TAG, "Firebase is not configured; benchmark upload is disabled")
          null
        } else {
          FirebaseFirestore.getInstance(firebaseApp)
        }
      } catch (error: Exception) {
        _uploadStatus.value = BenchmarkUploadStatus.UNAVAILABLE
        Log.w(TAG, "Firebase initialization failed; benchmark upload is disabled", error)
        null
      }

  private fun loadSyncedFastestDuration(): Long? {
    val recordFile = File(appContext.noBackupFilesDir, SYNCED_FASTEST_FILE)
    val fields =
        runCatching { recordFile.readText().trim().split(',', limit = 2) }.getOrNull()
            ?: return null
    if (fields.size != 2) return null
    val durationMillis = fields[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
    when (fields[0]) {
      BENCHMARK_VERSION -> Unit
      LEGACY_TIMING_VERSION ->
          runCatching { recordFile.writeText("$BENCHMARK_VERSION,$durationMillis") }
              .onFailure { Log.w(TAG, "Could not migrate the local benchmark version", it) }
      else -> return null
    }
    return durationMillis
  }

  private fun rememberSyncedFastestDuration(durationMillis: Long) {
    if (durationMillis <= 0L) return
    synchronized(localRecordLock) {
      val current = lastSyncedFastestDurationMillis
      if (current != null && current <= durationMillis) return
      runCatching {
            File(appContext.noBackupFilesDir, SYNCED_FASTEST_FILE)
                .writeText("$BENCHMARK_VERSION,$durationMillis")
          }
          .onSuccess { lastSyncedFastestDurationMillis = durationMillis }
          .onFailure { Log.w(TAG, "Could not persist the synced fastest benchmark", it) }
    }
  }

  private fun loadUploadedRecords(): Map<String, PendingBenchmark> {
    val root =
        runCatching {
              preferences.getString(PREF_UPLOADED_RECORDS, null)?.let(::JSONObject)
            }
            .getOrNull()
            ?: return emptyMap()
    return SUPPORTED_MODEL_KEYS.mapNotNull { modelKey ->
          pendingFromJson(root.optJSONObject(modelKey))?.let { modelKey to it }
        }
        .toMap()
  }

  private fun rememberUploadedRecord(candidate: PendingBenchmark) {
    val modelKey = supportedModelKey(candidate.modelUsed) ?: return
    synchronized(localRecordLock) {
      val current = uploadedRecordsByModel[modelKey]
      if (current != null && current.durationMillis <= candidate.durationMillis) return

      val updated = uploadedRecordsByModel.toMutableMap().apply { put(modelKey, candidate) }
      val root = JSONObject()
      updated.forEach { (key, record) -> root.put(key, pendingToJson(record)) }
      runCatching { preferences.edit().putString(PREF_UPLOADED_RECORDS, root.toString()).apply() }
          .onSuccess { uploadedRecordsByModel = updated }
          .onFailure { Log.w(TAG, "Could not persist uploaded benchmark backup", it) }
    }
  }

  private fun pendingFromJson(json: JSONObject?): PendingBenchmark? {
    if (json == null) return null
    val entryId = json.optString("entryId").takeIf { it.isNotBlank() } ?: return null
    val durationMillis = json.optLong("durationMillis").takeIf { it > 0L } ?: return null
    val modelUsed = json.optString("modelUsed").ifBlank { return null }
    return PendingBenchmark(
        entryId = entryId,
        durationMillis = durationMillis,
        modelUsed = modelUsed,
        recordedAtClientMillis = json.optLong("recordedAtClientMillis"),
        imageWidthPixels =
            json.optInt("imageWidthPixels").takeIf {
              json.has("imageWidthPixels") && it > 0
            },
        imageHeightPixels =
            json.optInt("imageHeightPixels").takeIf {
              json.has("imageHeightPixels") && it > 0
            },
        generatedTextCharacterCount =
            json.optInt("generatedTextCharacterCount").takeIf {
              json.has("generatedTextCharacterCount") && it >= 0
            },
    )
  }

  private fun pendingToJson(candidate: PendingBenchmark): JSONObject =
      JSONObject()
          .put("benchmarkVersion", BENCHMARK_VERSION)
          .put("entryId", candidate.entryId)
          .put("durationMillis", candidate.durationMillis)
          .put("modelUsed", candidate.modelUsed)
          .put("recordedAtClientMillis", candidate.recordedAtClientMillis)
          .apply {
            candidate.imageWidthPixels?.let { put("imageWidthPixels", it) }
            candidate.imageHeightPixels?.let { put("imageHeightPixels", it) }
            candidate.generatedTextCharacterCount?.let {
              put("generatedTextCharacterCount", it)
            }
          }

  private fun loadPendingBenchmark(): PendingBenchmark? =
      runCatching {
            val json =
                JSONObject(File(appContext.noBackupFilesDir, PENDING_BENCHMARK_FILE).readText())
            val isCurrentVersion =
                json.optString("benchmarkVersion") == BENCHMARK_VERSION ||
                    json.optString("timingVersion") == LEGACY_TIMING_VERSION
            if (!isCurrentVersion) return@runCatching null

            val entryId = json.optString("entryId")
            val durationMillis = json.optLong("durationMillis")
            if (entryId.isBlank() || durationMillis <= 0L) return@runCatching null

            PendingBenchmark(
                entryId = entryId,
                durationMillis = durationMillis,
                modelUsed = json.optString("modelUsed").ifBlank { "unknown" },
                recordedAtClientMillis = json.optLong("recordedAtClientMillis"),
                imageWidthPixels =
                    json.optInt("imageWidthPixels").takeIf {
                      json.has("imageWidthPixels") && it > 0
                    },
                imageHeightPixels =
                    json.optInt("imageHeightPixels").takeIf {
                      json.has("imageHeightPixels") && it > 0
                    },
                generatedTextCharacterCount =
                    json.optInt("generatedTextCharacterCount").takeIf {
                      json.has("generatedTextCharacterCount") && it >= 0
                    },
            )
          }
          .getOrNull()

  private fun rememberPendingBenchmark(candidate: PendingBenchmark) {
    val json =
        JSONObject()
            .put("benchmarkVersion", BENCHMARK_VERSION)
            .put("entryId", candidate.entryId)
            .put("durationMillis", candidate.durationMillis)
            .put("modelUsed", candidate.modelUsed)
            .put("recordedAtClientMillis", candidate.recordedAtClientMillis)
    candidate.imageWidthPixels?.let { json.put("imageWidthPixels", it) }
    candidate.imageHeightPixels?.let { json.put("imageHeightPixels", it) }
    candidate.generatedTextCharacterCount?.let { json.put("generatedTextCharacterCount", it) }
    runCatching {
          File(appContext.noBackupFilesDir, PENDING_BENCHMARK_FILE).writeText(json.toString())
        }
        .onSuccess { pendingBenchmark = candidate }
        .onFailure {
          // Keep the in-memory candidate uploadable even when local persistence is unavailable.
          pendingBenchmark = candidate
          Log.w(TAG, "Could not persist the pending benchmark", it)
        }
  }

  private fun clearPendingBenchmark(entryId: String) {
    synchronized(localRecordLock) {
      if (pendingBenchmark?.entryId != entryId) return
      pendingBenchmark = null
      runCatching { File(appContext.noBackupFilesDir, PENDING_BENCHMARK_FILE).delete() }
          .onFailure { Log.w(TAG, "Could not clear the pending benchmark", it) }
    }
  }

  private fun deleteLocalFile(fileName: String) {
    runCatching {
          val file = File(appContext.noBackupFilesDir, fileName)
          !file.exists() || file.delete()
        }
        .onSuccess { deleted ->
          if (!deleted) Log.w(TAG, "Could not delete local benchmark file=$fileName")
        }
        .onFailure { Log.w(TAG, "Could not delete local benchmark file=$fileName", it) }
  }

  @Synchronized
  private fun loadOrCreateDeviceId(): String {
    val idFile = File(appContext.noBackupFilesDir, DEVICE_ID_FILE)
    val savedId = runCatching { idFile.readText().trim() }.getOrNull()
    if (!savedId.isNullOrBlank()) return savedId

    val newId = UUID.randomUUID().toString()
    runCatching { idFile.writeText(newId) }
        .onFailure { Log.w(TAG, "Could not persist benchmark device ID", it) }
    return newId
  }

  companion object {
    /** Returns the last successfully uploaded best record kept on this device. */
    fun loadLocalUploadedEntries(context: Context, deviceId: String): List<LeaderboardEntry> {
      val preferences =
          context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
      val root =
          runCatching {
                preferences.getString(PREF_UPLOADED_RECORDS, null)?.let(::JSONObject)
              }
              .getOrNull()
              ?: return emptyList()
      return SUPPORTED_MODEL_KEYS.mapNotNull { modelKey ->
        val record = root.optJSONObject(modelKey) ?: return@mapNotNull null
        val entryId = record.optString("entryId").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val durationMillis = record.optLong("durationMillis").takeIf { it > 0L }
            ?: return@mapNotNull null
        val modelUsed = record.optString("modelUsed").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        LeaderboardEntry(
            rank = null,
            metricScore = null,
            deviceId = deviceId,
            entryId = entryId,
            benchmarkVersion = record.optString("benchmarkVersion").ifBlank { BENCHMARK_VERSION },
            durationMillis = durationMillis,
            durationSeconds = durationMillis / 1000.0,
            analyzedImageWidthPixels =
                record.optInt("imageWidthPixels").takeIf {
                  record.has("imageWidthPixels") && it > 0
                },
            analyzedImageHeightPixels =
                record.optInt("imageHeightPixels").takeIf {
                  record.has("imageHeightPixels") && it > 0
                },
            analyzedImagePixelCount =
                if (record.has("imageWidthPixels") && record.has("imageHeightPixels")) {
                  record.optInt("imageWidthPixels").toLong() *
                      record.optInt("imageHeightPixels").toLong()
                } else {
                  null
                },
            generatedTextCharacterCount =
                record.optInt("generatedTextCharacterCount").takeIf {
                  record.has("generatedTextCharacterCount") && it >= 0
                },
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            appId = context.applicationContext.packageName,
            appVersionCode = null,
            appVersionName = null,
            modelUsed = modelUsed,
            recordedAt = null,
            recordedAtClientMillis =
                record.optLong("recordedAtClientMillis").takeIf { it > 0L },
            snapshotAt = null,
        )
      }
    }

    private val SUPPORTED_MODEL_KEYS = listOf("gemma4", "lfm25vl")

    private fun supportedModelKey(modelUsed: String?): String? =
        modelUsed
            ?.lowercase(Locale.US)
            ?.filter(Char::isLetterOrDigit)
            ?.takeIf { it.isNotBlank() }
            ?.let { normalized ->
              when (normalized) {
                "gemma4" -> "gemma4"
                "lfm25vl", "lfm" -> "lfm25vl"
                else -> null
              }
            }

    const val TAG = "BenchmarkLeaderboard"
    const val PREFERENCES_FILE = "skarmetoo_prefs"
    const val PREF_LEADERBOARD_OPTED_IN = "leaderboard_opted_in"
    const val LEADERBOARD_UPDATES_TOPIC = "leaderboard-updates"
    const val ACTION_SHOW_LEADERBOARD = "com.deryk.skarmetoo.action.SHOW_LEADERBOARD"
    const val PREF_UPLOADED_RECORDS = "benchmark_uploaded_records"
    const val DEVICE_ID_FILE = "benchmark_device_id"
    const val SYNCED_FASTEST_FILE = "benchmark_synced_fastest_duration"
    const val PENDING_BENCHMARK_FILE = "benchmark_pending_upload"
    const val ENTRY_COLLECTION = "benchmark_entries"
    const val BENCHMARK_VERSION = "1.0"
    const val LEGACY_TIMING_VERSION = "2"
  }

  private data class PendingBenchmark(
      val entryId: String,
      val durationMillis: Long,
      val modelUsed: String,
      val recordedAtClientMillis: Long,
      val imageWidthPixels: Int?,
      val imageHeightPixels: Int?,
      val generatedTextCharacterCount: Int?,
  )
}
