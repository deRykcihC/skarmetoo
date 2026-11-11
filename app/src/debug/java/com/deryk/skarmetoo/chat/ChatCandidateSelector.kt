package com.deryk.skarmetoo.chat

import com.deryk.skarmetoo.data.ScreenshotEntry
import java.time.LocalDate
import java.util.Locale

data class ChatCandidate(
    val entry: ScreenshotEntry,
    val imageDate: LocalDate?,
    val score: Float,
    val dates: ChatImageDates = ChatImageDates(taken = imageDate),
)

/** Cheap stored-text/date screening before any image decoding or vision inference. */
object ChatCandidateSelector {
  private val spendingSignal =
      Regex(
          "\\b(receipts?|payments?|paid|purchases?|transactions?|invoices?|expenses?|spent)\\b|收据|付款|支付|发票|收據|發票",
          RegexOption.IGNORE_CASE)
  private const val SPENDING_SEMANTIC_THRESHOLD = 0.62f

  fun text(entry: ScreenshotEntry) = "${entry.summary}\n${entry.tags}\n${entry.note}"

  fun hasSpendingSignal(entry: ScreenshotEntry): Boolean =
      spendingSignal.containsMatchIn(text(entry))

  fun relevant(query: ChatQuery, entry: ScreenshotEntry, semantic: Float): Boolean {
    if (entry.isAnalyzing || entry.imageUri.isBlank()) return false
    if (query.spending) return hasSpendingSignal(entry) || semantic >= SPENDING_SEMANTIC_THRESHOLD
    val text = text(entry).lowercase(Locale.ROOT)
    return query.terms.any { text.contains(it) } ||
        semantic >= 0.45f ||
        (query.terms.isEmpty() && query.start != null)
  }

  fun select(
      query: ChatQuery,
      entries: List<ScreenshotEntry>,
      dates: Map<String, LocalDate?>,
      scores: Map<String, Float>,
      metadata: Map<String, ChatImageDates> = emptyMap(),
      numericOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.DAY_FIRST,
  ): List<ChatCandidate> =
      entries
          .mapNotNull { entry ->
            val semantic = scores[entry.imageUri] ?: 0f
            if (!relevant(query, entry, semantic)) return@mapNotNull null
            val imageDates =
                metadata[entry.imageUri] ?: ChatImageDates(taken = dates[entry.imageUri])
            val date = imageDates.forQuery(query)
            val text = text(entry).lowercase(Locale.ROOT)
            // A stored transaction date may differ from the screenshot's capture/import date.
            val contextDateMatches =
                query.dateBasis == ChatDateBasis.EVENT_THEN_TAKEN &&
                    ChatDateWindows.explicitDates(text, numericOrder).any { query.includes(it) }
            val dateMatches =
                query.includes(date) ||
                    contextDateMatches ||
                    (query.dateBasis == ChatDateBasis.EVENT_THEN_TAKEN && date == null)
            if (!dateMatches) return@mapNotNull null
            val lexical = query.terms.count { text.contains(it) }
            ChatCandidate(
                entry,
                date,
                semantic +
                    lexical * 0.15f +
                    if (query.spending && hasSpendingSignal(entry)) 0.5f else 0f,
                imageDates)
          }
          .sortedByDescending { it.score }
}
