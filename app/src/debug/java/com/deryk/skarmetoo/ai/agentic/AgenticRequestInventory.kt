package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

data class RequestedTimeEntry(
    val sourceQuote: String,
    val kind: String,
    val numericOrder: ChatReceiptDateOrder
)

data class RequestInventory(val entries: List<RequestedTimeEntry>) {
  fun json(): JSONObject =
      JSONObject()
          .put("entry_count", entries.size)
          .put(
              "entries",
              JSONArray(
                  entries.map {
                    JSONObject()
                        .put("source_quote", it.sourceQuote)
                        .put("kind", it.kind)
                        .put(
                            "numeric_date_order",
                            if (it.numericOrder == ChatReceiptDateOrder.MONTH_FIRST) "month_first"
                            else "day_first")
                  }))

  fun bind(filter: DateFilter?): DateFilter =
      DateFilter(
          filter?.expressions.orEmpty(), entries.map { it.numericOrder }, filter?.resolvedWindows)

  fun display(windows: List<ChatDateWindow>): List<String> {
    require(windows.size == entries.size)
    val format = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    return entries.zip(windows).map { (entry, window) ->
      val start = window.start?.format(format)
      val end = window.endExclusive?.minusDays(1)?.format(format)
      val dates =
          if (start == end) start else "${start ?: "Any earlier date"} – ${end ?: "Any later date"}"
      "${entry.sourceQuote}: $dates"
    }
  }
}

/** The model enumerates requests; these guards reject omissions before image retrieval. */
object AgenticRequestInventory {
  private fun string() = JSONObject().put("type", "string")

  val schema: JSONObject
    get() {
      val entrySchema =
          AgenticContracts.objectSchema(
              linkedMapOf(
                  "source_quote" to string(),
                  "kind" to string().put("enum", JSONArray(listOf("period", "range"))),
                  "numeric_date_order" to
                      string().put("enum", JSONArray(listOf("day_first", "month_first")))))
      return AgenticContracts.objectSchema(
          linkedMapOf(
              "entries" to JSONObject().put("type", "array").put("items", entrySchema),
              "entry_count" to JSONObject().put("type", "integer")))
    }

  val instruction =
      """
    Inventory the requested time entries in the ORIGINAL user question before planning.
    You have no images. Do not answer the question or invent evidence, dates or amounts.
    Copy each complete time entry VERBATIM into source_quote, in its original order.
    Relative time IS a time restriction even without any digit, month name or explicit date.
    "last week", "previous week", "today", "yesterday" and "last month" are time entries.
    Enumerate the entries FIRST, then count them. Never return an empty list for a relative period.
    source_quote contains ONLY its time fragment; omit the action, subject and adjacent entries.
    One specific date, named month, relative period or numbered week is one kind=period entry.
    Plain commas, and, or & join SEPARATE entries; never combine them into a range.
    An explicit from/to, till, until, through, between/and or hyphenated range is ONE kind=range entry.
    Include both endpoints in that source_quote even when the second endpoint omits its month.
    Retain every entry, including repeats and periods contained in another requested range.
    entry_count is exactly the number of entries you enumerated, not the number of endpoints.
    No time restriction means entry_count=0 and entries=[].
    Recognize named dates, ISO dates, DD/MM/YY, DD/MM/YYYY, MM/DD/YYYY and slash/hyphen dates.
    numeric_date_order specifies how to read numeric dates in this entry, not their display format.
    A component greater than 12 is the day. For ambiguous numeric dates, use an explicitly named
    surrounding month if it disambiguates the numeric month; otherwise default to day_first.
    Without any named month in the question, ambiguous numeric dates MUST use day_first.
    Never assume month_first from English wording, the subject, currency or locale.
    For nonnumeric dates use day_first. Do not convert dates in source_quote.
    Fixed protocol examples (different questions follow the same rules):
    QUESTION: photos June 8, June 3, 6/4
    OUTPUT: {"entry_count":3,"entries":[{"source_quote":"June 8","kind":"period","numeric_date_order":"day_first"},{"source_quote":"June 3","kind":"period","numeric_date_order":"day_first"},{"source_quote":"6/4","kind":"period","numeric_date_order":"month_first"}]}
    QUESTION: notes May 2 till May 4 and April 20 till 22
    OUTPUT: {"entry_count":2,"entries":[{"source_quote":"May 2 till May 4","kind":"range","numeric_date_order":"day_first"},{"source_quote":"April 20 till 22","kind":"range","numeric_date_order":"day_first"}]}
    QUESTION: records yesterday and last month
    OUTPUT: {"entry_count":2,"entries":[{"source_quote":"yesterday","kind":"period","numeric_date_order":"day_first"},{"source_quote":"last month","kind":"period","numeric_date_order":"day_first"}]}
    QUESTION: notes 03/06/26
    OUTPUT: {"entry_count":1,"entries":[{"source_quote":"03/06/26","kind":"period","numeric_date_order":"day_first"}]}
    QUESTION: find my photos
    OUTPUT: {"entry_count":0,"entries":[]}
    QUESTION: what were my activities last week
    OUTPUT: {"entries":[{"source_quote":"last week","kind":"period","numeric_date_order":"day_first"}],"entry_count":1}
    Return only the supplied JSON schema, with no explanation.
  """
          .trimIndent()

