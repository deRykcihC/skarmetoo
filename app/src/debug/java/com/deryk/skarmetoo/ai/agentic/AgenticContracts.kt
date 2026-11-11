package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class Intent {
  calculate_spending,
  search_receipts,
  find_transaction,
  count_transactions,
  describe_image,
  unknown
}

enum class Operation {
  retrieve,
  verify,
  sum,
  count,
  answer,
  request_clarification
}

enum class Match {
  YES,
  NO,
  MAYBE
}

enum class PaymentStatus {
  completed,
  pending,
  refunded,
  failed,
  unknown
}

enum class WorkflowState {
  IDLE,
  PLANNING,
  RETRIEVING,
  VERIFYING,
  CALCULATING,
  RESPONDING,
  COMPLETED,
  ERROR,
  CANCELLED,
  PARTIAL_RESULT
}

data class DateFilter(
    val expressions: List<String>,
    val numericOrders: List<ChatReceiptDateOrder> = emptyList(),
    val resolvedWindows: List<ChatDateWindow>? = null
)

data class QueryPlan(
    val intent: Intent,
    val operation: Operation,
    val category: String?,
    val dateFilter: DateFilter?,
    val searchKeywords: List<String>,
    val requiredEvidence: List<String>,
    val optionalEvidence: List<String>,
    val verificationQuestion: String,
    val nextAction: Operation
)

data class ImageSearchResult(val imageId: String, val score: Double, val rank: Int)

data class ImageVerification(
    val imageId: String,
    val match: Match,
    val transactionDate: LocalDate?,
    val amount: BigDecimal?,
    val currency: String?,
    val merchant: String?,
    val category: String?,
    val paymentStatus: PaymentStatus,
    val matchedKeywords: List<String>,
    val reason: String,
    val requiresReview: Boolean
)

data class VerifiedTransaction(
    val id: String,
    val imageIds: List<String>,
    val amount: BigDecimal?,
    val currency: String?,
    val date: LocalDate?,
    val dateSource: String,
    val merchant: String?,
    val status: PaymentStatus,
    val match: Match,
    val requiresReview: Boolean
)

data class CalculationResult(
    val totals: Map<String, BigDecimal>,
    val transactions: List<VerifiedTransaction>,
    val unresolvedIds: List<String>,
    val excludedIds: List<String>
) {
  fun json(): JSONObject =
      JSONObject()
          .put("schema_version", 1)
          .put("totals", JSONObject(totals.mapValues { it.value.toPlainString() }))
          .put("transaction_ids", JSONArray(transactions.map { it.id }))
          .put("source_image_ids", JSONArray(transactions.flatMap { it.imageIds }.distinct()))
          .put("unresolved_ids", JSONArray(unresolvedIds))
          .put("excluded_ids", JSONArray(excludedIds))
          .put("count", transactions.size)
}

data class WorkflowResult(
    val answer: String,
    val calculation: CalculationResult,
    val sources: List<String>,
    val state: WorkflowState,
    val elapsedMs: Long,
    val requestedPeriods: List<String> = emptyList()
)

/** These same schemas feed the native decoder and the strict post-decoding validator. */
object AgenticContracts {
  private fun field(type: String, nullable: Boolean = false) =
      JSONObject().put("type", if (nullable) JSONArray(listOf(type, "null")) else type)

  private fun moneyField() = field("string", true).put("pattern", "^\\d{1,12}(?:\\.\\d{1,4})?$")

  private fun dateField() = field("string", true).put("pattern", "^\\d{4}-\\d{2}-\\d{2}$")

  private fun currencyField() =
      field("string", true)
          .put(
              "enum",
              JSONArray(
                  java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.sorted() +
                      listOf(JSONObject.NULL)))

  private fun enumeration(values: List<String>) = field("string").put("enum", JSONArray(values))

  private fun strings() = field("array").put("items", field("string"))

  fun objectSchema(properties: Map<String, JSONObject>) =
      JSONObject()
          .put("type", "object")
          .put("additionalProperties", false)
          .put("properties", JSONObject(properties))
          .put("required", JSONArray(properties.keys.toList()))

