package com.deryk.skarmetoo.ai.agentic

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Parse a source-grounded model plan. Never supplies dates, subjects or amounts to the model. */
object AgenticPlanParser {
  data class NormalizationInput(
      val original: String,
      val question: String,
      val literals: Map<String, String>
  ) {
    fun restore(rewritten: String): String {
      val positions =
          literals.keys.map { token ->
            val matches = Regex(Regex.escape(token)).findAll(rewritten).toList()
            require(matches.size == 1) {
              "Copy every protected literal token exactly once; never reinterpret its value."
            }
            matches.single().range.first
          }
      require(positions == positions.sorted()) {
        "Keep protected literal tokens in their original order."
      }
      val restored =
          literals.entries.fold(rewritten) { text, (token, literal) ->
            text.replace(token, literal)
          }
      return normalizedQuestion(original, restored)
    }
  }

  /**
   * Protect user-supplied literal digits from a language-only spelling helper. No date arithmetic.
   */
  fun normalizationInput(original: String): NormalizationInput {
    var prefix = "__USER_LITERAL_"
    while (original.contains(prefix)) prefix += "_"
    val literals = linkedMapOf<String, String>()
    fun letters(value: Int): String {
      var n = value
      var result = ""
      do {
        result = ('A' + n % 26) + result
        n = n / 26 - 1
      } while (n >= 0)
      return result
    }
    val masked =
        Regex("[-+]?\\d+(?:[-/.]\\d+)*").replace(original) { match ->
          val token = "$prefix${letters(literals.size)}__"
          literals[token] = match.value
          token
        }
    return NormalizationInput(original, masked, literals)
  }

  private fun string() = JSONObject().put("type", "string")

  private fun enumeration(values: List<String>) = string().put("enum", JSONArray(values))

  fun normalizedQuestion(original: String, value: String): String {
    val normalized = value.trim()
    require(normalized.isNotBlank() && normalized.length <= 2000) {
      "Return a complete readable question, preserving the original meaning."
    }
    require(
        Regex("\\d+").findAll(original).map { it.value }.toList() ==
            Regex("\\d+").findAll(normalized).map { it.value }.toList()) {
          "Preserve all literal numbers/dates verbatim; do not insert a clock, year or amount."
        }
    AgenticQueryCoverage.validateNormalization(original, normalized)
    return normalized
  }

  val schema: JSONObject
    get() =
        AgenticContracts.objectSchema(
            linkedMapOf(
                "normalized_question" to string(),
                "intent" to enumeration(Intent.entries.map { it.name }),
                "operation" to enumeration(Operation.entries.map { it.name }),
                "action_quote" to string(),
                "subject_phrase" to string(),
                "date_clauses" to
                    JSONObject()
                        .put("type", "array")
                        .put(
                            "items",
                            AgenticContracts.objectSchema(
                                linkedMapOf(
                                    "kind" to enumeration(listOf("period", "range")),
                                    "start" to string(),
                                    "end" to
                                        JSONObject()
                                            .put("type", JSONArray(listOf("string", "null")))))),
                "search_keywords" to JSONObject().put("type", "array").put("items", string())))

  val instruction =
      """
      Independently understand the ORIGINAL user question. Do not reuse an earlier plan.
      Normalize ordinary spelling/abbreviations in normalized_question, keeping the same meaning,
      every date, every requested subject and every combined period. Never insert today's date.
      A spending/payment/expense summary, including bare PAYMENTS plus dates, uses
      intent=calculate_spending and operation=sum. Only explicit count/how-many uses count;
      only an explicit list/show/find/which/where request uses find_transaction/retrieve.
      action_quote copies that explicit count/list action from the original, otherwise empty.
      subject_phrase copies the explicitly requested item/category or merchant, otherwise empty.
      It is NOT the words spending, payments, purchases or receipts. Never drop a requested item.
      Extract EVERY time clause independently, including contained/overlapping periods.
      For one date/month/relative period use kind=period, start=the complete phrase, end=null.
      Dates joined by plain and, &, or commas are separate period objects, even in reverse order.
      Plain and never makes a date range. Only between X and Y makes and a range connector.
      For each from/to, till, until, between/and, through or hyphenated range use kind=range,
      start=the first endpoint and end=the second endpoint. A range is ONE object, never two days.
      If an endpoint omits its month, repeat the source range's stated month in that endpoint.
      When a REQUEST INVENTORY is supplied, emit exactly one clause per inventory entry,
      in the same order and with the same kind. Independently check it against the original.
      A month plus a range needs BOTH objects; a day plus a range needs BOTH objects.
      Preserve literal date spelling. For typos use the normalized relative phrase, not a bare
      word like week. No time requested means date_clauses=[] (all saved evidence).
      search_keywords are visual/semantic nouns, without requested calendar timing.
      Calendar arithmetic is performed by Kotlin. Return only the exact supplied JSON schema.
  """
          .trimIndent()

