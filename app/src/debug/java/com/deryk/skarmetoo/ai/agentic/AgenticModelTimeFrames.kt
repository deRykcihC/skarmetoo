package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

data class ModelTimeFrame(
    val sourceQuote: String,
    val kind: String,
    val numericOrder: ChatReceiptDateOrder,
    val start: LocalDate?,
    val endInclusive: LocalDate?,
    val daySelection: String = "all"
)

data class ModelTimeFrames(val frames: List<ModelTimeFrame>) {
  fun containsRequestedTime(text: String): Boolean =
      frames.any { frame ->
        val quote = frame.sourceQuote.trim().trimEnd('?', '.', '!').trim()
        quote.isNotEmpty() &&
            Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(quote)}(?![\\p{L}\\p{N}])")
                .containsMatchIn(text)
      }

  val inventory: RequestInventory
    get() =
        RequestInventory(
            frames.map { RequestedTimeEntry(it.sourceQuote, it.kind, it.numericOrder) })

  val filter: DateFilter
    get() =
        DateFilter(
            frames.map { it.sourceQuote },
            frames.map { it.numericOrder },
            frames.flatMap { frame ->
              if (frame.daySelection == "all")
                  listOf(ChatDateWindow(frame.start, frame.endInclusive?.plusDays(1)))
              else {
                val start = requireNotNull(frame.start)
                val end = requireNotNull(frame.endInclusive)
                require(ChronoUnit.DAYS.between(start, end) <= 36500) {
                  "Day selections require a bounded period."
                }
                val windows = mutableListOf<ChatDateWindow>()
                var cursor = start
                var runStart: LocalDate? = null
                while (!cursor.isAfter(end)) {
                  val weekend = cursor.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                  val selected = if (frame.daySelection == "weekend") weekend else !weekend
                  if (selected && runStart == null) runStart = cursor
                  if (!selected && runStart != null) {
                    windows += ChatDateWindow(runStart, cursor)
                    runStart = null
                  }
                  cursor = cursor.plusDays(1)
                }
                if (runStart != null) windows += ChatDateWindow(runStart, cursor)
                require(windows.isNotEmpty()) {
                  "The requested period has no selected ${frame.daySelection} days."
                }
                windows
              }
            })

  fun display(): List<String> =
      frames.map { frame ->
        val single = ModelTimeFrames(listOf(frame))
        val dates =
            single.filter.resolvedWindows.orEmpty().map { window ->
              single.inventory.display(listOf(window)).single().substringAfter(": ")
            }
        "${frame.sourceQuote}: ${dates.joinToString(", ")}"
      }

  fun json(): JSONObject =
      JSONObject()
          .put(
              "frames",
              JSONArray(
                  frames.map {
                    JSONObject()
                        .put("source_quote", it.sourceQuote)
                        .put("kind", it.kind)
                        .put(
                            "numeric_date_order",
                            if (it.numericOrder == ChatReceiptDateOrder.MONTH_FIRST) "month_first"
                            else "day_first")
                        .put("start_date", it.start?.toString() ?: JSONObject.NULL)
                        .put("end_date", it.endInclusive?.toString() ?: JSONObject.NULL)
                        .put("day_selection", it.daySelection)
                  }))
          .put("entry_count", frames.size)
}

/**
 * Gemma supplies explicit dates or structured temporal operations. Kotlin validates source
 * grounding and executes calendar arithmetic from those operations without interpreting the
 * original question.
 */
object AgenticModelTimeFrames {
  // Select a reference-capable transport format, never a date or an anchor target.
  fun usesPeriodReferences(question: String) = Regex("(?i)\\bthat\\b").containsMatchIn(question)

  fun planningSystem(question: String, base: String): String =
      if (usesPeriodReferences(question)) base else "$base\n$independentInstruction"

  fun schemaFor(question: String): JSONObject {
    if (usesPeriodReferences(question)) return planningSchema
    val schema = planningSchema
    val frame = schema.getJSONObject("properties").getJSONObject("frames").getJSONObject("items")
    frame.getJSONObject("properties").remove("period_id")
    val required = frame.getJSONArray("required")
    frame.put(
        "required",
        JSONArray(
            (0 until required.length())
                .map { required.getString(it) }
                .filter { it != "period_id" }))
    val rolling = frame.getJSONObject("properties").getJSONObject("rolling")
    rolling.getJSONObject("properties").remove("anchor_spec")
    rolling.getJSONObject("properties").put("anchor", string())
    rolling.put(
        "required", JSONArray(listOf("anchor", "direction", "duration_days", "include_anchor")))
    return schema
  }

  private val independentInstruction =
      """
    Interpret ALL time clauses in original source order. source_quote is only the exact contiguous time phrase.
    Separate AND/comma requests into distinct frames even if contiguous or overlapping. Do not repeat shared action words.
    CLOCK is current. Never calculate relative dates yourself. Return the supplied schema only, no money answers.
    temporal_mode selects single_day, rolling, calendar, or explicit. Set unused objects null.
    SINGLE_DAY uses relative={"anchor_reference":"today","anchor_offset_days":0,"selection":"single_day","count":1}.
    Today: today/0. Yesterday: yesterday/0. Day before yesterday: day_before_yesterday/0. N days ago: today/-N.
    These are single_day, never rolling spans of zero days, and never explicit mode with null dates.
    ROLLING uses {"anchor":"today","direction":"past","duration_days":N,"include_anchor":false}.
    Last/past/previous N days excludes today. These/current N days includes today (include_anchor=true).
    Duration counts included days, not an anchor offset. Relative only selects single days; spans use rolling.
    Calendar numbered month weeks use operation=week_of_month, year/month from question or clock, week=1..6 or -1 last, anchor_date=null.
    Weeks are Monday-Sunday clipped to the month. Whole month uses operation=month,week=0,anchor_date=null.
    Whole week of a date uses week_containing_date, anchor_date=ISO date, matching year/month,week=0; never clip this week.
    Other explicit dates/ranges use actual inclusive ISO start_date/end_date. Numeric order is day_first unless a named month disambiguates.
    day_selection is all, weekdays (Mon-Fri), or weekend (Sat-Sun), independently of period. Preserve it.
    Complete example "today and past four days":
    {"frames":[{"source_quote":"today","kind":"period","numeric_date_order":"day_first","start_date":null,"end_date":null,"day_selection":"all","calendar":null,"relative":{"anchor_reference":"today","anchor_offset_days":0,"selection":"single_day","count":1},"rolling":null,"temporal_mode":"single_day"},{"source_quote":"past four days","kind":"range","numeric_date_order":"day_first","start_date":null,"end_date":null,"day_selection":"all","calendar":null,"relative":null,"rolling":{"anchor":"today","direction":"past","duration_days":4,"include_anchor":false},"temporal_mode":"rolling"}],"entry_count":2}
    entry_count equals frames length. On audit check every clause, anchor, duration, direction and inclusion flag independently.
  """
          .trimIndent()

