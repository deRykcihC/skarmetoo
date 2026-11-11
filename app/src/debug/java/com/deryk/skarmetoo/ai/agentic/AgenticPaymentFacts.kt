package com.deryk.skarmetoo.ai.agentic

import java.time.LocalDate
import org.json.JSONObject

/** Question-independent observations. Query relevance is checked separately. */
object AgenticPaymentFacts {
  data class Observation(
      val verification: ImageVerification,
      val unresolvedFields: List<String>,
      val changes: List<String>
  )

  fun date(value: String): LocalDate =
      if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) LocalDate.parse(value)
      else if (value.matches(Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"))) {
        val parts = value.split(Regex("[-/.]"))
        LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
      } else {
        val match =
            requireNotNull(Regex("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})").matchEntire(value)) {
              "Expected a printed ISO or DD-MM-YYYY date"
            }
        LocalDate.of(
            match.groupValues[3].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[1].toInt())
      }

  fun category(value: String?): String? =
      value?.takeUnless {
        it.equals("null", true) ||
            Regex(
                    "(?i)(?:(?:all|total|my|confirmed|outgoing|paid)\\s+)*(?:spending|expenses?|transactions?|payments?|receipts?|purchases?|paid|spent|pay|spend)")
                .matches(it.trim())
      }

  private fun field(type: String, nullable: Boolean = false) =
      JSONObject().put("type", if (nullable) org.json.JSONArray(listOf(type, "null")) else type)

  private fun enum(vararg values: String) =
      field("string").put("enum", org.json.JSONArray(values.toList()))

  val schema
    get() =
        AgenticContracts.objectSchema(
            linkedMapOf(
                "image_kind" to
                    enum(
                        "payment_receipt",
                        "invoice",
                        "cart_checkout",
                        "menu",
                        "product_listing",
                        "order_summary",
                        "trade_statement",
                        "other",
                        "unknown"),
                "direction" to enum("outgoing", "incoming", "unknown"),
                "payment_status" to enum("completed", "pending", "refunded", "failed", "unknown"),
                "transaction_date" to field("string", true),
                "amount" to field("string", true),
                "currency" to field("string", true),
                "merchant" to field("string", true),
                "evidence" to field("string").put("maxLength", 240)))

  fun verification(raw: String, id: String): ImageVerification {
    val value = observation(raw, id)
    require(value.unresolvedFields.isEmpty()) {
      "Unresolved financial fields: ${value.unresolvedFields}"
    }
    return value.verification
  }

  fun observation(raw: String, id: String): Observation {
    val formatting = AgenticResponseFormatting.normalize(raw, schema)
    val o = AgenticContracts.parse(formatting.text)
    listOf("transaction_date", "amount", "currency", "merchant").forEach { key ->
      if (o.opt(key) == "null") o.put(key, JSONObject.NULL)
    }
    AgenticContracts.validate(o, schema)
    val kind = o.getString("image_kind")
    val direction = o.getString("direction")
    val status = PaymentStatus.valueOf(o.getString("payment_status"))
    val match =
        when {
          o.getString("evidence").isBlank() -> Match.MAYBE
          kind == "other" && (status == PaymentStatus.completed || !o.isNull("amount")) ->
              Match.MAYBE
          kind in setOf("cart_checkout", "menu", "product_listing") &&
              (status == PaymentStatus.completed || direction == "outgoing") -> Match.MAYBE
          kind in setOf("cart_checkout", "menu", "product_listing") -> Match.NO
          kind in setOf("other", "invoice") ||
              direction == "incoming" ||
              status in
                  setOf(PaymentStatus.failed, PaymentStatus.pending, PaymentStatus.refunded) ->
              Match.NO
          kind == "payment_receipt" &&
              direction == "outgoing" &&
              status == PaymentStatus.completed -> Match.YES
          else -> Match.MAYBE
        }
    val wire =
        AgenticContracts.verificationJson(
                ImageVerification(
                    id,
                    match,
                    null,
                    null,
                    null,
                    null,
                    null,
                    status,
                    emptyList(),
                    o.getString("evidence").take(180),
                    match == Match.MAYBE))
            .also { it.remove("image_id") }
    listOf("transaction_date", "amount", "currency", "merchant").forEach { wire.put(it, o.get(it)) }
    val unresolved = mutableListOf<String>()
    if (!o.isNull("transaction_date")) {
      val parsed = runCatching { date(o.getString("transaction_date")) }.getOrNull()
      if (parsed == null) unresolved += "transaction_date"
      wire.put("transaction_date", parsed?.toString() ?: JSONObject.NULL)
    }
    // A minus on a confirmed outgoing payment is its direction, not a refund. Never apply
    // this conversion to incoming, refunded or unknown records, or conflicting currency.
    val amount = o.opt("amount") as? String
    if (match == Match.YES && amount != null) {
      val token =
          Regex("^-(RM|[A-Z]{3})?\\s*([0-9]{1,12}(?:\\.[0-9]{1,4})?)$").matchEntire(amount.trim())
      if (token != null) {
        val prefix = token.groupValues[1]
        val code = if (prefix == "RM") "MYR" else prefix
        require(prefix.isEmpty() || code == o.opt("currency")) {
          "Signed amount currency conflicts"
        }
        wire.put("amount", token.groupValues[2])
      }
    }
    (wire.opt("amount") as? String)?.let { value ->
      Regex("^(RM|[A-Z]{3})\\s*([0-9]{1,12}(?:\\.[0-9]{1,4})?)$").matchEntire(value.trim())?.let {
          token ->
        val code = if (token.groupValues[1] == "RM") "MYR" else token.groupValues[1]
        require(code == o.opt("currency")) { "Amount currency prefix conflicts" }
        wire.put("amount", token.groupValues[2])
      }
    }
    (wire.opt("amount") as? String)?.let {
      if (!it.matches(Regex("[0-9]{1,12}(?:\\.[0-9]{1,4})?"))) {
        unresolved += "amount"
        wire.put("amount", JSONObject.NULL)
      }
    }
    (wire.opt("currency") as? String)?.let {
      if (runCatching { java.util.Currency.getInstance(it) }.isFailure) {
        unresolved += "currency"
        wire.put("currency", JSONObject.NULL)
      }
    }
    if (unresolved.isNotEmpty()) {
      wire
          .put("match", "MAYBE")
          .put("requires_review", true)
          .put(
              "reason", "Unresolved model fields: ${unresolved.joinToString()}. No values guessed.")
    }
    return Observation(
        AgenticContracts.verificationResponse(wire.toString(), id), unresolved, formatting.changes)
  }

  /** Text-only syntax repairs may not change or invent a financial observation. */
  fun validateRepair(original: String, repaired: JSONObject) {
    val source =
        try {
          AgenticResponseFormatting.normalize(original, schema).text
        } catch (invalid: Exception) {
          require(invalid.message != "Duplicate JSON property") {
            "Conflicting/duplicate original JSON fields"
          }
          original
        }
    listOf(
            "image_kind",
            "direction",
            "payment_status",
            "transaction_date",
            "amount",
            "currency",
            "merchant")
        .forEach { key ->
          val tokens =
              Regex(
                      "\\\"$key\\\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|null|-?[0-9]+(?:\\.[0-9]+)?)(?=\\s*(?:[,}]|$))")
                  .findAll(source)
                  .map { match ->
                    val token = match.groupValues[1]
                    if (token != "null" && !token.startsWith('"')) token
                    else AgenticContracts.parse("{\"value\":$token}").get("value").toString()
                  }
                  .toList()
          require(tokens.size <= 1) { "Conflicting original financial field: $key" }
          val dateEquivalent =
              key == "transaction_date" &&
                  tokens.size == 1 &&
                  tokens.single() != "null" &&
                  !repaired.isNull(key) &&
                  runCatching { date(tokens.single()) == date(repaired.getString(key)) }
                      .getOrDefault(false)
          require(
              dateEquivalent ||
                  (tokens.isEmpty() && repaired.isNull(key)) ||
                  (tokens.isEmpty() &&
                      key in setOf("image_kind", "direction", "payment_status") &&
                      repaired.opt(key) == "unknown") ||
                  tokens.singleOrNull() == repaired.get(key).toString()) {
                "Text repair changed or invented $key"
              }
        }
  }

  fun relevance(v: ImageVerification, decision: JSONObject, evidence: String): ImageVerification {
    val match = Match.valueOf(decision.getString("match"))
    val quote = decision.getString("evidence_quote").trim()
    val grounded =
        quote.isNotBlank() &&
            evidence.replace(Regex("\\s+"), " ").contains(quote.replace(Regex("\\s+"), " "), true)
    return v.copy(
        match = if (match != Match.MAYBE && !grounded) Match.MAYBE else match,
        requiresReview = v.requiresReview || match == Match.MAYBE || !grounded,
        reason = decision.getString("reason").take(180))
  }
}
