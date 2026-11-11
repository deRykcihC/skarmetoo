package com.deryk.skarmetoo.chat

import java.time.LocalDate
import java.util.Locale

enum class ChatDateBasis(val label: String) {
  EVENT_THEN_TAKEN("Event date, otherwise image taken date"),
  TAKEN("Image taken date"),
  ADDED("Image added/imported date"),
}

data class ChatQuery(
    val question: String,
    val spending: Boolean,
    val start: LocalDate? = null,
    val endExclusive: LocalDate? = null,
    val terms: Set<String> = emptySet(),
    val unsupportedDate: Boolean = false,
    val dateBasis: ChatDateBasis = ChatDateBasis.EVENT_THEN_TAKEN,
    val dateError: String? = null,
) {
  fun includes(date: LocalDate?): Boolean =
      start == null || (date != null && !date.isBefore(start) && date.isBefore(endExclusive))

  val period: String
    get() = if (start == null) "all saved dates" else "$start to " + endExclusive!!.minusDays(1)

  companion object {
    private val ignored =
        setOf(
            "a",
            "an",
            "the",
            "is",
            "are",
            "my",
            "me",
            "i",
            "where",
            "what",
            "when",
            "how",
            "do",
            "did",
            "was",
            "were",
            "of",
            "in",
            "on",
            "to",
            "for",
            "about",
            "find",
            "show",
            "much",
            "spent",
            "spend",
            "spending",
            "paid",
            "pay",
            "total",
            "last",
            "this",
            "week",
            "month",
            "today",
            "yesterday",
            "days",
            "day",
            "money",
            "expenses",
            "expense",
            "cost",
            "have",
            "past",
            "previous",
            "calendar",
            "you",
            "your",
            "from",
            "between",
            "and",
            "through",
            "until",
            "during",
            "including",
            "at",
            "images",
            "image",
            "screenshots",
            "screenshot",
            "photos",
            "photo",
            "pictures",
            "picture",
            "saved",
            "save",
            "taken",
            "took",
            "captured",
            "photographed",
            "added",
            "imported",
            "may")

    fun parse(
        question: String,
        today: LocalDate = LocalDate.now(),
        numericOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.DAY_FIRST,
    ): ChatQuery {
      val q = question.lowercase(Locale.ROOT)
      val window = ChatDateWindows.resolve(q, today, numericOrder)
      val withoutDates =
          q.toCharArray()
              .also { chars -> window.spans.forEach { span -> for (i in span) chars[i] = ' ' } }
              .concatToString()
      val terms =
          Regex("[\\p{L}\\p{N}]+")
              .findAll(withoutDates)
              .map { it.value }
              .filter { it !in ignored && it.toIntOrNull() == null }
              .filter { it != "period" || window.spans.isEmpty() }
              .toSet()
      val basis =
          when {
            Regex("\\b(added|imported)\\b|\\bi\\s+save(?:d)?\\b").containsMatchIn(q) ->
                ChatDateBasis.ADDED
            Regex("\\b(taken|captured|photographed)\\b|\\bi\\s+took\\b").containsMatchIn(q) ->
                ChatDateBasis.TAKEN
            else -> ChatDateBasis.EVENT_THEN_TAKEN
          }
      return ChatQuery(
          question,
          Regex("\\b(spend|spent|spending|paid|pay|cost|expense|expenses)\\b").containsMatchIn(q),
          window.start,
          window.endExclusive,
          terms,
          window.error != null,
          basis,
          window.error)
    }
  }
}
