package com.deryk.skarmetoo.ai.agentic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

/** Gemma selects the operation. Date arithmetic never reads or classifies the question. */
object AgenticTimePlan {
  private fun string() = JSONObject().put("type", "string")

  val schema: JSONObject
    get() {
      val entry =
          AgenticContracts.objectSchema(
              linkedMapOf(
                  "source_quote" to string(),
                  "mode" to
                      string()
                          .put(
                              "enum",
                              JSONArray(
                                  listOf(
                                      "rolling_days",
                                      "calendar_day",
                                      "calendar_week",
                                      "calendar_month",
                                      "explicit"))),
                  "offset" to JSONObject().put("type", "integer"),
                  "days" to JSONObject().put("type", "integer"),
                  "include_today" to JSONObject().put("type", "boolean"),
                  "numeric_date_order" to
                      string().put("enum", JSONArray(listOf("day_first", "month_first")))))
      return AgenticContracts.objectSchema(
          linkedMapOf(
              "entries" to JSONObject().put("type", "array").put("items", entry),
              "entry_count" to JSONObject().put("type", "integer")))
    }

  val executableSchema: JSONObject
    get() =
        schema.also { value ->
          val entry =
              value.getJSONObject("properties").getJSONObject("entries").getJSONObject("items")
          listOf("start_date", "end_date").forEach { key ->
            entry
                .getJSONObject("properties")
                .put(key, JSONObject().put("type", JSONArray(listOf("string", "null"))))
            entry.getJSONArray("required").put(key)
          }
        }

  val instruction =
      """
    Reread the original question and identify EVERY separate requested time entry.
    Decide the time operation first. Do not guess actual dates for a relative period.
    Each source_quote copies only its complete time phrase verbatim, without the action or subject.
    Comma/and/& joined dates or periods are separate entries; an explicit from/to or till range is one.
    Keep source order and repeats. entries=[] only when there is no time restriction.
    Select mode and parameters:
    rolling_days: a number of completed days before today. "past day" uses days=1;
      "past week"/"past seven days" uses days=7. Offset=0. include_today=false unless explicitly requested.
    calendar_day: today offset=0, yesterday/previous day offset=-1, day before yesterday offset=-2.
    calendar_week: last week/previous week offset=-1, this week offset=0, next week offset=1.
      These are complete Monday-Sunday weeks, distinct from rolling_days.
    calendar_month: last month offset=-1, this month offset=0, next month offset=1.
    explicit: a specific date, named month, numbered week of a month, or explicit date range.
      The next model turn resolves each explicit entry separately. No actual dates belong in this schema.
    This stage identifies time entries and operations only. Do not add start_date/end_date fields.
    For modes other than rolling_days use days=0 and include_today=false. For explicit use offset=0.
    Ambiguous numeric dates default to DD/MM/YY unless a named surrounding month disambiguates month_first.
    For nonnumeric phrases numeric_date_order is day_first. Count entries after listing them.
    Never answer amounts, invent evidence, or replace a rolling period with a calendar period.
    Interpret each entry independently: one rolling entry does not make adjacent calendar entries rolling.
    When auditing, do not merely copy the candidate. Compare EACH source_quote against the mode definitions.
    A phrase using last/previous week selects calendar_week even beside a past-day rolling entry.
    Generic examples: "notes past week" selects rolling_days, days=7, offset=0.
    "notes last week" selects calendar_week, days=0, offset=-1.
    "photos today and yesterday" has two calendar_day entries with offsets 0 and -1.
    "notes June 8, June 3, 6/4" has THREE explicit entries: source_quote "June 8", "June 3", "6/4".
    Never copy a whole comma-separated question into one source_quote. List each entry FIRST, then count.
    Return only the supplied JSON schema.
  """
          .trimIndent()

  fun prompt(question: String, now: JSONObject, candidate: JSONObject? = null): String {
    val clock =
        JSONObject().put("today", now.getString("today")).put("timezone", now.getString("timezone"))
    return "${if (candidate == null) "Identify all time operations." else "Independently reread the original and audit these model operations, correcting omissions or incorrect modes/parameters: $candidate"}\n" +
        "CLOCK: $clock\nSCHEMA: $schema\nORIGINAL QUESTION: ${JSONObject.quote(question)}"
  }

