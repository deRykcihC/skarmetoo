package com.deryk.skarmetoo.chat

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.json.JSONTokener

enum class ChatWorkflowStage(val label: String, val tokenLimit: Int) {
  SPLIT("Separating the requested parts", 256),
  DATES("Interpreting each requested period", 320),
  REWRITE("Understanding the request", 96),
  PLAN("Preparing the image checks", 384),
  VERIFY("Checking image relevance", 512),
  EXTRACT("Reading the image", 640),
  REVIEW("Generating a follow-up check", 192),
  RESOLVE("Resolving conflicting readings", 384),
  ANSWER("Writing the answer", 256),
}

data class ChatWorkflowStep(
    val stage: ChatWorkflowStage,
    val prompt: String,
    val sourceId: Long? = null,
    val systemPrompt: String = "",
    val response: String = "",
    val cached: Boolean = false,
    val elapsedMs: Long = 0,
    val working: Boolean = true,
    val error: String = "",
    val model: String = "",
    val responseSchema: String = "",
    val dateEvidence: String = "",
    val amountEvidence: String = "",
)

data class ChatWorkflowPlan(
    val originalQuestion: String,
    val verificationPrompt: String,
    val extraction: ChatExtractionPrompt,
)

data class ChatExtractionPrompt(val prompt: String, val format: JSONObject)

data class ChatExtractionInstruction(val prompt: String, val fields: JSONObject) {
  val readerPrompt: String
    get() = "$prompt\n\nFIELD DEFINITIONS:\n$fields"
}

enum class ChatDecision {
  YES,
  NO,
  MAYBE
}

data class ChatVerification(val decision: ChatDecision, val evidence: String)

data class ChatExtractedRecord(
    val sourceId: Long,
    val verification: ChatVerification,
    val json: JSONObject,
)

data class ChatInspection(
    val records: List<ChatExtractedRecord>,
    val rejected: Int,
    val failed: Int,
    val extractionPrompt: String?,
)

