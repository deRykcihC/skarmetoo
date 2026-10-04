package com.deryk.skarmetoo.network

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Instant
import java.util.Date

/** Reads the daily leaderboard snapshot produced by the Redis sync Worker. */
class BenchmarkLeaderboardRepository(context: Context) {
  private val appContext = context.applicationContext
  private val firestore: FirebaseFirestore? = configuredFirestore()

  fun load(currentDeviceId: String? = null, onComplete: (Result<LeaderboardSnapshot>) -> Unit) {
    val database = firestore
    if (database == null) {
      onComplete(Result.failure(IllegalStateException("Firebase is not configured")))
      return
    }

    val snapshots = database.collection(SNAPSHOT_COLLECTION)
    snapshots
        .orderBy("snapshotAt", Query.Direction.DESCENDING)
        .limit(1)
        .get()
        .addOnSuccessListener { result ->
          completeFromDocuments(result.documents, currentDeviceId, onComplete)
        }
        .addOnFailureListener {
          // A missing/older snapshotAt index should not prevent loading the latest document.
          snapshots
              .get()
              .addOnSuccessListener { result ->
                completeFromDocuments(
                    result.documents.sortedByDescending { it.date("snapshotAt")?.time ?: 0L },
                    currentDeviceId,
                    onComplete,
                )
              }
              .addOnFailureListener { error -> onComplete(Result.failure(error)) }
        }
  }

  private fun completeFromDocuments(
      documents: List<DocumentSnapshot>,
      currentDeviceId: String?,
      onComplete: (Result<LeaderboardSnapshot>) -> Unit,
  ) {
    val latest = documents.firstOrNull()
    if (latest == null) {
      onComplete(Result.failure(IllegalStateException("No leaderboard snapshot is available")))
      return
    }
    val snapshot = runCatching { toSnapshot(latest) }
    if (snapshot.isFailure) {
      onComplete(snapshot)
      return
    }

    val deviceId = currentDeviceId?.trim().orEmpty()
    if (deviceId.isBlank()) {
      onComplete(snapshot)
      return
    }

    // A daily snapshot only contains its top-N entries. Read this device's
    // successful uploads from the local backup instead of querying the entry
    // collection, so opening the leaderboard does not create extra traffic.
    val deviceEntries =
        BenchmarkLeaderboardClient.loadLocalUploadedEntries(appContext, deviceId)
    onComplete(Result.success(snapshot.getOrThrow().copy(deviceEntries = deviceEntries)))
  }

  private fun toSnapshot(document: DocumentSnapshot): LeaderboardSnapshot {
    val rankingFields = document.get("rankings") as? Map<*, *> ?: emptyMap<Any, Any>()
    val rankings =
        LeaderboardMetric.entries.associateWith { metric ->
          toEntries(rankingFields[metric.snapshotField])
        }
    // New snapshots contain a separate ranking for each supported model. Keep
    // the flat rankings above as a compatibility fallback for older snapshots.
    val rankingsByModelFields = document.get("rankingsByModel") as? Map<*, *>
    val modelRankings =
        LeaderboardModel.entries.associateWith { model ->
          val modelFields =
              (rankingsByModelFields?.get(model.snapshotKey) as? Map<*, *>)
                  ?: (rankingsByModelFields?.get(model.displayName) as? Map<*, *>)
          LeaderboardMetric.entries.associateWith { metric ->
            val nested = modelFields?.get(metric.snapshotField)
            val flat = rankingFields[metric.modelSnapshotField(model)]
            toEntries(nested ?: flat)
          }
        }
    return LeaderboardSnapshot(
        rankings = rankings,
        modelRankings = modelRankings,
        benchmarkVersion = document.string("benchmarkVersion"),
        snapshotAt = document.date("snapshotAt"),
        snapshotDate = document.string("snapshotDate") ?: document.id,
        topN = document.long("topN")?.toInt(),
    )
  }

  private fun toEntries(value: Any?): List<LeaderboardEntry> =
      (value as? Iterable<*>)
          ?.mapNotNull(::toEntry)
          ?.sortedWith(
              compareBy<LeaderboardEntry> { it.rank ?: Int.MAX_VALUE }
                  .thenBy { it.metricScore ?: it.durationMillis?.toDouble() ?: Double.MAX_VALUE },
          )
          ?: emptyList()

  private fun toEntry(value: Any?): LeaderboardEntry? {
    val fields = value as? Map<*, *> ?: return null
    val deviceId = fields.string("deviceId") ?: return null
    return LeaderboardEntry(
        rank = fields.long("rank")?.toInt(),
        metricScore =
            fields.double("metricScore")
                ?: fields.double("score")
                ?: fields.double("rankingScore"),
        deviceId = deviceId,
        entryId = fields.string("entryId"),
        benchmarkVersion = fields.string("benchmarkVersion"),
        durationMillis = fields.long("durationMillis"),
        durationSeconds = fields.double("durationSeconds"),
        analyzedImageWidthPixels = fields.long("analyzedImageWidthPixels")?.toInt(),
        analyzedImageHeightPixels = fields.long("analyzedImageHeightPixels")?.toInt(),
        analyzedImagePixelCount = fields.long("analyzedImagePixelCount"),
        generatedTextCharacterCount = fields.long("generatedTextCharacterCount")?.toInt(),
        deviceManufacturer = fields.string("deviceManufacturer") ?: fields.string("brand"),
        deviceModel = fields.string("deviceModel") ?: fields.string("model"),
        androidSdk = fields.long("androidSdk")?.toInt(),
        appId = fields.string("appId"),
        appVersionCode = fields.long("appVersionCode"),
        appVersionName = fields.string("appVersionName"),
        modelUsed = fields.string("modelUsed") ?: fields.string("aiModel"),
        recordedAt = fields.date("recordedAt"),
        recordedAtClientMillis = fields.long("recordedAtClientMillis"),
        snapshotAt = fields.date("snapshotAt"),
    )
  }