  private val version
    get() = field("integer").put("enum", JSONArray(listOf(1)))

  val dateSchema
    get() =
        objectSchema(mapOf("expressions" to strings()))
            .put("type", JSONArray(listOf("object", "null")))

  val planSchema
    get() =
        objectSchema(
            linkedMapOf(
                "schema_version" to version,
                "intent" to enumeration(Intent.entries.map { it.name }),
                "operation" to enumeration(Operation.entries.map { it.name }),
                "category" to field("string", true),
                "date_filter" to dateSchema,
                "search_keywords" to strings(),
                "required_evidence" to strings(),
                "optional_evidence" to strings(),
                "verification_question" to field("string"),
                "next_action" to enumeration(listOf("retrieve", "request_clarification"))))

  val verificationSchema
    get() =
        objectSchema(
            linkedMapOf(
                "schema_version" to version,
                "image_id" to field("string"),
                "match" to enumeration(Match.entries.map { it.name }),
                "transaction_date" to dateField(),
                "amount" to moneyField(),
                "currency" to currencyField(),
                "merchant" to field("string", true),
                "category" to field("string", true),
                "payment_status" to enumeration(PaymentStatus.entries.map { it.name }),
                "matched_keywords" to strings().put("maxItems", 6),
                "reason" to field("string").put("maxLength", 180),
                "requires_review" to field("boolean")))

  // Image identity is supplied by the tool dispatcher, never copied by the model.
  val verificationWireSchema
    get() =
        verificationSchema.also { schema ->
          schema.getJSONObject("properties").remove("image_id")
          schema.put(
              "required",
              JSONArray(schema.getJSONObject("properties").keys().asSequence().toList()))
        }

  /**
   * Only quotes an existing complete numeric date literal outside strings; validation still rejects
   * invalid dates.
   */
  fun quoteVerificationDate(raw: String): String =
      Regex(
              "(?<!\\\\)\"transaction_date\"\\s*:\\s*((?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2})|(?:[0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{4}))(?=\\s*[,}])")
          .replace(raw) { match ->
            match.value.replace(match.groupValues[1], JSONObject.quote(match.groupValues[1]))
          }

  /**
   * Drops only an identical malformed trailing repetition; valid duplicate JSON keys remain
   * rejected.
   */
  fun removeRepeatedReviewTransport(raw: String): String {
    val tail =
        Regex(",\\s*requires_review\"\\s*:\\s*(true|false)\\s*\\}\\s*$").find(raw) ?: return raw
    val prefix = raw.substring(0, tail.range.first)
    val decisions = Regex("\"requires_review\"\\s*:\\s*(true|false)").findAll(prefix).toList()
    return if (decisions.size == 1 && decisions.single().groupValues[1] == tail.groupValues[1])
        prefix.trimEnd() + "}"
    else raw
  }

  fun verificationResponse(raw: String, imageId: String): ImageVerification {
    val value = parse(raw)
    // Lossless formatting only: a currency prefix must agree with the independent ISO field.
    (value.opt("amount") as? String)?.let { amount ->
      Regex("^(RM|[A-Z]{3})\\s*([0-9]{1,12}(?:\\.[0-9]{1,4})?)$").matchEntire(amount.trim())?.let {
          prefixed ->
        val code = if (prefixed.groupValues[1] == "RM") "MYR" else prefixed.groupValues[1]
        require(value.opt("currency") == code) { "Amount currency prefix conflicts with currency" }
        value.put("amount", prefixed.groupValues[2])
      }
    }
    (value.opt("category") as? JSONArray)?.let { categories ->
      if (categories.length() == 1 && categories.opt(0) is String)
          value.put("category", categories.getString(0))
    }
    if (value.opt("category") == "null") value.put("category", JSONObject.NULL)
    validate(value, verificationWireSchema)
    value.put("image_id", imageId)
    return verification(value.toString(), imageId)
  }

  val searchSchema
    get() =
        objectSchema(
            mapOf(
                "schema_version" to version,
                "image_id" to field("string"),
                "score" to field("number"),
                "rank" to field("integer")))