  fun parseQuestionOperations(raw: String, question: String, today: LocalDate): JSONObject {
    if (usesPeriodReferences(question)) return parsePlanningOperations(raw, question, today)
    AgenticContracts.validate(AgenticContracts.parse(raw), schemaFor(question))
    return parseOperations(raw, question, today)
  }

  private fun anchorSchema() =
      AgenticContracts.objectSchema(
          linkedMapOf(
              "type" to
                  JSONObject()
                      .put("type", "string")
                      .put(
                          "enum",
                          JSONArray(listOf("named_day", "explicit_date", "period_reference"))),
              "value" to JSONObject().put("type", "string")))

  private fun normalizeAnchor(frame: JSONObject, index: Int) {
    if (!frame.has("period_id")) frame.put("period_id", "period_${index + 1}")
    frame.optJSONObject("rolling")?.let {
      if (!it.has("anchor_spec")) it.put("anchor_spec", JSONObject.NULL)
      if (!it.has("anchor")) it.put("anchor", "")
    }
  }

  fun referenceClauses(operations: JSONObject): List<JSONObject> =
      (0 until operations.getJSONArray("frames").length())
          .map { operations.getJSONArray("frames").getJSONObject(it) }
          .filter {
            it.getString("temporal_mode") == "rolling" &&
                Regex("(?i)\\bthat\\b").containsMatchIn(it.getString("source_quote"))
          }

  val referenceAuditSchema: JSONObject
    get() =
        AgenticContracts.objectSchema(
            mapOf(
                "references" to
                    JSONObject()
                        .put("type", "array")
                        .put(
                            "items",
                            AgenticContracts.objectSchema(
                                linkedMapOf(
                                    "period_id" to string(), "anchor_spec" to anchorSchema())))))

  fun referenceAuditSchema(operations: JSONObject): JSONObject {
    val schema = referenceAuditSchema
    val clauses = referenceClauses(operations)
    val array = schema.getJSONObject("properties").getJSONObject("references")
    array.put("minItems", clauses.size).put("maxItems", clauses.size)
    val fields = array.getJSONObject("items").getJSONObject("properties")
    fields
        .getJSONObject("period_id")
        .put("enum", JSONArray(clauses.map { it.getString("period_id") }))
    val all = operations.getJSONArray("frames")
    val ids = (0 until all.length()).map { all.getJSONObject(it).getString("period_id") }
    val earlier = clauses.flatMap { ids.take(ids.indexOf(it.getString("period_id"))) }.distinct()
    val anchor = fields.getJSONObject("anchor_spec").getJSONObject("properties")
    anchor.getJSONObject("type").put("enum", JSONArray(listOf("period_reference")))
    anchor.getJSONObject("value").put("enum", JSONArray(earlier))
    return schema
  }

  fun referenceAuditPrompt(question: String, operations: JSONObject): String {
    val clauses =
        JSONArray(
            (0 until operations.getJSONArray("frames").length()).map {
              val frame = operations.getJSONArray("frames").getJSONObject(it)
              JSONObject()
                  .put("period_id", frame.getString("period_id"))
                  .put("source_quote", frame.getString("source_quote"))
            })
    return "Independently resolve references in the ORIGINAL QUESTION. These are clause IDs and literal quotes only: $clauses. " +
        "Review ONLY the clauses with IDs ${referenceClauses(operations).map { it.getString("period_id") }}. For each clause containing THAT, identify which earlier clause it refers to. " +
        "The record's period_id is the clause CONTAINING THAT, NOT its target. anchor_spec.value is the EARLIER TARGET period_id. Do not reverse them. " +
        "Return period_id and anchor_spec={type:period_reference,value:the earlier period_id}. Do not default to today. Never invent dates or durations. " +
        "Original question: ${JSONObject.quote(question)}. Schema: ${referenceAuditSchema(operations)}"
  }

  fun parseReferenceAudit(raw: String, operations: JSONObject): JSONObject {
    val value = AgenticContracts.parse(raw)
    AgenticContracts.validate(value, referenceAuditSchema)
    val expected = referenceClauses(operations)
    val refs = value.getJSONArray("references")
    require(refs.length() == expected.size) { "Audit every reference clause exactly once." }
    val all = operations.getJSONArray("frames")
    val ids = (0 until all.length()).map { all.getJSONObject(it).getString("period_id") }
    for (i in expected.indices) {
      val ref = refs.getJSONObject(i)
      require(ref.getString("period_id") == expected[i].getString("period_id")) {
        "This reference record's period_id must be ${expected[i].getString("period_id")}, the clause containing THAT. anchor_spec.value names the earlier target. Do not reverse the two IDs."
      }
      val spec = ref.getJSONObject("anchor_spec")
      require(
          spec.getString("type") == "period_reference" &&
              ids.indexOf(spec.getString("value")) in
                  0 until ids.indexOf(ref.getString("period_id"))) {
            "THAT must reference an existing earlier period_id, never a named day, itself or a later period."
          }
    }
    return value
  }

  fun validateReferenceAgreement(operations: JSONObject, review: JSONObject) {
    val frames = operations.getJSONArray("frames")
    for (i in 0 until review.getJSONArray("references").length()) {
      val ref = review.getJSONArray("references").getJSONObject(i)
      val frame =
          (0 until frames.length())
              .map { frames.getJSONObject(it) }
              .single { it.getString("period_id") == ref.getString("period_id") }
      val expected = ref.getJSONObject("anchor_spec")
      val actual = frame.optJSONObject("rolling")?.optJSONObject("anchor_spec")
      require(
          actual?.optString("type") == expected.getString("type") &&
              actual?.optString("value") == expected.getString("value")) {
            "Independent reference review says ${ref.getString("period_id")} refers to ${expected.getString("value")}. Re-read the original and regenerate its anchor_spec as a period_reference; retain duration and direction."
          }
    }
  }

