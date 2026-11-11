package com.deryk.skarmetoo.chat

import java.time.LocalDate

data class ChatPrintedEventDate(val raw: String, val date: LocalDate, val evidence: String)

/** A date must be explicitly attached to a completed/payment event, never capture or due time. */
object ChatPrintedEventDates {
  private const val label =
      "(?:completed\\s*(?:time|date|on|at)?|paid\\s+(?:on|at)|payment\\s+date|transaction\\s+(?:date|time)|date\\s+paid)"
  private val dateText =
      "(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})(?:[ T]+\\d{1,2}:\\d{2}(?::\\d{2})?)?"
  private val eventDate =
      Regex("\\b$label\\s*:?\\s*($dateText)(?![\\d/-])", RegexOption.IGNORE_CASE)

  fun hasLabel(text: String) = Regex("\\b$label\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

  fun find(text: String, order: ChatReceiptDateOrder): ChatPrintedEventDate? {
    val matches = eventDate.findAll(text).toList()
    if (matches.isEmpty()) return null
    val dates = matches.map { ChatWorkflowFacts.parseDate(it.groupValues[1], order) }
    // Multiple conflicting or unreadable labeled dates cannot establish one record's event date.
    if (dates.any { it == null } || dates.distinct().size != 1) return null
    val match = matches.first()
    return ChatPrintedEventDate(match.groupValues[1], requireNotNull(dates.first()), match.value)
  }
}