  val transactionSchema
    get() =
        objectSchema(
            mapOf(
                "schema_version" to version,
                "id" to field("string"),
                "image_ids" to strings(),
                "amount" to moneyField(),
                "currency" to currencyField(),
                "date" to dateField(),
                "date_source" to field("string"),
                "merchant" to field("string", true),
                "status" to enumeration(PaymentStatus.entries.map { it.name }),
                "match" to enumeration(Match.entries.map { it.name }),
                "requires_review" to field("boolean")))

  val calculationSchema
    get() =
        objectSchema(
            mapOf(
                "schema_version" to version,
                "totals" to field("object").put("additionalProperties", field("string")),
                "transaction_ids" to strings(),
                "source_image_ids" to strings(),
                "unresolved_ids" to strings(),
                "excluded_ids" to strings(),
                "count" to field("integer")))

  val resultSchema
    get() =
        objectSchema(
            mapOf(
                "schema_version" to version,
                "answer" to field("string"),
                "source_image_ids" to strings()))

  fun parse(raw: String): JSONObject {
    require(raw.length <= 32_768) { "Response too large" }
    StrictJson.check(raw)
    val tokener = JSONTokener(raw.trim())
    val value = tokener.nextValue()
    require(value is JSONObject && tokener.nextClean() == '\u0000') { "Expected one JSON object" }
    return value
  }

  fun validate(value: Any?, schema: JSONObject, path: String = "root") {
    val type = schema.get("type")
    val types =
        if (type is JSONArray) (0 until type.length()).map { type.getString(it) }
        else listOf(type.toString())
    if (value == null || value === JSONObject.NULL) {
      require("null" in types) { "$path is required" }
      return
    }
    if (schema.has("enum"))
        require(
            (0 until schema.getJSONArray("enum").length()).any {
              schema.getJSONArray("enum").get(it).toString() == value.toString()
            }) {
              "$path has invalid enum"
            }
    when {
      "object" in types -> {
        require(value is JSONObject) { "$path must be an object" }
        val props = schema.optJSONObject("properties")
        if (props != null) {
          val expected = props.keys().asSequence().toSet()
          require(value.keys().asSequence().toSet() == expected) {
            "$path has missing or unexpected properties"
          }
          expected.forEach { validate(value.get(it), props.getJSONObject(it), "$path.$it") }
        } else
            schema.optJSONObject("additionalProperties")?.let { sub ->
              value.keys().forEach { validate(value.get(it), sub, "$path.$it") }
            }
      }
      "array" in types -> {
        require(value is JSONArray && value.length() <= schema.optInt("maxItems", 2048)) {
          "$path must be a bounded array"
        }
        repeat(value.length()) {
          validate(value.get(it), schema.getJSONObject("items"), "$path[$it]")
        }
      }
      "string" in types ->
          require(value is String && value.length <= schema.optInt("maxLength", 4096)) {
            "$path must be a short string"
          }
      "integer" in types -> require(value is Int || value is Long) { "$path must be an integer" }
      "number" in types ->
          require(value is Number && value.toDouble().isFinite()) { "$path must be finite" }
      "boolean" in types -> require(value is Boolean) { "$path must be boolean" }
    }
  }

