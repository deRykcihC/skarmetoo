package com.deryk.skarmetoo.ai.agentic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import org.json.JSONObject

data class AgenticImage(
    val id: String,
    val entryId: Long,
    val uri: String,
    val contentHash: String,
    val sourceDate: LocalDate?,
    val vector: FloatArray,
    val ocr: String?,
    val indexedAt: Long,
    val indexingMs: Long,
    val sourceTimestamp: Long? = null
)
/** Separate chat index; library removals also purge its image observations and cached facts. */
class AgenticIndex(context: Context) : SQLiteOpenHelper(context, "agentic_rag.db", null, 4) {
  private val appContext = context.applicationContext

  fun pruneToEntries(entries: List<ScreenshotEntry>, clearReports: Boolean = false): Int {
    val db = writableDatabase
    var removed =
        ScreenshotLibrary.pruneUris(db, "images", "uri", entries.mapTo(hashSetOf()) { it.imageUri })
    removed += db.delete("verifications", "image_id NOT IN (SELECT id FROM images)", null)
    removed += db.delete("payment_facts", "image_id NOT IN (SELECT id FROM images)", null)
    // Reports contain raw observations from multiple images, without a per-image row boundary.
    if (removed > 0 || clearReports || entries.isEmpty()) removed += db.delete("runs", null, null)
    return removed
  }

