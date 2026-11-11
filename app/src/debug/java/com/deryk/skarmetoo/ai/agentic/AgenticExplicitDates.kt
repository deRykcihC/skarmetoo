package com.deryk.skarmetoo.ai.agentic

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

object AgenticExplicitDates {
  val schema: JSONObject
    get() =
        AgenticContracts.objectSchema(
            linkedMapOf(
                "numeric_date_order" to
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(listOf("day_first", "month_first"))),
                "start_date" to JSONObject().put("type", JSONArray(listOf("string", "null"))),
                "end_date" to JSONObject().put("type", JSONArray(listOf("string", "null")))))

  val instruction =
      """
    Resolve ONE explicit time entry from the original question to actual ISO YYYY-MM-DD dates.
    start_date is inclusive; end_date is inclusive. A single day has identical start/end dates.
    A named month covers its first through final day. First week of a month is days 1-7,
    second 8-14, etc., capped at month end. A stated range includes its two endpoints.
    Only before/after open ranges may have one null boundary. Never return both dates null.
    Use the supplied phone year when the user omits a year. Preserve explicit years.
    Read the full original question to inherit a missing month/year and disambiguate numeric dates.
    A component greater than 12 is the day. Ambiguous numeric dates default to DD/MM/YY.
    If an explicitly named surrounding month uniquely matches the numeric month, use month_first.
    Use day_first for nonnumeric dates. Do not interpret other entries or answer spending amounts.
    Return only the JSON schema, with real date strings rather than copied phrases.
  """
          .trimIndent()

  fun prompt(original: String, quote: String, today: LocalDate): String =
      "PHONE TODAY: $today\nRESOLVE ONLY THIS ENTRY: ${JSONObject.quote(quote)}\n" +
          "ORIGINAL QUESTION FOR CONTEXT: ${JSONObject.quote(original)}\nSCHEMA: $schema"

  fun parse(raw: String, entry: JSONObject, original: String, today: LocalDate): JSONObject {
    val result = AgenticContracts.parse(raw)
    AgenticContracts.validate(result, schema)
    val resolvedEntry = JSONObject(entry.toString())
    listOf("numeric_date_order", "start_date", "end_date").forEach {
      resolvedEntry.put(it, result.get(it))
    }
    AgenticTimePlan.resolved(
        JSONObject().put("entries", JSONArray().put(resolvedEntry)).put("entry_count", 1), today)
    AgenticQueryCoverage.validateNumericOrder(
        original,
        entry.getString("source_quote"),
        result.getString("numeric_date_order") == "month_first")
    return resolvedEntry
  }
}