  val operationInstruction =
      """
    Interpret every time request from ORIGINAL QUESTION into the supplied JSON schema, in source order.
    CLOCK is fresh for this request. Do not calculate relative ISO dates yourself. Never answer money amounts.
    Copy each exact time phrase into source_quote, excluding action/subject words. entry_count equals frames length.
    Separate independent time clauses joined by AND, OR, or commas into separate frames, even if their dates overlap
    or could be combined into one continuous span. Never add durations together. A from/to or between/and range is one clause.
    A named day plus a duration is TWO requests: first the single day, then the rolling span with its original duration.
    For every frame use numeric_date_order=day_first unless a named month unambiguously disambiguates it.
    day_selection is all, weekdays (Mon-Fri), or weekend (Sat-Sun). Preserve this independently of the period.
    temporal_mode chooses exactly one operation: rolling, single_day, calendar, or explicit.
    It is authoritative: only the object selected by temporal_mode is executed. Set unused objects and dates null.
    Each frame has period_id="period_1", "period_2", etc in source order.
    ROLLING is any consecutive N-day span: {"anchor_spec":{"type":"named_day","value":"today"},"direction":"past","duration_days":N,"include_anchor":false}.
    Last/past/previous N days means completed days before today: direction=past, include_anchor=false.
    These N days / the current N days means today plus preceding days: direction=past, include_anchor=true.
    The day count belongs ONLY to duration_days. Rolling has no offset; never subtract the duration from its anchor.
    rolling.anchor_spec has type named_day, explicit_date, or period_reference. value is respectively today/yesterday/day_before_yesterday,
    an ISO date, or an earlier period_id. A period_reference anchors to that period's ENDING day.
    N days before/after THAT MUST use type=period_reference and the earlier period_id, direction=past/future, include_anchor=false.
    Never reinterpret "that" as the unrelated named_day today. Choose the earlier period it refers to.
    For N days before a named anchor, use that anchor directly, direction=past, include_anchor=false.
    RELATIVE is only a SINGLE day: anchor_reference as above, anchor_offset_days signed shift, selection=single_day,count=1.
    Today alone: temporal_mode=single_day, relative={"anchor_reference":"today","anchor_offset_days":0,"selection":"single_day","count":1},rolling=null,calendar=null,start_date=null,end_date=null.
    Named relative days (today/yesterday/day before yesterday) are NEVER explicit mode with empty dates.
    Yesterday alone: yesterday/0. Day before yesterday alone: day_before_yesterday/0. N days ago: today/-N.
    Calendar weeks always start Monday. Numbered month weeks are clipped to the month, with partial first week counted as week 1.
    CALENDAR numbered week: operation=week_of_month, year/month from question or CLOCK year, week=1..6 or -1 for last, anchor_date=null.
    Whole month: operation=month, week=0, anchor_date=null, year/month selected by question.
    Whole week of a DATE: operation=week_containing_date, anchor_date=that ISO date, year/month matching it, week=0.
    The containing week is full Monday-Sunday, never clipped to the month. Last week means the previous complete Monday-Sunday week.
    Other explicit dates/ranges may use actual ISO start_date/end_date, inclusive; open bounds may be null.
    Example "past three days" -> rolling={"anchor_spec":{"type":"named_day","value":"today"},"direction":"past","duration_days":3,"include_anchor":false}.
    Example "these four days" -> rolling={"anchor_spec":{"type":"named_day","value":"today"},"direction":"past","duration_days":4,"include_anchor":true}.
    Independent entries never inherit a prior offset; only explicit references such as "that" refer back.
    During audit compare semantic fields with the original: every entry, anchor, duration, inclusion of today, direction, day selection.
    Example "day before yesterday and three days before that": period_1 is the named single day;
    period_2 rolling.anchor_spec={"type":"period_reference","value":"period_1"},direction=past,duration_days=3,include_anchor=false.
    period_id belongs directly on each FRAME, beside source_quote, NEVER inside rolling or anchor_spec.
    Complete format example "today and past four days":
    {"frames":[{"period_id":"period_1","source_quote":"today","kind":"period","numeric_date_order":"day_first","start_date":null,"end_date":null,"day_selection":"all","calendar":null,"relative":{"anchor_reference":"today","anchor_offset_days":0,"selection":"single_day","count":1},"rolling":null,"temporal_mode":"single_day"},{"period_id":"period_2","source_quote":"past four days","kind":"range","numeric_date_order":"day_first","start_date":null,"end_date":null,"day_selection":"all","calendar":null,"relative":null,"rolling":{"anchor_spec":{"type":"named_day","value":"today"},"direction":"past","duration_days":4,"include_anchor":false},"temporal_mode":"rolling"}],"entry_count":2}
    Independent-clause example "today and past four days": period_1 has source_quote="today",temporal_mode=single_day,
    relative={"anchor_reference":"today","anchor_offset_days":0,"selection":"single_day","count":1},rolling=null.
    period_2 has source_quote="past four days",temporal_mode=rolling,relative=null,
    rolling={"anchor_spec":{"type":"named_day","value":"today"},"direction":"past","duration_days":4,"include_anchor":false}.
    source_quote MUST be a contiguous literal substring of the ORIGINAL. Never prepend shared action/subject words to later clauses.
    Correct only interpretation errors; do not replace the semantic operations with calculated dates. Return JSON only.
  """
          .trimIndent()

