package com.deryk.skarmetoo.chat

/** Totals over checked records; overlapping periods and identical image copies count once. */
object ChatAnswerCalculations {
  fun combinedAmounts(
      parts: List<ChatPartAnswer>,
      contentHashes: Map<Long, String> = emptyMap(),
  ): ChatAmountResult? {
    if (parts.none { it.amounts != null }) return null
    return ChatAmountResult(
        parts
            .flatMap { it.amounts?.amounts.orEmpty() }
            .distinctBy {
              contentHashes[it.record.sourceId]
                  ?.takeIf { hash -> hash.isNotBlank() }
                  ?.let { hash -> "image:$hash" } ?: "source:${it.record.sourceId}"
            },
        parts.sumOf { it.amounts?.excluded ?: 0 },
        parts.sumOf { it.amounts?.duplicates ?: 0 },
        parts.flatMap { it.amounts?.exclusions.orEmpty() })
  }

  fun combinedSummary(parts: List<ChatPartAnswer>, combined: ChatAmountResult?): String {
    if (parts.size < 2 || parts.any { it.amounts?.amounts.isNullOrEmpty() } || combined == null)
        return ""
    return "Combined confirmed total across the requested dates/periods: " +
        combined.totals.entries.joinToString("; ") { "${it.key} ${it.value.toPlainString()}" } +
        " across ${combined.amounts.size} unique saved records."
  }
}