  fun parse(raw: String, question: String, today: LocalDate): JSONObject {
    val value = AgenticContracts.parse(raw)
    AgenticContracts.validate(value, schema)
    val entries = value.getJSONArray("entries")
    require(value.getInt("entry_count") == entries.length() && entries.length() <= 64) {
      "Count all listed entries exactly."
    }
    // Canonicalize unused fields without changing the model's selected operation.
    (0 until entries.length()).forEach { i ->
      val entry = entries.getJSONObject(i)
      val mode = entry.getString("mode")
      if (mode != "rolling_days") entry.put("days", 0).put("include_today", false)
      if (mode == "explicit" || mode == "rolling_days") entry.put("offset", 0)
    }
    var next = 0
    val spans = mutableListOf<IntRange>()
    (0 until entries.length()).forEach { i ->
      val entry = entries.getJSONObject(i)
      val quote = entry.getString("source_quote").trim()
      val position = question.indexOf(quote, next, ignoreCase = true)
      require(quote.isNotEmpty() && position >= next) {
        "Copy each distinct time fragment verbatim in original order, without action words."
      }
      next = position + quote.length
      spans += position until next
      val joined =
          Regex("(?i)\\b(?:and|or)\\b|[,;&]")
              .containsMatchIn(quote.replace(Regex(",\\s*\\d{4}(?!\\d)"), ""))
      require(!joined || Regex("(?i)\\bbetween\\b").containsMatchIn(quote)) {
        "Split separate comma/and joined requests; only between/and is one range."
      }
      if (entry.getString("mode") != "explicit") {
        resolved(JSONObject().put("entries", JSONArray().put(entry)).put("entry_count", 1), today)
      }
    }
    AgenticQueryCoverage.validateInventorySpans(question, spans)
    return value
  }

  fun resolved(plan: JSONObject, today: LocalDate): JSONObject {
    val entries = plan.getJSONArray("entries")
    val frames =
        (0 until entries.length()).map { index ->
          val entry = entries.getJSONObject(index)
          val offset = entry.getInt("offset")
          val days = entry.getInt("days")
          val includeToday = entry.getBoolean("include_today")
          val mode = entry.getString("mode")
          require(offset in -1200..1200) { "Use a bounded calendar offset." }
          require(mode == "rolling_days" || (days == 0 && !includeToday)) {
            "Only rolling_days uses days/include_today."
          }
          require(mode == "explicit" || (entry.isNull("start_date") && entry.isNull("end_date"))) {
            "Relative dates are executed by the tool; leave their date fields null."
          }
          val (start, end) =
              when (mode) {
                "rolling_days" -> {
                  require(offset == 0 && days in 1..3660) {
                    "rolling_days requires offset=0 and days between 1 and 3660."
                  }
                  val end = today.minusDays(if (includeToday) 0 else 1)
                  end.minusDays(days.toLong() - 1) to end
                }
                "calendar_day" -> today.plusDays(offset.toLong()).let { it to it }
                "calendar_week" ->
                    today
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks(offset.toLong())
                        .let { it to it.plusDays(6) }
                "calendar_month" ->
                    today.withDayOfMonth(1).plusMonths(offset.toLong()).let {
                      it to it.plusMonths(1).minusDays(1)
                    }
                "explicit" -> {
                  require(offset == 0) { "Explicit dates use offset=0." }
                  fun date(key: String): LocalDate? =
                      if (entry.isNull(key)) null
                      else {
                        val text = entry.getString(key)
                        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) {
                          "Explicit dates must be ISO YYYY-MM-DD."
                        }
                        LocalDate.parse(text)
                      }
                  date("start_date") to date("end_date")
                }
                else -> error("Unsupported time operation.")
              }
          require(start != null || end != null) {
            "Resolve the explicit entry '${entry.getString("source_quote")}' with at least one actual date."
          }
          require(start == null || end == null || !end.isBefore(start)) {
            "An inclusive end date cannot precede its start date."
          }
          JSONObject()
              .put("source_quote", entry.getString("source_quote"))
              .put(
                  "kind",
                  if (mode == "explicit" && (start == null || end == null || start != end)) "range"
                  else "period")
              .put("numeric_date_order", entry.getString("numeric_date_order"))
              .put("start_date", start?.toString() ?: JSONObject.NULL)
              .put("end_date", end?.toString() ?: JSONObject.NULL)
        }
    return JSONObject().put("frames", JSONArray(frames)).put("entry_count", frames.size)
  }
}
