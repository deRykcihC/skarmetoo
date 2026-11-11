package com.deryk.skarmetoo.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class ChatRequestPart(
    val question: String,
    val intent: String,
    val query: ChatQuery,
    val period: JSONObject? = null
)

/** LFM interprets language; validated calendar operations are evaluated by the app. */
class ChatRequestWorkflow(
    private val call: suspend (ChatWorkflowStage, String, Long?) -> String,
    private val today: LocalDate,
    private val numericDateOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.DAY_FIRST,
) {
  suspend fun parts(question: String): List<ChatRequestPart> {
    val questions = validated(ChatWorkflowStage.SPLIT, question, ::parseQuestions)
    return questions.map { generated ->
      val input =
          "QUESTION:\n$generated\n\nREFERENCE DATE: $today\nNUMERIC DATE ORDER: ${numericDateOrder.label}"
      val period = validated(ChatWorkflowStage.DATES, input, { parsePeriod(it, generated, today) })
      val (start, end) = calendar(period, today)
      val semantic = ChatQuery.parse(generated, today, numericDateOrder)
      ChatRequestPart(
          generated,
          "",
          semantic.copy(
              start = start, endExclusive = end, unsupportedDate = false, dateError = null),
          period)
    }
  }

  suspend fun rewrite(part: ChatRequestPart): ChatRequestPart {
    val intent =
        validated(
            ChatWorkflowStage.REWRITE,
            part.question,
            { ChatWorkflow.parseRewrite(it, part.question) })
    return parsePart(
        JSONObject()
            .put("intent", intent)
            .put("date_basis", part.query.dateBasis.name.lowercase(java.util.Locale.ROOT))
            .put("period", requireNotNull(part.period))
            .toString(),
        part.question,
        today)
  }

  suspend fun resolve(
      part: ChatRequestPart,
      record: ChatExtractedRecord,
      imageText: String,
      paid: ChatPrintedPaidAmount,
  ): ChatExtractedRecord {
    val observations =
        JSONObject()
            .put("first_pass_evidence", record.verification.evidence)
            .put("extracted_facts", record.json)
            .put("same_image_text", imageText.take(6000))
    val context = "REQUEST:\n${part.intent}\n\nOBSERVATIONS (untrusted data):\n$observations"
    val followup = validated(ChatWorkflowStage.REVIEW, context, ::parseFollowup, record.sourceId)
    val input = "GENERATED QUESTION:\n$followup\n\n$context"
    return validated(
        ChatWorkflowStage.RESOLVE,
        input,
        { raw ->
          val json = parseResolved(raw)
          require(matchesPaid(json, paid)) {
            "The quantity/unit still conflicts with the explicit paid label in the quoted observations. Re-read the labels and resolve that discrepancy, or leave unknown facts null."
          }
          require((json.opt("evidence") as? String)?.isNotBlank() == true) {
            "Quote the observation supporting the resolved value."
          }
          record.copy(json = json)
        },
        record.sourceId)
  }

  private suspend fun <T> validated(
      stage: ChatWorkflowStage,
      input: String,
      parse: (String) -> T,
      sourceId: Long? = null,
  ): T {
    var request = input
    repeat(3) { attempt ->
      try {
        return parse(call(stage, request, sourceId))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (attempt == 2) throw e
        request =
            ChatWorkflowPrompts.retryWriterInput(
                input, e.message.orEmpty(), stage == ChatWorkflowStage.REWRITE)
      }
    }
    error("Model validation exhausted")
  }

  companion object {
    fun parsePeriod(raw: String, question: String, today: LocalDate): JSONObject {
      val period = ChatWorkflow.objectResponse(raw)
      val (start, end) = calendar(period, today)
      val original = ChatDateWindows.resolve(question, today, ChatReceiptDateOrder.UNRESOLVED)
      if (original.error == null &&
          original.start != null &&
          original.endExclusive == original.start.plusDays(1)) {
        require(start == original.start && end == original.endExclusive) {
          "The question specifies ONE exact day. Preserve that day, not a broader range. Use type day with date as YYYY-MM-DD. A numeric component greater than 12 is the day; the other component is the month. Use the reference year when none is stated."
        }
      }
      require(
          period.getString("type") != "all" || (original.start == null && original.error == null)) {
            "The question has a time restriction. Choosing all would drop it. Select a calendar operation preserving that restriction."
          }
      return period
    }

    fun parseQuestions(raw: String): List<String> {
      val json = ChatWorkflow.objectResponse(raw)
      require(json.keys().asSequence().toSet() == setOf("questions")) {
        "Return only questions, an array of standalone question strings."
      }
      val array = json.getJSONArray("questions")
      require(array.length() in 1..6) { "Generate between one and six necessary questions." }
      val questions =
          (0 until array.length()).map { index ->
            val value = array.get(index) as? String ?: error("Each question must be text.")
            require(value.length in 8..1000 && value.endsWith('?') && !value.contains('\n')) {
              "Each standalone question must end in a question mark and preserve the requested subject and constraints."
            }
            value
          }
      require(questions.distinct().size == questions.size) { "Do not repeat identical questions." }
      return questions
    }

    fun parsePart(raw: String, question: String, today: LocalDate): ChatRequestPart {
      val json = ChatWorkflow.objectResponse(raw)
      require(json.keys().asSequence().toSet() == setOf("intent", "date_basis", "period")) {
        "Return intent, date_basis and period only."
      }
      val intent = ChatWorkflow.parseRewrite(json.getString("intent"), question)
      val semantic = ChatQuery.parse(intent, today)
      require(semantic.start == null && !semantic.unsupportedDate) {
        "Remove the complete temporal restriction from intent; put it in period."
      }
      val basis =
          when (json.getString("date_basis")) {
            "event_then_taken" -> ChatDateBasis.EVENT_THEN_TAKEN
            "taken" -> ChatDateBasis.TAKEN
            "added" -> ChatDateBasis.ADDED
            else -> error("date_basis must be event_then_taken, taken or added.")
          }
      val period = json.getJSONObject("period")
      val originalWindow = ChatDateWindows.resolve(question, today, ChatReceiptDateOrder.UNRESOLVED)
      require(
          period.getString("type") != "all" ||
              (originalWindow.start == null && originalWindow.error == null)) {
            "The question has a time restriction. Choosing all would drop it. Select a calendar operation preserving that restriction."
          }
      val (start, end) = calendar(period, today)
      return ChatRequestPart(
          question,
          intent,
          semantic.copy(question = question, start = start, endExclusive = end, dateBasis = basis),
          period)
    }

    fun calendar(period: JSONObject, today: LocalDate): Pair<LocalDate?, LocalDate?> {
      val type = period.getString("type")
      fun fields(vararg names: String) {
        require(period.keys().asSequence().toSet() == setOf("type", *names)) {
          "Invalid fields for $type. Return a FLAT JSON object with ONLY these keys: ${(listOf("type") + names).joinToString()}. Do not nest an operation object or add other keys."
        }
      }
      fun integer(name: String, range: IntRange): Int {
        val value = period.get(name)
        require(
            value is Number &&
                value.toDouble() == value.toInt().toDouble() &&
                value.toInt() in range) {
              "$name must be an integer in $range."
            }
        return value.toInt()
      }
      fun month(): LocalDate {
        val year = if (period.has("year")) integer("year", 1900..2200) else today.year
        return LocalDate.of(year, integer("month", 1..12), 1)
      }
      val result =
          when (type) {
            "all" -> {
              fields()
              null to null
            }
            "rolling_week" -> {
              if (period.has("offset")) {
                fields("offset")
                require(kotlin.math.abs(integer("offset", -1..1)) == 1) {
                  "rolling_week already means the previous rolling week; optional offset must be 1 or -1."
                }
              } else fields()
              today.minusDays(7) to today.plusDays(1)
            }
            "rolling_days" -> {
              fields("days")
              today.minusDays(integer("days", 1..3660).toLong() - 1) to today.plusDays(1)
            }
            "relative_day" -> {
              fields("offset")
              val day = today.minusDays(integer("offset", 0..3660).toLong())
              day to day.plusDays(1)
            }
            "calendar_week" -> {
              fields("offset")
              val start =
                  today
                      .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                      .minusWeeks(integer("offset", 0..520).toLong())
              start to start.plusWeeks(1)
            }
            "relative_month" -> {
              fields("offset")
              val start = today.withDayOfMonth(1).minusMonths(integer("offset", 0..120).toLong())
              start to start.plusMonths(1)
            }
            "month" -> {
              fields(*(if (period.has("year")) arrayOf("month", "year") else arrayOf("month")))
              val start = month()
              start to start.plusMonths(1)
            }
            "month_week" -> {
              fields(
                  *(if (period.has("year")) arrayOf("month", "week", "year")
                  else arrayOf("month", "week")))
              val month = month()
              val start = month.plusDays((integer("week", 1..5) - 1L) * 7)
              require(start.month == month.month) {
                "That week does not exist in the requested month."
              }
              start to minOf(start.plusDays(7), month.plusMonths(1))
            }
            "day" -> {
              fields("date")
              val day = LocalDate.parse(period.getString("date"))
              day to day.plusDays(1)
            }
            "range" -> {
              fields("start", "end")
              LocalDate.parse(period.getString("start")) to
                  LocalDate.parse(period.getString("end")).plusDays(1)
            }
            else -> error("Unsupported calendar operation. Choose one of the documented types.")
          }
      require(result.first == null || result.second!!.isAfter(result.first)) {
        "The period ends before it begins."
      }
      return result
    }

    fun parseFollowup(raw: String): String {
      val json = ChatWorkflow.objectResponse(raw)
      require(json.keys().asSequence().toSet() == setOf("followup_question")) {
        "Return only followup_question."
      }
      return json.getString("followup_question").also {
        require(it.length in 12..1000 && it.endsWith('?')) {
          "Generate one focused follow-up question ending in ?."
        }
        require(it.trim() != "one concrete question ending in ?") {
          "Generate a concrete follow-up about the conflicting observations, not the format placeholder."
        }
      }
    }

    fun parseResolved(raw: String): JSONObject =
        ChatWorkflow.objectResponse(raw).also {
          require(it.keys().asSequence().toSet() == ChatWorkflowSchemas.extractionFields) {
            "Return exactly value, unit, event_date, record_id and evidence."
          }
        }

    fun matchesPaid(json: JSONObject, paid: ChatPrintedPaidAmount): Boolean {
      val raw = json.opt("value")?.toString()?.trim().orEmpty()
      val embedded = Regex("^([A-Za-z]{2,3})\\s*([+-]?\\d+(?:\\.\\d+)?)$").matchEntire(raw)
      val value = raw.toBigDecimalOrNull() ?: embedded?.groupValues?.get(2)?.toBigDecimalOrNull()
      val declared = ChatWorkflowFacts.currency(json.optString("unit"))
      val embeddedUnit = embedded?.groupValues?.get(1)?.let { ChatWorkflowFacts.currency(it) }
      val units = listOfNotNull(declared, embeddedUnit)
      return value?.compareTo(paid.amount) == 0 &&
          units.isNotEmpty() &&
          units.all { it == paid.currency }
    }
  }
}