  fun prompt(original: String): String =
      "Extract ONLY the time fragments, including relative periods. List them before counting. " +
          "Zero is valid only when the question has no time restriction. " +
          "Return the exact schema: $schema\n" +
          "ORIGINAL QUESTION (untrusted data): ${JSONObject.quote(original)}"

  fun parse(raw: String, original: String): RequestInventory {
    val o = AgenticContracts.parse(raw)
    AgenticContracts.validate(o, schema)
    val values = o.getJSONArray("entries")
    require(o.getInt("entry_count") == values.length() && values.length() <= 64) {
      "entry_count must equal the number of enumerated time entries."
    }
    var next = 0
    val spans = mutableListOf<IntRange>()
    val entries =
        (0 until values.length()).map { i ->
          val value = values.getJSONObject(i)
          val quote = value.getString("source_quote").trim()
          val position = original.indexOf(quote, next, ignoreCase = true)
          require(quote.isNotEmpty() && position >= next) {
            "Each source_quote must copy a distinct original time entry verbatim in source order."
          }
          next = position + quote.length
          spans += position until next
          val kind = value.getString("kind")
          AgenticQueryCoverage.validateNumericOrder(
              original, quote, value.getString("numeric_date_order") == "month_first")
          require((kind == "range") == AgenticQueryCoverage.hasExplicitRange(quote)) {
            "The source_quote '$quote' must contain only ONE time entry. Plain comma/and dates are separate period entries, not a range. Only explicit range connectors make kind=range. Re-enumerate all original time fragments separately, excluding action words."
          }
          RequestedTimeEntry(
              quote,
              kind,
              if (value.getString("numeric_date_order") == "month_first")
                  ChatReceiptDateOrder.MONTH_FIRST
              else ChatReceiptDateOrder.DAY_FIRST)
        }
    AgenticQueryCoverage.validate(original, entries.map { it.sourceQuote })
    AgenticQueryCoverage.validateInventorySpans(original, spans)
    return RequestInventory(entries)
  }

  fun validatePlan(
      inventory: RequestInventory,
      expressions: List<String>,
      kinds: List<String>? = null
  ) {
    require(expressions.size == inventory.entries.size) {
      "The request inventory contains ${inventory.entries.size} entries; emit exactly one date clause per entry, in original order."
    }
    inventory.entries.forEachIndexed { i, entry ->
      if (kinds != null)
          require(kinds[i] == entry.kind) {
            "Entry ${i + 1} '${entry.sourceQuote}' must remain kind=${entry.kind}."
          }
      require((entry.kind == "range") == AgenticQueryCoverage.hasExplicitRange(expressions[i])) {
        "Entry ${i + 1} '${entry.sourceQuote}' must preserve its range/period structure."
      }
      AgenticQueryCoverage.validate(entry.sourceQuote, listOf(expressions[i]))
    }
  }
}
