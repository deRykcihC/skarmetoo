package com.deryk.skarmetoo.chat

import android.graphics.Bitmap
import android.os.SystemClock
import com.deryk.skarmetoo.ai.GgufLlmManager
import com.deryk.skarmetoo.ai.GgufModelInfo
import com.deryk.skarmetoo.ai.VisionBatch
import com.deryk.skarmetoo.ai.generateChatResponse
import com.deryk.skarmetoo.ai.withModelUnloaded
import com.deryk.skarmetoo.ai.withVisionBatch
import com.deryk.skarmetoo.data.ScreenshotEntry
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ChatWorkflowAnswer(
    val answer: String,
    val sources: List<ScreenshotEntry>,
    val amounts: ChatAmountResult? = null,
    val parts: List<ChatPartAnswer> = emptyList(),
)

data class ChatPartAnswer(
    val question: String,
    val query: ChatQuery,
    val amounts: ChatAmountResult?,
    val records: List<ChatExtractedRecord>,
    val candidates: Int,
) {
  val status: String
    get() = if (records.isEmpty()) "no_confirmed_evidence" else "confirmed_saved_evidence"

  val summary: String
    get() = buildString {
      if (amounts != null && records.isNotEmpty()) {
        append("Amounts found (${query.period}): ")
        append(
            amounts.totals.entries.joinToString("; ") { "${it.key} ${it.value.toPlainString()}" })
        append(" across ${records.size} confirmed saved records.")
        amounts.amounts
            .sortedWith(compareByDescending<ChatAmount> { it.date }.thenBy { it.record.sourceId })
            .forEach {
              append(
                  "\n[${it.record.sourceId}] ${it.currency} ${it.amount.toPlainString()} · ${it.date} (${it.resolvedDate?.label})")
            }
      } else if (records.isEmpty()) {
        append(
            "${query.period}: no confirmed matching saved evidence. The total is unknown; this does not establish zero spending.")
      } else {
        append("${query.period}: ${records.size} confirmed saved records.")
        records.forEach { append("\n[${it.sourceId}] ${it.json}") }
      }
      append("\nChecked $candidates candidates. ${query.dateBasis.label}.")
      amounts?.exclusions?.forEach { append("\n[${it.sourceId}] excluded: ${it.reason}") }
    }
}