  val operationSchema: JSONObject
    get() =
        reasonedSchema.also { root ->
          val entry =
              root.getJSONObject("properties").getJSONObject("frames").getJSONObject("items")
          entry.getJSONObject("properties").remove("reference_date")
          entry
              .getJSONObject("properties")
              .put(
                  "calendar",
                  AgenticContracts.objectSchema(
                      linkedMapOf(
                          "operation" to
                              string()
                                  .put(
                                      "enum",
                                      JSONArray(
                                          listOf(
                                              "week_of_month", "month", "week_containing_date"))),
                          "anchor_date" to date(),
                          "year" to JSONObject().put("type", "integer"),
                          "month" to JSONObject().put("type", "integer"),
                          "week" to JSONObject().put("type", "integer"))))
          entry
              .getJSONObject("properties")
              .getJSONObject("calendar")
              .put("type", JSONArray(listOf("object", "null")))
          entry.getJSONArray("required").put("calendar")
          entry
              .getJSONObject("properties")
              .put(
                  "relative",
                  AgenticContracts.objectSchema(
                          linkedMapOf(
                              "anchor_reference" to string(),
                              "anchor_offset_days" to JSONObject().put("type", "integer"),
                              "selection" to
                                  string()
                                      .put(
                                          "enum",
                                          JSONArray(
                                              listOf(
                                                  "single_day",
                                                  "preceding_days",
                                                  "following_days"))),
                              "count" to JSONObject().put("type", "integer")))
                      .put("type", JSONArray(listOf("object", "null"))))
          entry.getJSONArray("required").put("relative")
          entry
              .getJSONObject("properties")
              .put(
                  "rolling",
                  AgenticContracts.objectSchema(
                          linkedMapOf(
                              "anchor" to string(),
                              "direction" to
                                  string().put("enum", JSONArray(listOf("past", "future"))),
                              "duration_days" to
                                  JSONObject()
                                      .put("type", "integer")
                                      .put("minimum", 1)
                                      .put("maximum", 3660),
                              "include_anchor" to JSONObject().put("type", "boolean")))
                      .put("type", JSONArray(listOf("object", "null"))))
          entry.getJSONArray("required").put("rolling")
          entry
              .getJSONObject("properties")
              .getJSONObject("rolling")
              .getJSONObject("properties")
              .put("anchor_spec", anchorSchema().put("type", JSONArray(listOf("object", "null"))))
          entry
              .getJSONObject("properties")
              .getJSONObject("rolling")
              .getJSONArray("required")
              .put("anchor_spec")
          entry
              .getJSONObject("properties")
              .put("period_id", string().put("enum", JSONArray((1..64).map { "period_$it" })))
          entry.getJSONArray("required").put("period_id")
          entry
              .getJSONObject("properties")
              .put(
                  "temporal_mode",
                  string()
                      .put(
                          "enum",
                          JSONArray(listOf("rolling", "single_day", "calendar", "explicit"))))
          entry.getJSONArray("required").put("temporal_mode")
          val required = entry.getJSONArray("required")
          entry.put(
              "required",
              JSONArray(
                  (0 until required.length())
                      .map { required.getString(it) }
                      .filter { it != "reference_date" }))
        }

  val planningSchema: JSONObject
    get() =
        operationSchema.also { root ->
          val entry =
              root.getJSONObject("properties").getJSONObject("frames").getJSONObject("items")
          val legacy =
              setOf("reference_quote", "start_days_from_reference", "end_days_from_reference")
          legacy.forEach(entry.getJSONObject("properties")::remove)
          val rolling = entry.getJSONObject("properties").getJSONObject("rolling")
          rolling.getJSONObject("properties").remove("anchor")
          rolling.getJSONObject("properties").getJSONObject("anchor_spec").put("type", "object")
          rolling.put(
              "required",
              JSONArray(listOf("anchor_spec", "direction", "duration_days", "include_anchor")))
          val relative =
              entry
                  .getJSONObject("properties")
                  .getJSONObject("relative")
                  .getJSONObject("properties")
          relative.getJSONObject("selection").put("enum", JSONArray(listOf("single_day")))
          relative.getJSONObject("count").put("enum", JSONArray(listOf(1)))
          val required = entry.getJSONArray("required")
          entry.put(
              "required",
              JSONArray(
                  (0 until required.length())
                      .map { required.getString(it) }
                      .filterNot { it in legacy }))
        }

  fun operationPrompt(question: String, now: JSONObject, candidate: JSONObject? = null): String {
    val planningCandidate = candidate?.let { JSONObject(it.toString()) }
    planningCandidate?.getJSONArray("frames")?.let { frames ->
      for (i in 0 until frames.length()) {
        val frame = frames.getJSONObject(i)
        if (usesPeriodReferences(question)) frame.optJSONObject("rolling")?.remove("anchor")
        else {
          frame.remove("period_id")
          frame.optJSONObject("rolling")?.remove("anchor_spec")
        }
        listOf("reference_quote", "start_days_from_reference", "end_days_from_reference")
            .forEach(frame::remove)
      }
    }
    return "${if (usesPeriodReferences(question)) operationInstruction else independentInstruction}\nCLOCK: $now\nSCHEMA: ${schemaFor(question)}\n" +
        (planningCandidate?.let { "Audit this candidate against the question: $it\n" } ?: "") +
        "ORIGINAL QUESTION: ${JSONObject.quote(question)}\n" +
        "APPLICATION POLICY (overrides conversational defaults): LAST/PAST/PREVIOUS N days excludes TODAY: rolling anchored to today, direction=past, duration_days=N, include_anchor=false. THESE/CURRENT N days includes TODAY: same fields but include_anchor=true. Use only anchor fields supplied by this schema. " +
        "For example last five days has include_anchor=false; these five days has include_anchor=true. Never copy an incorrect inclusion flag from an audit candidate. " +
        "Read ORIGINAL QUESTION again and choose the inclusion flag from its meaning under this policy. Split independent AND/comma time clauses into separate frames; never merge a named day with another span or increase its duration to absorb the day. Spans use rolling; relative is single-day only. Return JSON only."
  }

  fun parsePlanningOperations(raw: String, original: String, today: LocalDate): JSONObject {
    val value = AgenticContracts.parse(raw)
    val frames = value.getJSONArray("frames")
    for (i in 0 until frames.length()) {
      val frame = frames.getJSONObject(i)
      val rolling = frame.optJSONObject("rolling")
      // Transport normalization only: retain the exact model-produced ID, never infer a reference.
      if (!frame.has("period_id") && rolling?.has("period_id") == true)
          frame.put("period_id", rolling.remove("period_id"))
    }
    AgenticContracts.validate(value, planningSchema)
    return parseOperations(value.toString(), original, today)
  }

