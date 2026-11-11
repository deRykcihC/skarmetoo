package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** Only wording is generated: all amounts and date groups come from deduplicated calculations. */
internal class AgenticAnswerSynthesis(private val result: CalculationResult) {
  data class Day(
      val id: String,
      val date: String?,
      val totals: Map<String, BigDecimal>,
      val count: Int
  )

  val days =
      result.transactions
          .groupBy { it.date }
          .entries
          .sortedWith(compareBy(nullsLast()) { it.key })
          .mapIndexed { i, (date, transactions) ->
            Day(
                "day_$i",
                date?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                transactions
                    .filter { it.amount != null && it.currency != null }
                    .groupBy { it.currency!! }
                    .mapValues { (_, items) ->
                      items.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount!! }
                    },
                transactions.size)
          }

  val schema =
      AgenticContracts.objectSchema(
          mapOf(
              "summary" to JSONObject().put("type", "string"),
              "breakdown" to
                  JSONObject()
                      .put("type", "array")
                      .put(
                          "items",
                          AgenticContracts.objectSchema(
                              mapOf(
                                  "id" to JSONObject().put("type", "string"),
                                  "text" to JSONObject().put("type", "string")))),
              "review_note" to JSONObject().put("type", "string"),
              "layout" to
                  JSONObject()
                      .put("type", "string")
                      .put("enum", JSONArray(listOf("paragraph", "lines", "bullets")))))

  fun prompt(question: String, intent: Intent, category: String?, batch: List<Day>): String {
    val facts =
        JSONObject()
            .put("intent", intent.name)
            .put("category", category ?: JSONObject.NULL)
            .put("confirmed_totals", moneyJson(result.totals))
            .put("confirmed_transaction_count", result.transactions.size)
            .put("unresolved_image_count", result.unresolvedIds.size)
            .put(
                "days",
                JSONArray(
                    batch.map { day ->
                      JSONObject()
                          .put("id", day.id)
                          .put("date", day.date ?: JSONObject.NULL)
                          .put("confirmed_totals", moneyJson(day.totals))
                          .put("confirmed_transaction_count", day.count)
                    }))
    return """
      Answer the user's question naturally and concisely using only these authoritative facts. Question: ${JSONObject.quote(question)}.
      Facts: $facts
      You write wording only; never calculate, invent amounts, assume zero spending, or claim that missing evidence proves no spending.
      summary: Explain the overall confirmed totals with a currency before each amount (e.g. MYR 12.34). If no confirmed transactions, explain that no matching payment is confirmed.
      breakdown: Include every supplied day exactly once, using its id. In text, keep its date exactly as dd/MM/yyyy and its confirmed amount(s) with currency together. A null date means payment date unknown; do not invent a date. Keep each entry short; do not repeat 'confirmed total' or transaction counts on each day. Counts are optional. Do not include numbering, extra dates or extra amounts.
      review_note: If unresolved_image_count is positive, write one brief note explaining that unverified images are excluded and payments may be missing. Choose your own wording rather than copying these instructions. Otherwise use an empty string.
      Choose paragraph, lines, or bullets as layout to suit this answer. Return only the requested JSON. The app attaches receipt sources and requested date periods separately.
    """
        .trimIndent()
  }

  fun validate(json: JSONObject, batch: List<Day>) {
    AgenticContracts.validate(json, schema)
    val summary = json.getString("summary")
    validateText(summary, result.totals, setOf(result.transactions.size), null)
    if (result.transactions.isEmpty())
        require(Regex("(?i)\\bno\\b|\\bnone\\b").containsMatchIn(summary)) {
          "Explain that there is no confirmed matching evidence; do not invent a zero total"
        }
    val entries = json.getJSONArray("breakdown")
    require(entries.length() == batch.size) { "Include exactly ${batch.size} supplied date groups" }
    val seen = mutableSetOf<String>()
    for (i in 0 until entries.length()) {
      val entry = entries.getJSONObject(i)
      val day = batch.singleOrNull { it.id == entry.getString("id") }
      require(day != null && seen.add(day.id)) { "Unknown or repeated date group" }
      validateText(entry.getString("text"), day.totals, setOf(day.count), day.date)
      if (day.date == null)
          require(
              Regex("(?i)unknown|undated|unavailable|not (?:known|visible|available)")
                  .containsMatchIn(entry.getString("text"))) {
                "Explain that this payment date is unknown"
              }
    }
    val note = json.getString("review_note")
    if (result.unresolvedIds.isNotEmpty()) {
      require(
          Regex("(?i)review|unresolved|unverified|uncertain|unconfirmed").containsMatchIn(note) &&
              Regex("(?i)exclud|omit|not included|not counted|may be|could be|incomplete")
                  .containsMatchIn(note)) {
            "Explain naturally that unreviewed evidence is excluded and the total may be incomplete"
          }
      require(Regex("(?i)confirmed|verified|found|identified|recorded").containsMatchIn(summary)) {
        "Qualify these as confirmed amounts while evidence remains unresolved"
      }
      validateText(note, emptyMap(), setOf(result.unresolvedIds.size), null)
    } else require(note.isBlank()) { "Do not add uncertainty when no evidence needs review" }
  }

  private fun validateText(
      text: String,
      totals: Map<String, BigDecimal>,
      counts: Set<Int>,
      date: String?
  ) {
    require(text.isNotBlank() && !text.trimStart().startsWith("{")) {
      "Write a nonempty readable answer"
    }
    val dates = Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b").findAll(text).map { it.value }.toList()
    require(if (date == null) dates.isEmpty() else dates.isNotEmpty() && dates.all { it == date }) {
      "Keep the supplied date with its own amounts, exactly as dd/MM/yyyy"
    }
    val money = Regex("(?i)\\b([A-Z]{3}|RM)\\s+(\\d+(?:\\.\\d+)?)\\b")
    val found =
        money
            .findAll(text)
            .filter { match ->
              match.groupValues[1].equals("RM", true) ||
                  runCatching { java.util.Currency.getInstance(match.groupValues[1].uppercase()) }
                      .isSuccess
            }
            .toList()
    found.forEach { match ->
      val currency = match.groupValues[1].uppercase().let { if (it == "RM") "MYR" else it }
      require(totals[currency]?.compareTo(match.groupValues[2].toBigDecimal()) == 0) {
        "Use only this section's verified currency and amount; do not swap dates or currencies"
      }
    }
    totals.forEach { (currency, total) ->
      require(
          found.any { match ->
            val code = match.groupValues[1].uppercase().let { if (it == "RM") "MYR" else it }
            code == currency && total.compareTo(match.groupValues[2].toBigDecimal()) == 0
          }) {
            "Include $currency ${total.toPlainString()}"
          }
    }
    var remaining = text
    (Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b").findAll(text).map { it.range } +
            found.map { it.range })
        .sortedByDescending { it.first }
        .forEach { remaining = remaining.removeRange(it) }
    require(
        Regex("\\d+(?:\\.\\d+)?").findAll(remaining).all { match ->
          counts.any { match.value.toBigDecimal().compareTo(it.toBigDecimal()) == 0 }
        }) {
          "Do not introduce other dates, counts or amounts"
        }
  }

  fun render(parts: List<JSONObject>): String {
    val first = parts.first()
    val texts =
        parts.flatMap { part ->
          val entries = part.getJSONArray("breakdown")
          (0 until entries.length()).map { entries.getJSONObject(it).getString("text").trim() }
        }
    val breakdown =
        when (first.getString("layout")) {
          "paragraph" ->
              texts
                  .mapIndexed { i, text ->
                    if (text.lastOrNull() in listOf('.', '!', '?', ';')) text
                    else text + if (i == texts.lastIndex) "." else ";"
                  }
                  .joinToString(" ")
          "bullets" -> texts.joinToString("\n") { "• $it" }
          else -> texts.joinToString("\n")
        }
    return listOf(first.getString("summary"), breakdown, first.getString("review_note"))
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
  }

  fun fallback(intent: Intent, category: String?): String {
    val headline =
        if (result.totals.isNotEmpty()) "Confirmed spending: ${moneyText(result.totals)}."
        else AgenticCalculation.answer(intent, result.copy(unresolvedIds = emptyList()), category)
    val breakdown =
        days.joinToString("\n") {
          "${it.date ?: "Payment date unknown"}: ${moneyText(it.totals).ifBlank { "${it.count} confirmed payments" }}."
        }
    val note =
        if (result.unresolvedIds.isNotEmpty())
            "${result.unresolvedIds.size} images still need review and are excluded; these confirmed amounts may omit other payments."
        else ""
    return listOf(headline, breakdown, note).filter { it.isNotBlank() }.joinToString("\n\n")
  }

  private fun moneyJson(totals: Map<String, BigDecimal>) =
      JSONObject(totals.mapValues { it.value.toPlainString() })

  private fun moneyText(totals: Map<String, BigDecimal>) =
      totals.entries
          .sortedBy { it.key }
          .joinToString("; ") { "${it.key} ${it.value.toPlainString()}" }
}
