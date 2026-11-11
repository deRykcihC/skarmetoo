package com.deryk.skarmetoo.chat

import java.math.BigDecimal

data class ChatPrintedPaidAmount(
    val amount: BigDecimal,
    val currency: String,
    val evidence: String
)

/**
 * Explicit paid labels only; purchase subtotals, discounts, rewards and bare prices do not qualify.
 */
object ChatPrintedPaidAmounts {
  private const val label = "(?:you\\s+paid|amount\\s+paid|paid\\s+amount|total\\s+paid)"
  private val paid =
      Regex(
          "\\b$label\\s*:?\\s*([A-Za-z]{2,3})\\s*(\\d+(?:\\.\\d{1,2})?)(?![\\p{L}\\p{N}]|[.,]\\d)",
          RegexOption.IGNORE_CASE)

  fun hasLabel(text: String) = Regex("\\b$label\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

  fun find(text: String): ChatPrintedPaidAmount? {
    val matches = paid.findAll(text).toList()
    if (matches.isEmpty()) return null
    val values =
        matches.map {
          val amount = it.groupValues[2].toBigDecimalOrNull()
          val currency = ChatWorkflowFacts.currency(it.groupValues[1])
          if (amount == null || amount.signum() <= 0 || currency == null) return null
          ChatPrintedPaidAmount(amount, currency, it.value)
        }
    if (values.any {
      it.currency != values.first().currency || it.amount.compareTo(values.first().amount) != 0
    })
        return null
    return values.first()
  }
}