  fun subject(value: String, original: String): String? {
    val result = AgenticPaymentFacts.category(value.trim().ifBlank { null }) ?: return null
    require(original.contains(result, true)) {
      "Copy the requested subject verbatim from the original question; do not invent a category."
    }
    return result
  }

  fun parse(
      raw: String,
      original: String,
      today: LocalDate,
      earlier: QueryPlan,
      inventory: RequestInventory? = null
  ): QueryPlan {
    val o = AgenticContracts.parse(raw)
    AgenticContracts.validate(o, schema)
    // A repair may fix the model clauses but omit its redundant spelling rewrite. Keep the
    // untouched user wording in that case; never fill or reinterpret any date/intent/subject.
    val normalized = o.getString("normalized_question").trim().ifBlank { original.trim() }
    require(normalized.isNotBlank() && normalized.length <= 2000) {
      "normalized_question must contain the complete readable meaning of the original question, with spelling/abbreviations resolved."
    }
    require(
        Regex("\\d+").findAll(original).map { it.value }.toList() ==
            Regex("\\d+").findAll(normalized).map { it.value }.toList()) {
          "Spelling normalization must preserve every literal date/number; never insert a year or clock."
        }
    val intent = Intent.valueOf(o.getString("intent"))
    val operation = Operation.valueOf(o.getString("operation"))
    val action = o.getString("action_quote").trim()
    when (intent) {
      Intent.calculate_spending -> require(operation == Operation.sum)
      Intent.count_transactions -> {
        require(operation == Operation.count)
        require(
            action.isNotEmpty() &&
                original.contains(action, true) &&
                Regex("(?i)\\b(?:count|how many|number of)\\b").containsMatchIn(action)) {
              "Count requires an explicit count/how-many action quote; bare payment queries are spending summaries."
            }
      }
      Intent.find_transaction ->
          require(
              action.isNotEmpty() &&
                  original.contains(action, true) &&
                  Regex("(?i)\\b(?:list|show|find|which|where|search)\\b")
                      .containsMatchIn(action)) {
                "Listing requires an explicit list/show/find action quote; bare payment queries are spending summaries."
              }
      else -> Unit
    }
    val expressions =
        (0 until o.getJSONArray("date_clauses").length()).map { n ->
          val c = o.getJSONArray("date_clauses").getJSONObject(n)
          val start = c.getString("start").trim()
          require(start.isNotBlank())
          if (!c.isNull("end") && c.getString("end") != "null" && c.getString("end").isNotBlank()) {
            "from $start to ${c.getString("end").trim()}"
          } else {
            // "last week" is a complete interval even if labelled range by the model.
            // Source coverage and calendar resolution still reject a missing range endpoint.
            start
          }
        }
    AgenticQueryCoverage.validate(original, expressions)
    AgenticQueryCoverage.validate(normalized, expressions)
    inventory?.let {
      AgenticRequestInventory.validatePlan(
          it,
          expressions,
          (0 until o.getJSONArray("date_clauses").length()).map { i ->
            o.getJSONArray("date_clauses").getJSONObject(i).getString("kind")
          })
    }
    val filter = inventory?.bind(DateFilter(expressions)) ?: DateFilter(expressions)
    try {
      AgenticDates.resolve(filter, today)
    } catch (invalid: Exception) {
      error(
          "Calendar cannot resolve model clauses $expressions from normalized question '$normalized'. Re-read the original abbreviations/spelling and regenerate normalized_question plus full relative phrases (not bare week/month). ${invalid.message}")
    }
    val category = subject(o.getString("subject_phrase"), original)
    require(category == null || normalized.contains(category, true)) {
      "Normalization dropped the requested subject."
    }
    return earlier.copy(
        intent = intent,
        operation = operation,
        category = category,
        dateFilter = filter,
        searchKeywords = AgenticContracts.strings(o, "search_keywords"))
  }
}