/** One complete text plan, a contiguous vision batch, then a text answer. */
class ChatWorkflowRunner(
    private val gguf: GgufLlmManager,
    private val model: GgufModelInfo,
    private val modelDirectory: File,
    private val repository: ChatRepository,
    private val cache: ChatWorkflowCache,
    private val checkActive: suspend () -> Unit,
    private val onSteps: (List<ChatWorkflowStep>) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onRetrieval: (ChatQuery, ChatRetrieval) -> Unit,
    private val today: LocalDate = LocalDate.now(),
) {
  private val steps = mutableListOf<ChatWorkflowStep>()
  private var candidates: List<ChatCandidate> = emptyList()
  private var bitmap: Bitmap? = null
  private var bitmapSource: Long? = null
  private var visionBatch: VisionBatch? = null
  private val contentHashes = mutableMapOf<Long, String>()
  private val imageTexts = mutableMapOf<Long, String>()
  private val printedDates = mutableMapOf<Long, ChatPrintedEventDate>()
  private val printedPaidAmounts = mutableMapOf<Long, ChatPrintedPaidAmount>()
  private val visibleEventLabels = mutableSetOf<Long>()

  private fun modelIdentity(info: GgufModelInfo) =
      listOf(info.fileName, info.mmprojFile).joinToString("|") {
        val file = File(modelDirectory, it)
        "$it:${file.length()}:${file.lastModified()}:${info.chatTemplate}"
      }

  suspend fun run(question: String): ChatWorkflowAnswer {
    try {
      val request = ChatRequestWorkflow(::call, today, repository.numericDateOrder)
      val workflow = ChatWorkflow(::call, checkActive)
      val parts = request.parts(question)
      val dated =
          gguf.withModelUnloaded(model) {
            parts.map { part ->
              val found = repository.retrieve(part.query, part.question, onProgress)
              onRetrieval(part.query, found)
              part to found
            }
          }
      val plansByIntent = mutableMapOf<String, ChatWorkflowPlan>()
      val retrieved =
          dated
              .filter { (_, found) -> found.candidates.isNotEmpty() }
              .map { (datedPart, found) ->
                checkActive()
                val part = request.rewrite(datedPart)
                val plan =
                    plansByIntent[part.intent]
                        ?: workflow.planIntent(part.question, part.intent).also {
                          plansByIntent[part.intent] = it
                        }
                PreparedPart(part, plan, found)
              }
      candidates = dated.flatMap { it.second.candidates }.distinctBy { it.entry.id }
      // Identical model-generated image questions share their checks, including overlapping dates.
      val groups =
          retrieved.groupBy {
            it.plan.verificationPrompt +
                "\n" +
                it.plan.extraction.prompt +
                "\n" +
                it.plan.extraction.format
          }
      val inspections = mutableMapOf<String, ChatInspection>()
      if (candidates.isNotEmpty()) {
        gguf.withVisionBatch(model, ChatWorkflowModels.vision) { batch ->
          visionBatch = batch
          try {
            for ((key, group) in groups) {
              val ids = group.flatMap { it.retrieval.candidates }.map { it.entry.id }.distinct()
              inspections[key] = workflow.inspect(group.first().plan, ids)
            }
          } finally {
            visionBatch = null
            bitmap?.recycle()
            bitmap = null
            bitmapSource = null
          }
        }
      }
      // Only after releasing 450M: 3B writes and answers focused questions about contradictions.
      for ((key, group) in groups) {
        val inspection = inspections[key] ?: continue
        val unqualified =
            group.firstOrNull { it.part.query.spending && it.part.query.terms.isEmpty() }
        if (unqualified != null) {
          val reviewed =
              inspection.records.map { record ->
                val paid = printedPaidAmounts[record.sourceId]
                if (paid == null ||
                    record.verification.decision != ChatDecision.YES ||
                    ChatWorkflowFacts.amounts(
                            unqualified.part.query.copy(start = null, endExclusive = null),
                            listOf(record),
                            emptyMap(),
                            repository.numericDateOrder,
                            printedPaidAmounts = mapOf(record.sourceId to paid))
                        .amounts
                        .singleOrNull()
                        ?.let {
                          it.amount.compareTo(paid.amount) == 0 && it.currency == paid.currency
                        } == true)
                    record
                else
                    try {
                      request.resolve(
                          unqualified.part, record, imageTexts[record.sourceId].orEmpty(), paid)
                    } catch (e: CancellationException) {
                      throw e
                    } catch (_: Exception) {
                      record // The facts validator excludes a remaining explicit contradiction.
                    }
              }
          inspections[key] = inspection.copy(records = reviewed)
        }
      }
      val results =
          dated.map { (datedPart, found) ->
            val preparedPart = retrieved.firstOrNull { it.part.question == datedPart.question }
            val key =
                preparedPart?.plan?.verificationPrompt +
                    "\n" +
                    preparedPart?.plan?.extraction?.prompt +
                    "\n" +
                    preparedPart?.plan?.extraction?.format
            val ids = found.candidates.map { it.entry.id }.toSet()
            val inspection = inspections[key] ?: ChatInspection(emptyList(), 0, 0, null)
            val records = inspection.records.filter { it.sourceId in ids }
            val query = preparedPart?.part?.query ?: datedPart.query
            val amounts =
                if (query.spending)
                    ChatWorkflowFacts.amounts(
                        query,
                        records,
                        contentHashes,
                        repository.numericDateOrder,
                        printedDates,
                        printedPaidAmounts,
                        found.candidates.associate { it.entry.id to it.dates },
                        visibleEventLabels)
                else null
            val accepted =
                amounts?.amounts?.map { it.record }
                    ?: records.filter {
                      it.verification.decision == ChatDecision.YES &&
                          query.includes(
                              ChatEventDates.resolve(
                                      query,
                                      it.json,
                                      printedDates[it.sourceId],
                                      candidates.first { c -> c.entry.id == it.sourceId }.dates,
                                      repository.numericDateOrder,
                                      it.sourceId in visibleEventLabels)
                                  .date)
                    }
            ChatPartAnswer(datedPart.question, query, amounts, accepted, found.candidates.size)
          }
      val combinedAmounts = ChatAnswerCalculations.combinedAmounts(results, contentHashes)
      val combinedSummary = ChatAnswerCalculations.combinedSummary(results, combinedAmounts)
      val calculations =
          (results.map { it.summary } + combinedSummary)
              .filter { it.isNotBlank() }
              .joinToString("\n\n")
      val evidence =
          results.joinToString("\n") { part ->
            part.amounts?.amounts?.take(20)?.joinToString("\n") {
              "[${it.record.sourceId}] ${it.currency} ${it.amount.toPlainString()} · ${it.date}"
            } ?: part.records.take(10).joinToString("\n") { "[${it.sourceId}] ${it.json}" }
          }
      val explanation =
          try {
            call(
                ChatWorkflowStage.ANSWER,
                "${ChatWorkflowPrompts.answer}\n\nUSER QUESTION: $question\n" +
                    "APP_CALCULATIONS:\n$calculations\nACCEPTED FACTS:\n$evidence",
                null)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            ""
          }
      // Missing evidence is not zero real-world activity; authoritative per-period summaries stay.
      val complete = results.all { it.records.isNotEmpty() }
      val safeExplanation =
          explanation
              .takeIf {
                complete &&
                    (combinedSummary.isBlank() ||
                        ChatAnswerValidation.canDisplaySpendingExplanation(
                            explanation, requireNotNull(combinedAmounts).totals)) &&
                    results.all { part ->
                      part.amounts?.let {
                        ChatAnswerValidation.canDisplaySpendingExplanation(
                            text = explanation, totals = it.totals)
                      } ?: true
                    }
              }
              .orEmpty()
      val sources =
          candidates
              .filter { c ->
                results.any { part -> part.records.any { it.sourceId == c.entry.id } }
              }
              .map { it.entry }
      return ChatWorkflowAnswer(
          listOf(
                  calculations,
                  safeExplanation,
                  "This covers confirmed evidence in saved images; missing records may change the totals.")
              .filter { it.isNotBlank() }
              .joinToString("\n\n"),
          sources,
          combinedAmounts,
          results)
    } finally {
      bitmap?.recycle()
      bitmap = null
      bitmapSource = null
      withContext(NonCancellable) { gguf.unloadWhenIdle(model) }
    }
  }

  private data class PreparedPart(
      val part: ChatRequestPart,
      val plan: ChatWorkflowPlan,
      val retrieval: ChatRetrieval,
  )

  private suspend fun call(stage: ChatWorkflowStage, prompt: String, sourceId: Long?): String {
    checkActive()
    val inferenceModel = ChatWorkflowModels.forStage(stage)
    val index = steps.size
    val systemPrompt =
        when (stage) {
          ChatWorkflowStage.SPLIT -> ChatWorkflowPrompts.split
          ChatWorkflowStage.DATES -> ChatWorkflowPrompts.dates
          ChatWorkflowStage.REVIEW -> ChatWorkflowPrompts.review
          ChatWorkflowStage.RESOLVE -> ChatWorkflowPrompts.resolve
          ChatWorkflowStage.REWRITE -> ChatWorkflowPrompts.rewrite
          ChatWorkflowStage.PLAN -> ChatWorkflowPrompts.plan
          else -> null
        }
    val responseSchema = ChatWorkflowSchemas.forStage(stage)
    steps +=
        ChatWorkflowStep(
            stage,
            prompt,
            sourceId,
            systemPrompt = systemPrompt.orEmpty(),
            model = inferenceModel.displayName,
            responseSchema = responseSchema.orEmpty())
    onSteps(steps.toList())
    val candidate = sourceId?.let { id -> candidates.first { it.entry.id == id } }
    val visual = stage == ChatWorkflowStage.VERIFY || stage == ChatWorkflowStage.EXTRACT
    val position = candidate?.let { candidates.indexOf(it) + 1 }
    onProgress(stage.label + if (sourceId == null) "" else " $position/${candidates.size}")
    val started = SystemClock.elapsedRealtime()
    try {
      var request = prompt
      if (stage == ChatWorkflowStage.EXTRACT && candidate != null) {
        val text =
            imageTexts[sourceId]
                ?: try {
                      repository.imageText(candidate.entry, candidateBitmap(candidate))
                    } catch (e: CancellationException) {
                      throw e
                    } catch (_: Exception) {
                      "" // Reading aid unavailable: retain the existing image-model/date validation
                      // path.
                    }
                    .also { imageTexts[requireNotNull(sourceId)] = it }
        ChatPrintedEventDates.find(text, repository.numericDateOrder)?.let {
          printedDates[requireNotNull(sourceId)] = it
        }
        if (ChatPrintedEventDates.hasLabel(text)) visibleEventLabels += requireNotNull(sourceId)
        ChatPrintedPaidAmounts.find(text)?.let { printedPaidAmounts[requireNotNull(sourceId)] = it }
        request = ChatWorkflowPrompts.withImageText(prompt, text)
        steps[index] =
            steps[index].copy(
                prompt = request,
                dateEvidence = printedDates[sourceId]?.evidence.orEmpty(),
                amountEvidence = printedPaidAmounts[sourceId]?.evidence.orEmpty())
        onSteps(steps.toList())
        checkActive()
      }
      val cacheEntry =
          candidate?.entry?.let { entry ->
            val hash =
                contentHashes[entry.id]
                    ?: repository.imageContentHash(entry).also { contentHashes[entry.id] = it }
            entry.copy(imageHash = hash)
          }
      val key =
          ChatWorkflowCacheKey.build(
              modelIdentity(inferenceModel), stage, request, cacheEntry, responseSchema)
      val cacheable =
          stage != ChatWorkflowStage.ANSWER &&
              (candidate == null || cacheEntry?.imageHash?.isNotBlank() == true)
      val cached = if (!cacheable) null else withContext(Dispatchers.IO) { cache.get(key) }
      val raw =
          cached
              ?: run {
                // Prompt writers/final answers do not need candidate pixels in memory.
                if (!visual) {
                  bitmap?.recycle()
                  bitmap = null
                  bitmapSource = null
                }
                if (visual && candidate != null) candidateBitmap(candidate)
                val onToken: (String) -> Unit = { token ->
                  steps[index] = steps[index].copy(response = steps[index].response + token)
                  onSteps(steps.toList())
                }
                if (visual && candidate != null) {
                  requireNotNull(visionBatch)
                      .generate(
                          request,
                          requireNotNull(bitmap),
                          maxOutputTokens = stage.tokenLimit,
                          onToken = onToken)
                } else {
                  gguf.generateChatResponse(
                      request,
                      expectedModel = model,
                      maxOutputTokens = stage.tokenLimit,
                      systemPrompt = systemPrompt,
                      responseSchema = null,
                      onToken = onToken)
                }
              }
      steps[index] = steps[index].copy(response = raw, cached = cached != null)
      when (stage) {
        ChatWorkflowStage.SPLIT -> ChatRequestWorkflow.parseQuestions(raw)
        ChatWorkflowStage.DATES ->
            ChatRequestWorkflow.parsePeriod(
                raw,
                prompt.substringAfter("QUESTION:\n").substringBefore("\n\nREFERENCE DATE:"),
                today)
        ChatWorkflowStage.REVIEW -> ChatRequestWorkflow.parseFollowup(raw)
        ChatWorkflowStage.RESOLVE -> {
          val json = ChatRequestWorkflow.parseResolved(raw)
          printedPaidAmounts[sourceId]?.let { paid ->
            require(ChatRequestWorkflow.matchesPaid(json, paid)) {
              "The quantity/unit still conflicts with the explicit paid label in the quoted observations. Re-read the labels and resolve that discrepancy."
            }
          }
        }
        ChatWorkflowStage.REWRITE ->
            ChatWorkflow.parseRewrite(raw, prompt.substringBefore("\n\nVALIDATION ERROR:"))
        ChatWorkflowStage.PLAN ->
            ChatWorkflow.parsePlan(raw, prompt.substringBefore("\n\nVALIDATION ERROR:"))
        ChatWorkflowStage.VERIFY -> ChatWorkflow.parseVerification(raw)
        ChatWorkflowStage.EXTRACT -> {
          val fields = ChatWorkflow.extractionFieldsFromInput(request)
          require(ChatWorkflow.objectResponse(raw).keys().asSequence().toSet() == fields) {
            "LFM changed its generated extraction fields."
          }
        }
        ChatWorkflowStage.ANSWER -> require(raw.isNotBlank()) { "LFM returned no answer." }
      }
      if (cached == null && cacheable)
          withContext(Dispatchers.IO) { cache.put(key, raw, cacheEntry) }
      checkActive()
      return raw
    } catch (e: Exception) {
      steps[index] = steps[index].copy(error = e.message ?: "Model request failed")
      if (steps[index].response.isNotBlank() &&
          stage in
              setOf(
                  ChatWorkflowStage.SPLIT,
                  ChatWorkflowStage.REWRITE,
                  ChatWorkflowStage.DATES,
                  ChatWorkflowStage.PLAN,
                  ChatWorkflowStage.REVIEW,
                  ChatWorkflowStage.RESOLVE)) {
        throw IllegalArgumentException(
            "${e.message}\nPREVIOUS MODEL RESPONSE (untrusted reference): ${org.json.JSONObject.quote(steps[index].response.take(1200))}",
            e)
      }
      throw e
    } finally {
      steps[index] =
          steps[index].copy(working = false, elapsedMs = SystemClock.elapsedRealtime() - started)
      onSteps(steps.toList())
    }
  }

  private suspend fun candidateBitmap(candidate: ChatCandidate): Bitmap {
    if (bitmapSource != candidate.entry.id || bitmap == null) {
      bitmap?.recycle()
      bitmap = null
      bitmapSource = null
      bitmap = repository.loadImage(candidate.entry)
      bitmapSource = candidate.entry.id
    }
    return requireNotNull(bitmap)
  }
}