  private val verifierVersion =
      java.io.File(context.filesDir, "gemma-4-E2B-it.litertlm").let {
        "$VERIFIER_VERSION:${it.length()}:${it.lastModified()}"
      }
  private val observationVersion =
      java.io.File(context.filesDir, "gemma-4-E2B-it.litertlm").let {
        "$PAYMENT_FACTS_VERSION:${it.length()}:${it.lastModified()}"
      }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE images (id TEXT NOT NULL, model TEXT NOT NULL, entry_id INTEGER NOT NULL, uri TEXT NOT NULL, content_hash TEXT NOT NULL, source_date TEXT, source_timestamp INTEGER, vector BLOB NOT NULL, dimensions INTEGER NOT NULL, indexed_at INTEGER NOT NULL, ocr TEXT, extraction_status TEXT NOT NULL, indexing_ms INTEGER NOT NULL, PRIMARY KEY(id,model))")
    db.execSQL(
        "CREATE TABLE verifications (image_id TEXT NOT NULL, model TEXT NOT NULL, plan_hash TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(image_id,model,plan_hash))")
    db.execSQL(
        "CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, started_at INTEGER NOT NULL, json TEXT NOT NULL)")
    createPlans(db)
    createFacts(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) db.execSQL("ALTER TABLE images ADD COLUMN source_timestamp INTEGER")
    if (oldVersion < 3) createPlans(db)
    if (oldVersion < 4) createFacts(db)
  }

  private fun createFacts(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS payment_facts (image_id TEXT NOT NULL, model TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(image_id,model))")
  }

  fun paymentFacts(imageId: String): String? =
      readableDatabase
          .rawQuery(
              "SELECT json FROM payment_facts WHERE image_id=? AND model=?",
              arrayOf(imageId, observationVersion))
          .use { c ->
            if (c.moveToFirst())
                runCatching {
                      c.getString(0).also { AgenticPaymentFacts.verification(it, imageId) }
                    }
                    .getOrNull()
            else null
          }

  fun cachePaymentFacts(imageId: String, raw: String) =
      synchronized(ScreenshotLibrary.lock) {
        val v = AgenticPaymentFacts.verification(raw, imageId)
        // A persisted uncertain observation stays MAYBE on every read. It is never a confirmation.
        if (v.match == Match.YES && (v.amount == null || v.currency == null)) return@synchronized
        if (!contains(imageId)) return@synchronized
        val values =
            ContentValues().apply {
              put("image_id", imageId)
              put("model", observationVersion)
              put("json", raw)
              put("created_at", System.currentTimeMillis())
            }
        writableDatabase.insertWithOnConflict(
            "payment_facts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }

  private fun createPlans(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS plans (cache_key TEXT NOT NULL, model TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(cache_key,model))")
  }

  fun cachedPlan(key: String): String? =
      readableDatabase
          .rawQuery(
              "SELECT json FROM plans WHERE cache_key=? AND model=?",
              arrayOf(key, "$verifierVersion:planner-v27"))
          .use { c ->
            if (c.moveToFirst())
                runCatching {
                      c.getString(0).also {
                        AgenticContracts.plan(JSONObject(it).getJSONObject("plan").toString())
                        require(JSONObject(it).has("request_inventory"))
                      }
                    }
                    .getOrNull()
            else null
          }

  fun cachePlan(
      key: String,
      raw: String,
      inventory: RequestInventory,
      timeFrames: JSONObject? = null,
      timeOperations: JSONObject? = null,
      clockContext: JSONObject? = null
  ) {
    AgenticContracts.plan(raw)
    val values =
        ContentValues().apply {
          put("cache_key", key)
          put("model", "$verifierVersion:planner-v27")
          put(
              "json",
              JSONObject()
                  .put("plan", JSONObject(raw))
                  .put("request_inventory", inventory.json())
                  .put("model_time_frames", timeFrames ?: JSONObject.NULL)
                  .put("model_time_operations", timeOperations ?: JSONObject.NULL)
                  .put("clock_context", clockContext ?: JSONObject.NULL)
                  .toString())
          put("created_at", System.currentTimeMillis())
        }
    writableDatabase.insertWithOnConflict("plans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun contains(id: String) =
      readableDatabase
          .rawQuery(
              "SELECT 1 FROM images WHERE id=? AND model=?",
              arrayOf(id, EmbeddingGemma2.MODEL_VERSION))
          .use { it.moveToFirst() }

  fun rebind(id: String, entryId: Long, uri: String) =
      synchronized(ScreenshotLibrary.lock) {
        if (!ScreenshotLibrary.contains(appContext, uri, entryId)) return@synchronized
        val values =
            ContentValues().apply {
              put("entry_id", entryId)
              put("uri", uri)
            }
        writableDatabase.update(
            "images", values, "id=? AND model=?", arrayOf(id, EmbeddingGemma2.MODEL_VERSION))
      }

  fun put(image: AgenticImage) =
      synchronized(ScreenshotLibrary.lock) {
        if (!ScreenshotLibrary.contains(appContext, image.uri, image.entryId)) return@synchronized
        require(image.vector.size == EmbeddingGemma2.DIMENSIONS)
        val bytes = ByteBuffer.allocate(image.vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        image.vector.forEach { bytes.putFloat(it) }
        val values =
            ContentValues().apply {
              put("id", image.id)
              put("model", EmbeddingGemma2.MODEL_VERSION)
              put("entry_id", image.entryId)
              put("uri", image.uri)
              put("content_hash", image.contentHash)
              put("source_date", image.sourceDate?.toString())
              put("vector", bytes.array())
              put("dimensions", image.vector.size)
              put("indexed_at", image.indexedAt)
              put("ocr", image.ocr)
              put("extraction_status", "UNKNOWN")
              put("indexing_ms", image.indexingMs)
            }
        image.sourceTimestamp?.let { values.put("source_timestamp", it) }
        writableDatabase.insertWithOnConflict(
            "images", null, values, SQLiteDatabase.CONFLICT_IGNORE)
      }

  fun images(): List<AgenticImage> =
      readableDatabase
          .rawQuery(
              "SELECT id,entry_id,uri,content_hash,source_date,vector,dimensions,ocr,indexed_at,indexing_ms,source_timestamp FROM images WHERE model=?",
              arrayOf(EmbeddingGemma2.MODEL_VERSION))
          .use { c ->
            buildList {
              while (c.moveToNext()) {
                val raw = c.getBlob(5)
                val n = c.getInt(6)
                require(n == EmbeddingGemma2.DIMENSIONS && raw.size == n * 4)
                val data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                add(
                    AgenticImage(
                        c.getString(0),
                        c.getLong(1),
                        c.getString(2),
                        c.getString(3),
                        if (c.isNull(4)) null else LocalDate.parse(c.getString(4)),
                        FloatArray(n) { data.float },
                        if (c.isNull(7)) null else c.getString(7),
                        c.getLong(8),
                        c.getLong(9),
                        if (c.isNull(10)) null else c.getLong(10)))
              }
            }
          }

  fun cached(imageId: String, planHash: String): ImageVerification? =
      readableDatabase
          .rawQuery(
              "SELECT json FROM verifications WHERE image_id=? AND model=? AND plan_hash=?",
              arrayOf(imageId, verifierVersion, planHash))
          .use { c ->
            if (c.moveToFirst())
                runCatching { AgenticContracts.verification(c.getString(0), imageId) }.getOrNull()
            else null
          }

  /** Date facts may screen another query; its old YES/NO decision must never approve that query. */
  fun verifiedEventDates(imageId: String): List<LocalDate> =
      readableDatabase
          .rawQuery(
              "SELECT json FROM verifications WHERE image_id=? AND model=?",
              arrayOf(imageId, verifierVersion))
          .use { cursor ->
            buildList {
                  while (cursor.moveToNext()) {
                    val value =
                        runCatching { AgenticContracts.verification(cursor.getString(0), imageId) }
                            .getOrNull()
                    if (value != null &&
                        value.match == Match.YES &&
                        !value.requiresReview &&
                        value.paymentStatus == PaymentStatus.completed)
                        value.transactionDate?.let { add(it) }
                  }
                }
                .distinct()
          }

  fun cache(v: ImageVerification, planHash: String) =
      synchronized(ScreenshotLibrary.lock) {
        if (!contains(v.imageId)) return@synchronized
        val values =
            ContentValues().apply {
              put("image_id", v.imageId)
              put("model", verifierVersion)
              put("plan_hash", planHash)
              put("json", AgenticContracts.verificationJson(v).toString())
              put("created_at", System.currentTimeMillis())
            }
        writableDatabase.insertWithOnConflict(
            "verifications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }

  fun saveRun(
      question: String,
      started: Long,
      report: JSONObject,
      expectedRevision: Long = ScreenshotLibrary.removalRevision.value
  ) =
      synchronized(ScreenshotLibrary.lock) {
        if (expectedRevision != ScreenshotLibrary.removalRevision.value) return@synchronized
        val values =
            ContentValues().apply {
              put("question", question)
              put("started_at", started)
              put("json", report.toString())
            }
        writableDatabase.insertOrThrow("runs", null, values)
      }

  companion object {
    const val VERIFIER_VERSION =
        "gemma4-e2b:181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c:verification-v17"
    // V13's validated eight-field observations are unchanged by V14 planning repairs.
    const val PAYMENT_FACTS_VERSION =
        "gemma4-e2b:181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c:verification-v13"
  }
}