  fun strings(o: JSONObject, key: String): List<String> =
      o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }

  fun nullable(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

  fun plan(raw: String, validateTemporalQuestion: Boolean = true): QueryPlan {
    val o = parse(raw)
    validate(o, planSchema)
    val plan =
        QueryPlan(
            Intent.valueOf(o.getString("intent")),
            Operation.valueOf(o.getString("operation")),
            nullable(o, "category"),
            if (o.isNull("date_filter")) null
            else DateFilter(strings(o.getJSONObject("date_filter"), "expressions")),
            strings(o, "search_keywords"),
            strings(o, "required_evidence"),
            strings(o, "optional_evidence"),
            o.getString("verification_question"),
            Operation.valueOf(o.getString("next_action")))
    require(plan.intent == Intent.unknown || plan.nextAction == Operation.retrieve) {
      "Known supported intent must retrieve existing library images. Do not ask the user for amounts, currency or images: the app obtains those from image verification."
    }
    if (plan.nextAction != Operation.request_clarification) {
      require(plan.intent != Intent.calculate_spending || plan.operation == Operation.sum) {
        "calculate_spending requires operation sum"
      }
      require(plan.intent != Intent.count_transactions || plan.operation == Operation.count) {
        "count_transactions requires operation count"
      }
      if (validateTemporalQuestion)
          require(plan.verificationQuestion.isNotBlank()) {
            "Generate an image eligibility question"
          }
      if (validateTemporalQuestion && plan.dateFilter != null) {
        require(!isTemporalConcept(plan.verificationQuestion)) {
          "verification_question must test ONE image's semantic/payment evidence only; remove all requested timing. Kotlin checks date_filter."
        }
        require(plan.searchKeywords.none { isTemporalConcept(it) }) {
          "search_keywords must contain visual concepts only; timing belongs exclusively in date_filter"
        }
      }
    }
    return plan
  }

  fun isTemporalConcept(text: String): Boolean =
      Regex(
              "(?i)\\b(?:last|past|previous|these|this|next)\\s+(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s*days?\\b")
          .containsMatchIn(text.replace('_', ' ')) ||
          Regex(
                  "(?i)\\b(?:today|yesterday|tomorrow|week|weekdays?|weekends?|month|year|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|may|june|july|august|september|october|november|december)\\b|\\b(?:past|previous|last|this|next)\\s+(?:day|night|morning|evening|hours?)\\b|\\d{1,4}[-/]\\d{1,2}")
              .containsMatchIn(text.replace('_', ' '))

  fun planJson(plan: QueryPlan): JSONObject =
      JSONObject()
          .put("schema_version", 1)
          .put("intent", plan.intent.name)
          .put("operation", plan.operation.name)
          .put("category", plan.category ?: JSONObject.NULL)
          .put(
              "date_filter",
              plan.dateFilter?.let { JSONObject().put("expressions", JSONArray(it.expressions)) }
                  ?: JSONObject.NULL)
          .put("search_keywords", JSONArray(plan.searchKeywords))
          .put("required_evidence", JSONArray(plan.requiredEvidence))
          .put("optional_evidence", JSONArray(plan.optionalEvidence))
          .put("verification_question", plan.verificationQuestion)
          .put("next_action", plan.nextAction.name)

  fun verification(raw: String, expectedId: String): ImageVerification {
    val o = parse(raw)
    validate(o, verificationSchema)
    require(o.getString("image_id") == expectedId) { "Image identity mismatch" }
    val amount =
        nullable(o, "amount")?.let {
          require(it.matches(Regex("\\d{1,12}(?:\\.\\d{1,4})?"))) {
            "Amount must be a nonnegative decimal string"
          }
          BigDecimal(it)
        }
    val currency =
        nullable(o, "currency")?.also {
          require(
              it.matches(Regex("[A-Z]{3}")) &&
                  runCatching { java.util.Currency.getInstance(it) }.isSuccess) {
                "Invalid ISO currency"
              }
        }
    return ImageVerification(
        expectedId,
        Match.valueOf(o.getString("match")),
        nullable(o, "transaction_date")?.let {
          require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
          LocalDate.parse(it)
        },
        amount,
        currency,
        nullable(o, "merchant"),
        nullable(o, "category"),
        PaymentStatus.valueOf(o.getString("payment_status")),
        strings(o, "matched_keywords"),
        o.getString("reason"),
        o.getBoolean("requires_review"))
  }

  fun verificationJson(v: ImageVerification) =
      JSONObject()
          .put("schema_version", 1)
          .put("image_id", v.imageId)
          .put("match", v.match.name)
          .put("transaction_date", v.transactionDate?.toString() ?: JSONObject.NULL)
          .put("amount", v.amount?.toPlainString() ?: JSONObject.NULL)
          .put("currency", v.currency ?: JSONObject.NULL)
          .put("merchant", v.merchant ?: JSONObject.NULL)
          .put("category", v.category ?: JSONObject.NULL)
          .put("payment_status", v.paymentStatus.name)
          .put("matched_keywords", JSONArray(v.matchedKeywords))
          .put("reason", v.reason)
          .put("requires_review", v.requiresReview)
}