  fun parseOperations(raw: String, original: String, today: LocalDate): JSONObject {
    val value = AgenticContracts.parse(raw)
    for (i in 0 until value.getJSONArray("frames").length()) {
      val frame = value.getJSONArray("frames").getJSONObject(i)
      normalizeAnchor(frame, i)
      if (!frame.has("day_selection")) frame.put("day_selection", "all")
      if (!frame.has("reference_quote")) frame.put("reference_quote", "")
      if (!frame.has("start_days_from_reference"))
          frame.put("start_days_from_reference", JSONObject.NULL)
      if (!frame.has("end_days_from_reference"))
          frame.put("end_days_from_reference", JSONObject.NULL)
      if (!frame.has("calendar")) frame.put("calendar", JSONObject.NULL)
      frame.optJSONObject("calendar")?.let {
        if (!it.has("anchor_date")) it.put("anchor_date", JSONObject.NULL)
      }
      if (!frame.has("relative")) frame.put("relative", JSONObject.NULL)
      if (!frame.has("rolling")) frame.put("rolling", JSONObject.NULL)
      if (!frame.has("temporal_mode"))
          frame.put(
              "temporal_mode",
              when {
                !frame.isNull("rolling") -> "rolling"
                !frame.isNull("calendar") -> "calendar"
                !frame.isNull("relative") -> "single_day"
                else -> "explicit"
              })
    }
    AgenticContracts.validate(value, operationSchema)
    for (i in 0 until value.getJSONArray("frames").length()) {
      val frame = value.getJSONArray("frames").getJSONObject(i)
      require(frame.getString("period_id") == "period_${i + 1}") {
        "Use sequential period_id values in original source order."
      }
      if (!frame.isNull("rolling")) {
        val spec = frame.getJSONObject("rolling").optJSONObject("anchor_spec")
        if (spec != null &&
            Regex("(?i)\\bthat\\b").containsMatchIn(frame.getString("source_quote"))) {
          require(spec.getString("type") == "period_reference") {
            "This clause refers to THAT: select anchor_spec.type=period_reference and the earlier period_id it refers to, not a named day."
          }
        }
      }
      val requestedSelection =
          when {
            Regex("(?i)\\bweekends?\\b").containsMatchIn(frame.getString("source_quote")) ->
                "weekend"
            Regex("(?i)\\bweekdays?\\b").containsMatchIn(frame.getString("source_quote")) ->
                "weekdays"
            else -> null
          }
      require(
          requestedSelection == null || frame.getString("day_selection") == requestedSelection) {
            "Preserve the source_quote's $requestedSelection restriction in day_selection. Keep the enclosing period separate."
          }
      if (!frame.isNull("relative")) {
        val relative = frame.getJSONObject("relative")
        if (relative.getString("selection") != "single_day") {
          val named =
              Regex("(?i)\\b(?:before|after)\\s+(day\\s+before\\s+yesterday|yesterday|today)\\b")
                  .find(frame.getString("source_quote"))
                  ?.groupValues
                  ?.get(1)
                  ?.lowercase()
                  ?.replace(Regex("\\s+"), "_")
          require(named == null || relative.getString("anchor_reference") == named) {
            "The source_quote explicitly names '$named' as the before/after anchor. Set relative.anchor_reference to that named anchor with anchor_offset_days=0; preserve the requested selection/count. Do not shift from today instead."
          }
          val standalonePast =
              Regex("(?i)^\\s*(?:the\\s+)?past\\s+(?:\\d+|[a-z]+)\\s*days?\\s*(?:spending)?\\s*$")
                  .matches(frame.getString("source_quote"))
          require(
              !standalonePast ||
                  (relative.getString("anchor_reference") == "today" &&
                      relative.getInt("anchor_offset_days") == 0 &&
                      relative.getString("selection") == "preceding_days")) {
                "A standalone past-days span is anchored to today with anchor_offset_days=0 and selection=preceding_days. Its count belongs to the selection, never the anchor offset. Re-read this entry independently of the previous entry."
              }
        }
      }
    }
    parse(executeOperations(value, today).toString(), original)
    return value
  }