/** Generated instructions pass forward unchanged; distinct planning errors get bounded retries. */
class ChatWorkflow(
    private val call: suspend (ChatWorkflowStage, String, Long?) -> String,
    private val checkActive: suspend () -> Unit = {},
) {
  suspend fun planIntent(question: String, intent: String): ChatWorkflowPlan =
      writeValidated(ChatWorkflowStage.PLAN, intent) { raw -> parsePlan(raw, intent) }
          .copy(originalQuestion = question)

  suspend fun plan(question: String): ChatWorkflowPlan {
    val intent =
        writeValidated(ChatWorkflowStage.REWRITE, question) { raw -> parseRewrite(raw, question) }
    return writeValidated(ChatWorkflowStage.PLAN, intent) { raw -> parsePlan(raw, intent) }
        .copy(originalQuestion = question)
  }

  suspend fun inspect(plan: ChatWorkflowPlan, sourceIds: List<Long>): ChatInspection {
    val records = mutableListOf<ChatExtractedRecord>()
    var rejected = 0
    var failed = 0
    val extraction = plan.extraction
    val fields = extraction.format.keys().asSequence().toSet()
    var usedExtraction = false
    for (sourceId in sourceIds) {
      checkActive()
      val verification =
          try {
            parseVerification(
                call(
                    ChatWorkflowStage.VERIFY,
                    ChatWorkflowPrompts.input(ChatWorkflowPrompts.verify, plan.verificationPrompt),
                    sourceId))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            failed++
            continue
          }
      if (verification.decision == ChatDecision.NO) {
        rejected++
        continue
      }

      // The complete model-written task already exists; no text-model switch inside the batch.
      usedExtraction = true
      try {
        val raw =
            call(
                ChatWorkflowStage.EXTRACT,
                ChatWorkflowPrompts.extractionInput(
                    extraction.prompt, extraction.format, verification.evidence),
                sourceId)
        val json = objectResponse(raw)
        require(json.keys().asSequence().toSet() == fields) {
          "LFM changed the generated extraction fields."
        }
        records += ChatExtractedRecord(sourceId, verification, json)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed++
      }
    }
    return ChatInspection(records, rejected, failed, extraction.prompt.takeIf { usedExtraction })
  }

  private suspend fun <T> writeValidated(
      stage: ChatWorkflowStage,
      prompt: String,
      parse: (String) -> T,
  ): T {
    var request = prompt
    val attempts = if (stage == ChatWorkflowStage.PLAN) 3 else 2
    val errors = mutableSetOf<String>()
    repeat(attempts) { attempt ->
      checkActive()
      try {
        return parse(call(stage, request, null))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (attempt == attempts - 1 || !errors.add(e.message.orEmpty())) throw e
        val error =
            e.message.orEmpty() +
                if (stage == ChatWorkflowStage.PLAN)
                    "\nBoth questions must omit all dates, month names, weekdays and relative time windows. Ask about evidence and the actual requested fact for ONE image only. Replace every placeholder and retain the five null response slots."
                else ""
        request =
            ChatWorkflowPrompts.retryWriterInput(prompt, error, stage == ChatWorkflowStage.REWRITE)
      }
    }
    error("Writer retry exhausted")
  }

  companion object {
    fun parseRewrite(raw: String, question: String): String {
      val text = raw.trim().removeSurrounding("\"").trim()
      require(
          text.length in 8..2000 &&
              !text.startsWith('{') &&
              !text.startsWith('[') &&
              !text.contains('\n')) {
            "Return only the rewritten request as one plain sentence. Do not answer or output JSON."
          }
      val now = LocalDate.of(2000, 1, 1)
      val rewritten = ChatQuery.parse(text, now, ChatReceiptDateOrder.UNRESOLVED)
      require(rewritten.start == null && !rewritten.unsupportedDate) {
        "Remove the entire date/time restriction, including every day, month name, year and relative period. Return only the original action, subject and requested information as a request without a time restriction."
      }
      val window = ChatDateWindows.resolve(question, now, ChatReceiptDateOrder.UNRESOLVED)
      val withoutDates =
          question
              .toCharArray()
              .also { chars -> window.spans.forEach { span -> for (i in span) chars[i] = ' ' } }
              .concatToString()
      fun numbers(value: String) =
          Regex("""\b\d+(?:\.\d+)?\b""")
              .findAll(value)
              .map { it.value.toBigDecimal().stripTrailingZeros() }
              .toSet()
      require(numbers(withoutDates) == numbers(text)) {
        "Keep non-temporal quantities or thresholds from the original request, but remove numbers belonging to its date/time restriction. Never invent an answer or number."
      }
      val filler =
          setOf(
              "can",
              "could",
              "would",
              "please",
              "tell",
              "give",
              "get",
              "that",
              "use",
              "using",
              "with",
              "which",
              "actual",
              "amount",
              "amounts",
              "quantity",
              "information",
              "details",
              "payment",
              "payments",
              "purchase",
              "purchases",
              "transaction",
              "transactions",
              "record",
              "records",
              "expenditure",
              "expenditures",
              "look",
              "looking",
              "search",
              "requested",
              "question",
              "user",
              "own",
              "has",
              "had")
      val original = ChatQuery.parse(question, now, ChatReceiptDateOrder.UNRESOLVED)
      require(
          original.terms - filler == rewritten.terms - filler &&
              original.spending == rewritten.spending) {
            "Keep the original action and non-temporal subject. Remove only date/time words; do not add a subject, drop a subject, or answer the request."
          }
      rejectWriterEcho(text, ChatWorkflowPrompts.rewrite)
      return text
    }

    fun parsePlan(raw: String, question: String): ChatWorkflowPlan {
      val json = objectResponse(raw)
      require(
          json.keys().asSequence().toSet() ==
              setOf("verification_question", "extraction_question", "response_format")) {
            "Return verification_question, extraction_question and response_format in one JSON object."
          }
      rejectInventedPlanningNumbers(json.toString(), question)
      fun readerQuestion(key: String): String {
        val text = json.opt(key) as? String
        require(text != null && text.length in 20..2000 && text.trim().endsWith('?')) {
          "$key must be a concrete question for one image, not an answer or empty template."
        }
        require(
            !Regex("<[^>]+>").containsMatchIn(text) &&
                !Regex("\\{[^}]+\\}").containsMatchIn(text) &&
                !Regex("\\b(value|unit|event_date|record_id|evidence)\\s*[:=]\\s*null\\b")
                    .containsMatchIn(text)) {
              "Replace all question placeholders using the user's question. Write concrete image-reading questions; never output {TARGET}, {FACT} or an empty template."
            }
        rejectWriterEcho(text, ChatWorkflowPrompts.plan)
        val timeWindow =
            ChatDateWindows.resolve(text, LocalDate.of(2000, 1, 1), ChatReceiptDateOrder.UNRESOLVED)
        require(timeWindow.start == null && timeWindow.error == null) {
          "Remove all dates, month names, weekdays and relative time windows from both image questions. Ask only what ONE record must prove and which actual fact to read. The app handles the user's time period; the image reader must not filter dates or aggregate records. Keep the original subject and requested fact. Return the two corrected questions and the five null response slots."
        }
        return text
      }
      val verification = readerQuestion("verification_question")
      require(
          Regex("^(Does|Do|Is|Are|Can|Has|Have|Did|Was|Were)\\b", RegexOption.IGNORE_CASE)
              .containsMatchIn(verification)) {
            "verification_question must ask yes/no about evidence in ONE image, not ask for an amount or aggregate answer."
          }
      val extraction = readerQuestion("extraction_question")
      val format = json.getJSONObject("response_format")
      require(templateFields(format) == ChatWorkflowSchemas.extractionFields) {
        "Use exactly value, unit, event_date, record_id and evidence with null values in response_format."
      }
      return ChatWorkflowPlan(question, verification, ChatExtractionPrompt(extraction, format))
    }

    fun parseVerificationInstruction(raw: String, question: String? = null): String {
      val json = objectResponse(raw)
      require(
          json.keys().asSequence().toSet() == setOf("next_prompt", "yes_if", "no_if", "maybe_if")) {
            "Return next_prompt, yes_if, no_if and maybe_if strings describing this question's image relevance rules."
          }
      val prompt = json.opt("next_prompt") as? String
      require(prompt != null && prompt.startsWith("VERIFY:") && prompt.length <= 6000) {
        "LFM did not generate a valid image verification prompt. See its response below."
      }
      rejectWriterEcho(prompt, ChatWorkflowPrompts.generate)
      rejectWriterEcho(prompt, ChatWorkflowPrompts.plan)
      val rules = JSONObject()
      for ((key, decision) in listOf("yes_if" to "YES", "no_if" to "NO", "maybe_if" to "MAYBE")) {
        val rule = json.opt(key) as? String
        require(rule != null && rule.length in 15..1200) {
          "$key must contain concrete visible evidence conditions."
        }
        rejectWriterEcho(rule, ChatWorkflowPrompts.generate)
        rules.put(decision, rule)
      }
      val result = "$prompt\n\nDECISION RULES:\n$rules"
      if (question != null) rejectInventedPlanningNumbers(result, question)
      return result
    }

    fun rejectInventedPlanningNumbers(instructions: String, question: String) {
      val numbers = Regex("\\b\\d+(?:\\.\\d+)?\\b")
      val allowed =
          numbers.findAll(question).map { it.value.toBigDecimal().stripTrailingZeros() }.toSet()
      val invented =
          numbers
              .findAll(instructions)
              .map { it.value.toBigDecimal().stripTrailingZeros() }
              .filter { it !in allowed }
              .toSet()
      require(invented.isEmpty()) {
        "Do not invent example numbers or dates that the question did not supply. Write conditional evidence rules, not descriptions of imagined images."
      }
    }

    private fun rejectWriterEcho(prompt: String, task: String) {
      val copied =
          task
              .split(Regex("[.!]\\s+"))
              .map { it.trim() }
              .filter { it.length >= 35 }
              .filter { prompt.contains(it, ignoreCase = true) }
              .sumOf { it.length }
      require(copied < prompt.length / 2) {
        "Write a question-specific image task, not a copy of the prompt-writing instructions."
      }
    }

    fun validateRequestedFields(question: String, fields: JSONObject) {
      if (!Regex(
              "\\b(how\\s+(much|many)|total|sum|amount|count|number\\s+of)\\b",
              RegexOption.IGNORE_CASE)
          .containsMatchIn(question))
          return
      val meanings =
          fields.keys().asSequence().associateWith {
            "${it.replace('_', ' ')} ${fields.optString(it)}"
          }
      val value =
          meanings
              .filter { (name, text) ->
                name !in setOf("event_date", "record_id", "evidence") &&
                    Regex(
                            "\\b(value|quantity|amount|total|count|number|paid|spent|cost)\\b",
                            RegexOption.IGNORE_CASE)
                        .containsMatchIn(text)
              }
              .keys
      val unit =
          meanings
              .filter { (_, text) ->
                Regex("\\b(unit|currency)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
              }
              .keys
      require(value.any { v -> unit.any { it != v } }) {
        "The question requests a quantity. Define the requested numeric value and its unit as separate fields, with reading instructions."
      }
    }

    fun objectResponse(raw: String): JSONObject {
      val text =
          raw.trim().let {
            if (it.startsWith("```"))
                it.removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()
            else it
          }
      require(text.startsWith('{') && text.endsWith('}')) { "LFM returned a non-JSON response." }
      val parser = JSONTokener(text)
      val result = parser.nextValue() as? JSONObject ?: error("LFM must return one JSON object.")
      require(parser.nextClean() == '\u0000') { "LFM returned extra text after JSON." }
      return result
    }

    fun parseVerification(raw: String): ChatVerification {
      val json = objectResponse(raw)
      require(json.keys().asSequence().toSet() == setOf("decision", "evidence")) {
        "Invalid verification fields."
      }
      val decision = ChatDecision.valueOf(json.getString("decision"))
      val evidence = json.opt("evidence") as? String ?: error("Evidence must be text.")
      require(evidence.isNotBlank()) { "LFM did not provide visible evidence." }
      return ChatVerification(decision, evidence)
    }

    fun parseExtractionPrompt(raw: String): ChatExtractionPrompt {
      val written = objectResponse(raw)
      require(written.keys().asSequence().toSet() == setOf("next_prompt", "response_format")) {
        "LFM did not return the extraction prompt and response format."
      }
      val prompt =
          written.opt("next_prompt") as? String
              ?: error("The generated extraction prompt must be text.")
      validateExtractionPrompt(prompt)
      rejectWriterEcho(prompt, ChatWorkflowPrompts.writeExtraction)
      rejectWriterEcho(prompt, ChatWorkflowPrompts.plan)
      val template =
          written.opt("response_format") as? JSONObject
              ?: error("LFM did not generate a JSON response format.")
      return ChatExtractionPrompt(prompt, parseExtractionFormat(template.toString(), prompt))
    }

    fun parseExtractionInstruction(raw: String): ChatExtractionInstruction {
      val written = objectResponse(raw)
      require(written.keys().asSequence().toSet() == setOf("next_prompt", "fields")) {
        "Return next_prompt and a fields object containing explicit field reading instructions."
      }
      val prompt =
          written.opt("next_prompt") as? String
              ?: error("The generated extraction prompt must be text.")
      validateExtractionPrompt(prompt)
      rejectWriterEcho(prompt, ChatWorkflowPrompts.writeExtraction)
      rejectWriterEcho(prompt, ChatWorkflowPrompts.plan)
      val fields =
          written.opt("fields") as? JSONObject
              ?: throw IllegalArgumentException(
                  "fields must be an object of field names and reading instructions.")
      val keys = fields.keys().asSequence().toSet()
      require(keys == ChatWorkflowSchemas.extractionFields) {
        "Define reading instructions for value, unit, event_date, record_id and evidence."
      }
      require(
          keys.all {
            Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)*").matches(it) &&
                it !in setOf("image", "prompt", "next_prompt", "fields") &&
                (fields.opt(it) as? String)?.trim()?.length in 12..600
          }) {
            "Each snake_case field needs a reading instruction; image/prompt fields and null templates are invalid."
          }
      return ChatExtractionInstruction(prompt, fields)
    }

    private fun validateExtractionPrompt(prompt: String) {
      require(prompt.startsWith("EXTRACT:") && prompt.length <= 6000) {
        "LFM did not generate an EXTRACT prompt."
      }
      require(prompt.removePrefix("EXTRACT:").trim().length >= 40) {
        "LFM omitted the extraction instructions or field definitions. See its response below."
      }
      require(
          !Regex("\\b\\w+\\s*=\\s*null\\b").containsMatchIn(prompt) &&
              !prompt.removePrefix("EXTRACT:").trim().startsWith("{")) {
            "LFM wrote an empty template instead of extraction instructions. See its response below."
          }
    }

    fun parseExtractionFormat(raw: String, prompt: String): JSONObject {
      val template = objectResponse(raw)
      val fields = templateFields(template)
      if (prompt.contains("\n\nFIELD DEFINITIONS:\n")) {
        val definitions = objectResponse(prompt.substringAfterLast("\n\nFIELD DEFINITIONS:\n"))
        require(fields == definitions.keys().asSequence().toSet()) {
          "The response format must use exactly the generated field definitions."
        }
      }
      require(fields.all { prompt.contains(it) }) {
        "LFM did not define every generated extraction field."
      }
      return template
    }

    fun templateFields(template: JSONObject): Set<String> {
      val fields = template.keys().asSequence().toSet()
      require(fields.size in 1..24 && fields.all { it.isNotBlank() && template.isNull(it) }) {
        "LFM must generate an empty JSON template with null values."
      }
      return fields
    }

    fun extractionFieldsFromInput(prompt: String): Set<String> =
        templateFields(
            objectResponse(
                prompt.substringAfterLast("\n\nRESPONSE FORMAT:\n").substringBefore("\n\nTASK:\n")))
  }
}