  private fun configuredFirestore(): FirebaseFirestore? =
      runCatching {
            val app =
                FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(appContext)
            app?.let { FirebaseFirestore.getInstance(it) }
          }
          .getOrNull()

  private companion object {
    const val SNAPSHOT_COLLECTION = "benchmark_leaderboard_snapshots"
  }
}

enum class LeaderboardMetric(val snapshotField: String) {
  FASTEST_TIME_PER_IMAGE("fastestTimePerImage"),
  FASTEST_TIME_PER_TEXT("fastestTimePerText"),
}

enum class LeaderboardModel(val snapshotKey: String, val displayName: String) {
  GEMMA_4("gemma4", "Gemma 4"),
  LFM_2_5_VL("lfm25vl", "LFM 2.5 VL"),
  ALL("all", "All"),

  ;

  fun matches(rawModel: String?): Boolean =
      rawModel
          ?.lowercase(java.util.Locale.US)
          ?.filter(Char::isLetterOrDigit)
          ?.let { normalized ->
            if (this == ALL) {
              normalized == GEMMA_4.snapshotKey || normalized == LFM_2_5_VL.snapshotKey
            } else {
              normalized == snapshotKey
            }
          }
          ?: false
}

private fun LeaderboardMetric.modelSnapshotField(model: LeaderboardModel): String =
    "${snapshotField}_${model.snapshotKey}"

data class LeaderboardSnapshot(
    val rankings: Map<LeaderboardMetric, List<LeaderboardEntry>>,
    val modelRankings: Map<LeaderboardModel, Map<LeaderboardMetric, List<LeaderboardEntry>>> =
        emptyMap(),
    val deviceEntries: List<LeaderboardEntry> = emptyList(),
    val benchmarkVersion: String?,
    val snapshotAt: Date?,
    val snapshotDate: String?,
    val topN: Int?,
) {
  fun entriesFor(metric: LeaderboardMetric): List<LeaderboardEntry> = rankings[metric].orEmpty()

  fun entriesFor(metric: LeaderboardMetric, model: LeaderboardModel): List<LeaderboardEntry> =
      modelRankings[model]?.get(metric) ?: entriesFor(metric)

  fun bestDeviceEntryFor(metric: LeaderboardMetric, model: LeaderboardModel): LeaderboardEntry? =
      deviceEntries
          .asSequence()
          .filter { model.matches(it.modelUsed) }
          .mapNotNull { entry -> leaderboardScore(entry, metric)?.let { it to entry } }
          .minByOrNull { it.first }
          ?.second
}

data class LeaderboardEntry(
    val rank: Int?,
    val metricScore: Double?,
    val deviceId: String,
    val entryId: String?,
    val benchmarkVersion: String?,
    val durationMillis: Long?,
    val durationSeconds: Double?,
    val analyzedImageWidthPixels: Int?,
    val analyzedImageHeightPixels: Int?,
    val analyzedImagePixelCount: Long?,
    val generatedTextCharacterCount: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val androidSdk: Int?,
    val appId: String?,
    val appVersionCode: Long?,
    val appVersionName: String?,
    val modelUsed: String?,
    val recordedAt: Date?,
    val recordedAtClientMillis: Long?,
    val snapshotAt: Date?,
)

private fun Map<*, *>.string(field: String): String? =
    (this[field] as? String)?.takeIf { it.isNotBlank() }

private fun Map<*, *>.long(field: String): Long? =
    when (val value = this[field]) {
      is Number -> value.toLong()
      is String -> value.toLongOrNull()
      else -> null
    }

private fun Map<*, *>.double(field: String): Double? =
    when (val value = this[field]) {
      is Number -> value.toDouble()
      is String -> value.toDoubleOrNull()
      else -> null
    }

private fun leaderboardScore(entry: LeaderboardEntry, metric: LeaderboardMetric): Double? =
    when (metric) {
      LeaderboardMetric.FASTEST_TIME_PER_IMAGE ->
          entry.durationSeconds ?: entry.durationMillis?.div(1000.0)
      LeaderboardMetric.FASTEST_TIME_PER_TEXT ->
          entry.metricScore
              ?: entry.durationMillis?.toDouble()?.let { duration ->
                entry.generatedTextCharacterCount?.takeIf { it > 0 }?.let { textCount ->
                  duration / textCount
                }
              }
    }

private fun Map<*, *>.date(field: String): Date? = dateValue(this[field])

private fun DocumentSnapshot.string(field: String): String? =
    getString(field)?.takeIf { it.isNotBlank() }

private fun DocumentSnapshot.long(field: String): Long? =
    getLong(field) ?: getDouble(field)?.toLong()

private fun DocumentSnapshot.date(field: String): Date? = dateValue(get(field))

private fun dateValue(value: Any?): Date? =
    runCatching {
          when (value) {
            is Timestamp -> value.toDate()
            is Date -> value
            is Number -> Date(value.toLong())
            is String -> Date.from(Instant.parse(value))
            else -> null
          }
        }
        .getOrNull()
