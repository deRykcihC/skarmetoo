package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Completes explicit labelled facts after visual context verification; never parses arbitrary
 * numbers.
 */
object AgenticReceiptEvidence {
  /** Identity only: ignore a copy-button label; never deduplicate by price or merchant alone. */
  fun transactionReference(ocr: String?): String? =
      Regex(
              "(?i)\\b(?:transaction\\s*(?:sn|id|ref(?:erence)?)|receipt\\s*(?:id|no))\\s*[:#]?\\s*(?:copy\\s+)?([A-Za-z0-9-]{8,64})(?![A-Za-z0-9-])")
          .findAll(ocr.orEmpty())
          .map { it.groupValues[1].uppercase(java.util.Locale.ROOT) }
          .filter { reference -> reference.any(Char::isDigit) }
          .distinct()
          .toList()
          .singleOrNull()

  data class Result(
      val verification: ImageVerification,
      val filled: List<String>,
      val conflicts: List<String>
  )

  fun hasPrintedEventDate(ocr: String?): Boolean =
      Regex(
              "(?i)\\b(?:completed\\s*time|payment\\s*date|transaction\\s*date|paid\\s*on|purchase\\s*date|date)\\b")
          .containsMatchIn(ocr.orEmpty())

  fun complete(v: ImageVerification, ocr: String?): Result {
    if (v.match != Match.YES || v.paymentStatus != PaymentStatus.completed || ocr.isNullOrBlank())
        return Result(v, emptyList(), emptyList())
    val filled = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    val paid =
        Regex(
                "(?im)\\b(?:you\\s+paid|amount\\s+paid|paid\\s+amount|total\\s+paid)\\s*:?\\s*(RM|[A-Z]{3})\\s*([0-9]{1,12}(?:\\.[0-9]{1,4})?)(?![0-9.])")
            .findAll(ocr)
            .mapNotNull { match ->
              val code =
                  if (match.groupValues[1].equals("RM", true)) "MYR"
                  else match.groupValues[1].uppercase()
              if (runCatching { java.util.Currency.getInstance(code) }.isFailure) null
              else code to BigDecimal(match.groupValues[2])
            }
            .distinctBy { "${it.first}:${it.second.stripTrailingZeros()}" }
            .toList()
    var amount = v.amount
    var currency = v.currency
    if (paid.size == 1) {
      val (code, value) = paid.single()
      if (amount == null) {
        amount = value
        filled += "amount"
      } else if (amount.compareTo(value) != 0) conflicts += "amount"
      if (currency == null) {
        currency = code
        filled += "currency"
      } else if (currency != code) conflicts += "currency"
    } else if (paid.size > 1) conflicts += "paid amount"
    val eventDates =
        Regex(
                "(?i)\\b(?:completed\\s+time|transaction\\s+date|payment\\s+date|paid\\s+on)\\s*:?\\s*([0-9]{1,4}[-/.][0-9]{1,2}[-/.][0-9]{1,4})")
            .findAll(ocr)
            .mapNotNull { match ->
              val parts = match.groupValues[1].split(Regex("[-/.]"))
              runCatching {
                    if (parts[0].length == 4)
                        LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    else if (parts[2].length == 4)
                        LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                    else null
                  }
                  .getOrNull()
            }
            .distinct()
            .toList()
    var date = v.transactionDate
    if (eventDates.size == 1) {
      if (date == null) {
        date = eventDates.single()
        filled += "transaction_date"
      } else if (date != eventDates.single()) conflicts += "transaction_date"
    } else if (eventDates.size > 1) conflicts += "transaction_date"
    var merchant = v.merchant
    if (merchant == null) {
      val payees =
          Regex("(?im)\\b(?:pay\\s+to|paid\\s+to|merchant)\\s*:?[^\\S\\n]*(?:\\n)?([^\\n]{2,100})")
              .findAll(ocr)
              .map { it.groupValues[1].trim() }
              .distinct()
              .toList()
      if (payees.size == 1) {
        merchant = payees.single()
        filled += "merchant"
      }
    }
    return Result(
        v.copy(
            amount = amount,
            currency = currency,
            transactionDate = date,
            merchant = merchant,
            requiresReview = v.requiresReview || conflicts.isNotEmpty()),
        filled,
        conflicts)
  }
}