  /** Executes only model-produced references and parameters; never reads the user's wording. */
  fun executeOperations(operations: JSONObject, today: LocalDate): JSONObject {
    val result = JSONObject(operations.toString())
    for (i in 0 until result.getJSONArray("frames").length()) {
      val frame = result.getJSONArray("frames").getJSONObject(i)
      normalizeAnchor(frame, i)
      if (!frame.has("day_selection")) frame.put("day_selection", "all")
      if (!frame.has("reference_quote")) frame.put("reference_quote", "")
      if (!frame.has("start_days_from_reference"))
          frame.put("start_days_from_reference", JSONObject.NULL)
      if (!frame.has("end_days_from_reference"))
          frame.put("end_days_from_reference", JSONObject.NULL)
      if (!frame.has("calendar")) frame.put("calendar", JSONObject.NULL)
      frame.optJSONObject("calendar")?.let {
        if (!it.has("anchor_date")) it.put("anchor_date", JSONObject.NULL)
      }
      if (!frame.has("relative")) frame.put("relative", JSONObject.NULL)
      if (!frame.has("rolling")) frame.put("rolling", JSONObject.NULL)
      if (!frame.has("temporal_mode"))
          frame.put(
              "temporal_mode",
              when {
                !frame.isNull("rolling") -> "rolling"
                !frame.isNull("calendar") -> "calendar"
                !frame.isNull("relative") -> "single_day"
                else -> "explicit"
              })
    }
    AgenticContracts.validate(result, operationSchema)
    val frames = result.getJSONArray("frames")
    for (i in 0 until frames.length()) {
      val frame = frames.getJSONObject(i)
      val reference = frame.getString("reference_quote")
      val mode = frame.getString("temporal_mode")
      require(mode == "explicit" || !frame.isNull(if (mode == "single_day") "relative" else mode)) {
        if (mode == "single_day")
            "temporal_mode=single_day requires a NON-NULL relative object: anchor_reference is the named day (today/yesterday/day_before_yesterday), anchor_offset_days=0, selection=single_day,count=1. Fill that relative object; do not leave it null."
        else "The object selected by temporal_mode=$mode must be non-null."
      }
      if (mode == "rolling") {
        val span = frame.getJSONObject("rolling")
        val spec = span.optJSONObject("anchor_spec")
        val anchorName = spec?.getString("value") ?: span.getString("anchor")
        if (spec?.getString("type") == "named_day")
            require(anchorName in setOf("today", "yesterday", "day_before_yesterday")) {
              "named_day must be today, yesterday or day_before_yesterday."
            }
        val anchor =
            if (spec?.getString("type") == "explicit_date") LocalDate.parse(anchorName)
            else if (spec?.getString("type") == "period_reference") {
              val previous =
                  (0 until i)
                      .map { frames.getJSONObject(it) }
                      .singleOrNull { it.getString("period_id") == anchorName }
              require(previous != null && !previous.isNull("end_date")) {
                "period_reference must name a bounded earlier period_id; self/forward/unknown references are invalid."
              }
              LocalDate.parse(previous.getString("end_date"))
            } else
                when (anchorName) {
                  "today" -> today
                  "yesterday" -> today.minusDays(1)
                  "day_before_yesterday" -> today.minusDays(2)
                  else -> {
                    val previous =
                        (0 until i)
                            .map { frames.getJSONObject(it) }
                            .lastOrNull { it.getString("source_quote") == anchorName }
                    require(previous != null && !previous.isNull("end_date")) {
                      "Rolling anchor must be a named day or an earlier entry's exact source_quote."
                    }
                    LocalDate.parse(previous.getString("end_date"))
                  }
                }
        val duration = span.getInt("duration_days")
        require(duration in 1..3660) {
          "Rolling duration_days counts included days and must be 1..3660, never zero. A named single day uses temporal_mode=single_day and relative with anchor_offset_days=0, selection=single_day,count=1; rolling=null. Do not represent today as a zero-day rolling span."
        }
        val inclusive = span.getBoolean("include_anchor")
        val start: LocalDate
        val end: LocalDate
        if (span.getString("direction") == "past") {
          end = if (inclusive) anchor else anchor.minusDays(1)
          start = end.minusDays(duration.toLong() - 1)
        } else {
          start = if (inclusive) anchor else anchor.plusDays(1)
          end = start.plusDays(duration.toLong() - 1)
        }
        frame
            .put("start_date", start.toString())
            .put("end_date", end.toString())
            .put("kind", if (duration == 1) "period" else "range")
      } else if (mode == "calendar") {
        // Calendar is the model-selected operation; relative fields are unused here.
        val calendar = frame.getJSONObject("calendar")
        if (calendar.getString("operation") == "week_containing_date") {
          require(!calendar.isNull("anchor_date")) {
            "week_containing_date requires an actual ISO anchor_date, extracted from the requested date."
          }
          val anchorText = calendar.getString("anchor_date")
          require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(anchorText)) {
            "anchor_date must be YYYY-MM-DD."
          }
          val anchor = LocalDate.parse(anchorText)
          require(
              calendar.getInt("year") == anchor.year &&
                  calendar.getInt("month") == anchor.monthValue &&
                  calendar.getInt("week") == 0) {
                "week_containing_date uses the anchor's year/month and week=0."
              }
          val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
          frame
              .put("start_date", monday.toString())
              .put("end_date", monday.plusDays(6).toString())
              .put("kind", "range")
        } else {
          require(calendar.isNull("anchor_date")) { "Only week_containing_date uses anchor_date." }
          val first = LocalDate.of(calendar.getInt("year"), calendar.getInt("month"), 1)
          val last = first.plusMonths(1).minusDays(1)
          val week = calendar.getInt("week")
          val start =
              if (calendar.getString("operation") == "month") {
                require(week == 0) { "Whole month uses week=0." }
                first
              } else {
                require(week == -1 || week in 1..6) {
                  "week_of_month requires week=-1 or 1..6 for an ordinal month week. If the question asks for the whole week OF A DATE, change operation to week_containing_date, extract that date as ISO anchor_date, use its year/month and week=0. Do not invent a month-week ordinal for a date."
                }
                if (week == -1) last.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                else
                    first
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks((week - 1).toLong())
              }
          val end =
              if (calendar.getString("operation") == "month") last
              else start.plusDays(6).coerceAtMost(last)
          require(!start.isAfter(last)) { "Requested numbered week does not exist in that month." }
          frame
              .put("start_date", start.coerceAtLeast(first).toString())
              .put("end_date", end.toString())
              .put("kind", "range")
        }
      } else if (mode == "single_day") {
        val operation = frame.getJSONObject("relative")
        val referenceName = operation.getString("anchor_reference")
        val base =
            if (referenceName == "today") today
            else if (referenceName == "yesterday") today.minusDays(1)
            else if (referenceName == "day_before_yesterday") today.minusDays(2)
            else {
              val previous =
                  (0 until i)
                      .map { frames.getJSONObject(it) }
                      .lastOrNull { it.getString("source_quote") == referenceName }
              require(previous != null && !previous.isNull("end_date")) {
                "Anchor must name today or an earlier entry's exact source_quote."
              }
              LocalDate.parse(previous.getString("end_date"))
            }
        val shift = operation.getLong("anchor_offset_days")
        val count = operation.getInt("count")
        require(referenceName !in setOf("yesterday", "day_before_yesterday") || shift == 0L) {
          "Named yesterday/day_before_yesterday anchors already resolve that day; use anchor_offset_days=0, then apply selection/count."
        }
        require(shift in -36500..36500 && count in 1..3660) {
          "Use bounded anchor shift and positive day count."
        }
        val anchor = base.plusDays(shift)
        val (start, end) =
            when (operation.getString("selection")) {
              "single_day" -> {
                require(count == 1) { "single_day requires count=1." }
                anchor to anchor
              }
              "preceding_days" -> anchor.minusDays(count.toLong()) to anchor.minusDays(1)
              "following_days" -> anchor.plusDays(1) to anchor.plusDays(count.toLong())
              else -> error("Unsupported relative selection.")
            }
        frame
            .put("start_date", start.toString())
            .put("end_date", end.toString())
            .put("kind", if (start == end) "period" else "range")
      } else if (reference.isNotEmpty()) {
        require(frame.isNull("start_date") && frame.isNull("end_date")) {
          "Relative operations leave both date fields null; the calendar tool supplies dates."
        }
        val anchor =
            if (reference == "today") today
            else {
              val previous =
                  (0 until i)
                      .map { frames.getJSONObject(it) }
                      .lastOrNull { it.getString("source_quote") == reference }
              require(previous != null && !previous.isNull("end_date")) {
                "Reference an earlier entry with a bounded end date, using its exact source_quote."
              }
              LocalDate.parse(previous.getString("end_date"))
            }
        val first = frame.getLong("start_days_from_reference")
        val last = frame.getLong("end_days_from_reference")
        require(first in -36500..36500 && last in -36500..36500 && first <= last) {
          "Use bounded offsets in start/end order."
        }
        require(frame.getString("kind") != "period" || first == last) {
          "A single day must use identical start/end offsets. Reread that phrase and correct BOTH offsets, not just one."
        }
        frame
            .put("start_date", anchor.plusDays(first).toString())
            .put("end_date", anchor.plusDays(last).toString())
      } else
          require(
              frame.isNull("start_days_from_reference") &&
                  frame.isNull("end_days_from_reference")) {
                "Explicit dates have null offsets."
              }
      listOf("reference_quote", "start_days_from_reference", "end_days_from_reference")
          .forEach(frame::remove)
      frame.remove("calendar")
      frame.remove("relative")
      frame.remove("rolling")
      frame.remove("temporal_mode")
    }
    for (i in 0 until frames.length()) frames.getJSONObject(i).remove("period_id")
    return result
  }

  val instruction =
      """
    Resolve EVERY requested time fragment into actual ISO YYYY-MM-DD start_date/end_date.
    Read the WHOLE original question, using CLOCK.today as today. Do the calendar reasoning yourself.
    source_quote copies only the exact time fragment; preserve order and separate requests.
    Resolve references such as "that", "then", "before that", and "after that" against the
    preceding referenced date or period, not automatically against today. Return the completed
    dates, never the expression, an operation, or an unresolved reference in date fields.
    "all N days before that" is an inclusive N-day range ending the day BEFORE the referenced
    date; its start is N days before the reference. Exclude the referenced date unless requested.
    Compute START=reference minus N days, END=reference minus 1 day. Never use minus (N-1) for START.
    Count backwards individually: reference-1, reference-2, ... reference-N. Include ALL N dates.
    Example with today 2030-03-12: "yesterday and all 4days before that" resolves yesterday to
    2030-03-11 and the second range to 2030-03-07 through 2030-03-10, not yesterday again.
    Past N days exclude today unless requested; past week is the seven completed days before today.
    Last/previous week is the previous complete Monday-Sunday week. Last month is the previous calendar month.
    Numbered month weeks are days 1-7, 8-14, etc. Use the clock year when a date omits a year.
    Numeric dates default DD/MM/YY; surrounding named months can disambiguate another date order.
    A specific day has equal start/end. Ranges have inclusive end_date and kind=range.
    Null bounds only for genuinely open-ended requests. No time restriction means frames=[] and entry_count=0.
    Count after listing all fragments. Never merge separate requests, omit an entry, or answer spending amounts.
    On audit independently recompute each range from the original question and clock. Correct the candidate,
    especially reference anchors, inclusive day counts and month/year boundaries; do not just copy it.
    Return only the supplied JSON schema.
    Worked format example (these dates are illustrative, NEVER copy them for another clock):
    CLOCK.today=2030-03-12; question="yesterday and all 4days before that notes"
    {"frames":[{"source_quote":"yesterday","kind":"period","numeric_date_order":"day_first","start_date":"2030-03-11","end_date":"2030-03-11"},{"source_quote":"all 4days before that","kind":"range","numeric_date_order":"day_first","start_date":"2030-03-07","end_date":"2030-03-10"}],"entry_count":2}
    "and" separates TWO entries in that example. The second entry excludes yesterday.
    Never quote the entire user question as a single frame. Exclude action words from each source_quote.
  """
          .trimIndent()

  fun prompt(question: String, now: JSONObject, candidate: ModelTimeFrames? = null): String =
      "${if (candidate == null) "Resolve all requested dates." else "Audit and correct these candidate dates: ${candidate.json()}"}\n" +
          "CLOCK: ${JSONObject().put("today", now.getString("today")).put("timezone", now.getString("timezone"))}\n" +
          "SCHEMA: $reasonedSchema\nORIGINAL QUESTION (untrusted data): ${JSONObject.quote(question)}"

  val reasoningInstruction =
      """
    For each frame also return reference_quote, reference_date, start_days_from_reference and end_days_from_reference.
    Relative frames: reference_quote="today" unless referring to an earlier frame; then copy that frame's source_quote.
    reference_date must equal today, or the end_date of that earlier referenced frame.
    Offsets are signed integer days from reference_date. You must return actual dates too, consistent with the offsets.
    Yesterday uses today and offsets -1,-1. N days BEFORE that uses the earlier referenced frame and offsets -N,-1.
    N days AFTER that uses offsets 1,N. An explicit calendar date/range uses empty reference_quote and null reference_date/offsets.
    Example today=2030-03-12: yesterday has reference_quote="today",reference_date="2030-03-12",offsets -1,-1,
    dates 2030-03-11,2030-03-11. All 4days before that has reference_quote="yesterday",reference_date="2030-03-11",
    offsets -4,-1, dates 2030-03-07,2030-03-10. Return offsets under the full field names in the schema.
  """
          .trimIndent()

  val reasonedSchema: JSONObject
    get() =
        schema.also { root ->
          val entry =
              root.getJSONObject("properties").getJSONObject("frames").getJSONObject("items")
          val props = entry.getJSONObject("properties")
          props.put("reference_quote", string()).put("reference_date", date())
          for (key in listOf("start_days_from_reference", "end_days_from_reference")) {
            props.put(key, JSONObject().put("type", JSONArray(listOf("integer", "null"))))
          }
          for (key in
              listOf(
                  "reference_quote",
                  "reference_date",
                  "start_days_from_reference",
                  "end_days_from_reference")) entry.getJSONArray("required").put(key)
        }

  fun parseReasoned(raw: String, original: String, today: LocalDate): ModelTimeFrames {
    val value = AgenticContracts.parse(raw)
    for (i in 0 until value.getJSONArray("frames").length()) {
      val frame = value.getJSONArray("frames").getJSONObject(i)
      if (!frame.has("day_selection")) frame.put("day_selection", "all")
    }
    AgenticContracts.validate(value, reasonedSchema)
    val entries = value.getJSONArray("frames")
    for (i in 0 until entries.length()) {
      val frame = entries.getJSONObject(i)
      val reference = frame.getString("reference_quote")
      if (reference.isNotEmpty()) {
        val anchor =
            if (reference == "today") today
            else {
              val previous =
                  (0 until i)
                      .map { entries.getJSONObject(it) }
                      .singleOrNull { it.getString("source_quote") == reference }
              require(previous != null) {
                "Reference an earlier frame's exact source_quote or today."
              }
              LocalDate.parse(previous.getString("end_date"))
            }
        require(frame.getString("reference_date") == anchor.toString()) {
          "reference_date must equal the stated reference frame's end_date (or today's clock)."
        }
        for ((dateKey, offsetKey) in
            listOf(
                "start_date" to "start_days_from_reference",
                "end_date" to "end_days_from_reference")) {
          require(!frame.isNull(offsetKey) && frame.getLong(offsetKey) in -36500..36500) {
            "Relative frames require bounded integer offsets."
          }
          val computed = anchor.plusDays(frame.getLong(offsetKey)).toString()
          require(frame.getString(dateKey) == computed) {
            "Calendar arithmetic check for '${frame.getString("source_quote")}': your reference_date=$anchor plus your $offsetKey=${frame.getLong(offsetKey)} equals $computed. Correct $dateKey to that result, retaining your reference and offsets. Return the complete JSON."
          }
        }
      } else
          require(
              listOf("reference_date", "start_days_from_reference", "end_days_from_reference")
                  .all(frame::isNull)) {
                "Explicit dates have no relative reference or offsets."
              }
      listOf(
              "reference_quote",
              "reference_date",
              "start_days_from_reference",
              "end_days_from_reference")
          .forEach(frame::remove)
    }
    return parse(value.toString(), original)
  }

  private fun string() = JSONObject().put("type", "string")

  private fun date() = JSONObject().put("type", JSONArray(listOf("string", "null")))

  val schema: JSONObject
    get() {
      val frame =
          AgenticContracts.objectSchema(
              linkedMapOf(
                  "source_quote" to string(),
                  "kind" to string().put("enum", JSONArray(listOf("period", "range"))),
                  "numeric_date_order" to
                      string().put("enum", JSONArray(listOf("day_first", "month_first"))),
                  "start_date" to date(),
                  "end_date" to date(),
                  "day_selection" to
                      string().put("enum", JSONArray(listOf("all", "weekdays", "weekend")))))
      return AgenticContracts.objectSchema(
          linkedMapOf(
              "frames" to JSONObject().put("type", "array").put("items", frame),
              "entry_count" to JSONObject().put("type", "integer")))
    }

  fun parse(raw: String, original: String): ModelTimeFrames {
    val value = AgenticContracts.parse(raw)
    for (i in 0 until value.getJSONArray("frames").length()) {
      val frame = value.getJSONArray("frames").getJSONObject(i)
      if (!frame.has("day_selection")) frame.put("day_selection", "all")
    }
    AgenticContracts.validate(value, schema)
    val values = value.getJSONArray("frames")
    require(values.length() <= 64 && value.getInt("entry_count") == values.length()) {
      "entry_count must equal the number of complete time frames."
    }
    var next = 0
    val spans = mutableListOf<IntRange>()
    fun date(value: JSONObject, key: String): LocalDate? {
      if (value.isNull(key)) return null
      val rawDate = value.getString(key)
      require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(rawDate)) {
        "$key must be an actual ISO date, not a time phrase."
      }
      return LocalDate.parse(rawDate)
    }
    val frames =
        (0 until values.length()).map { i ->
          val frame = values.getJSONObject(i)
          val quote = frame.getString("source_quote").trim()
          val position = original.indexOf(quote, next, ignoreCase = true)
          require(quote.isNotEmpty() && position >= next) {
            "The source_quote '$quote' is not a distinct literal time fragment at this position in the original. Copy only its contiguous time words in source order. Do not prepend or repeat shared action/subject words from earlier clauses."
          }
          next = position + quote.length
          spans += position until next
          val kind = frame.getString("kind")
          val fragment = quote.replace(Regex(",\\s*\\d{4}(?!\\d)"), "")
          val joined = Regex("(?i)\\b(?:and|or)\\b|[,;&]").containsMatchIn(fragment)
          require(
              !joined ||
                  (kind == "range" && Regex("(?i)\\bbetween\\b").containsMatchIn(fragment))) {
                "Separate comma/and/or joined requests into individual frames; only between/and forms one range."
              }
          val monthFirst = frame.getString("numeric_date_order") == "month_first"
          AgenticQueryCoverage.validateNumericOrder(original, quote, monthFirst)
          val start = date(frame, "start_date")
          val end = date(frame, "end_date")
          require(start != null || end != null) {
            "Return at least one actual date for '$quote'. Explicit mode needs ISO dates. For a named relative day use temporal_mode=single_day and relative={anchor_reference:the named day,anchor_offset_days:0,selection:single_day,count:1}, leaving the dates null for the tool. Today uses anchor_reference=today; yesterday uses yesterday; day before yesterday uses day_before_yesterday."
          }
          require(start == null || end == null || !end.isBefore(start)) {
            "end_date is inclusive and must not precede start_date for '$quote'."
          }
          val statedDays =
              Regex("(?i)\\b(\\d+)\\s*days?\\b").find(quote)?.groupValues?.get(1)?.toLongOrNull()
          if (kind == "range" && start != null && end != null && statedDays != null) {
            require(ChronoUnit.DAYS.between(start, end) + 1 == statedDays) {
              "The range '$quote' must contain exactly $statedDays days INCLUDING both bounds. Recompute the dates from its reference; do not return fewer days."
            }
          }
          ModelTimeFrame(
              quote,
              kind,
              if (monthFirst) ChatReceiptDateOrder.MONTH_FIRST else ChatReceiptDateOrder.DAY_FIRST,
              start,
              end,
              frame.getString("day_selection"))
        }
    // Known source literals must not disappear. Unknown language is left to Gemma and its audit.
    // Unlike the legacy phrase resolver, this guard never rejects a phrase merely for being
    // unfamiliar.
    AgenticQueryCoverage.validateInventorySpans(original, spans)
    return ModelTimeFrames(frames)
  }
}
