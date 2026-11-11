package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import com.deryk.skarmetoo.chat.ChatDateWindows
import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.math.BigDecimal
import java.time.LocalDate

object AgenticDates {
  fun resolve(filter: DateFilter?, today: LocalDate): List<ChatDateWindow> {
    filter?.resolvedWindows?.let { windows ->
      // One model request can resolve to several disjoint weekend/weekday windows.
      require(windows.all { it.error == null && (it.start != null || it.endExclusive != null) })
      require(
          windows.all {
            it.start == null || it.endExclusive == null || it.endExclusive.isAfter(it.start)
          })
      return windows
    }
    return filter?.expressions.orEmpty().mapIndexed { i, phrase ->
      val order = filter?.numericOrders?.getOrNull(i) ?: ChatReceiptDateOrder.DAY_FIRST
      if (Regex("(?i)\\b(?:the\\s+)?day\\s+before\\s+yesterday\\b").containsMatchIn(phrase))
          return@mapIndexed ChatDateWindow(today.minusDays(2), today.minusDays(1))
      // The existing Chat page retains its rolling-week policy. This new page uses calendar
      // weeks.
      val calendar =
          phrase
              .replace(
                  Regex("(\\d{4}-\\d{2}-\\d{2})\\s*[-–]\\s*(\\d{4}-\\d{2}-\\d{2})"), "$1 to $2")
              .replace(
                  Regex("(\\d{1,2}/\\d{1,2}/\\d{4})\\s*[-–]\\s*(\\d{1,2}/\\d{1,2}/\\d{4})"),
                  "$1 to $2")
              .replace(
                  Regex("(?i)\\b(?:last|past|previous)(?:\\s+last)?\\s+week\\b"),
                  "last calendar week")
              .replace(
                  Regex(
                      "(?<![\\p{L}\\p{N}/-])(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{4}|\\d{2}))?(?![\\p{L}\\p{N}/-])")) {
                      match ->
                    val a = match.groupValues[1].toInt()
                    val b = match.groupValues[2].toInt()
                    val year =
                        match.groupValues[3].let {
                          if (it.isEmpty()) ""
                          else "/${if (it.length == 2) 2000 + it.toInt() else it.toInt()}"
                        }
                    val dayFirst =
                        when {
                          a > 12 -> true
                          b > 12 -> false
                          else -> order == ChatReceiptDateOrder.DAY_FIRST
                        }
                    if (dayFirst) "$a/$b$year" else "$b/$a$year"
                  }
      ChatDateWindows.resolve(calendar, today, ChatReceiptDateOrder.DAY_FIRST).also {
        require(it.error == null) { it.error!! }
        require(it.start != null || it.endExclusive != null) { "Unresolved date phrase: $phrase" }
      }
    }
  }

  fun includes(date: LocalDate?, windows: List<ChatDateWindow>): Boolean? =
      if (windows.isEmpty()) true
      else
          date?.let { day ->
            windows.any {
              (it.start == null || !day.isBefore(it.start)) &&
                  (it.endExclusive == null || day.isBefore(it.endExclusive))
            }
          }
}

object AgenticCalculation {
  fun calculate(
      records: List<VerifiedTransaction>,
      windows: List<ChatDateWindow> = emptyList(),
      requireAmount: Boolean = true,
      requireCompleted: Boolean = true
  ): CalculationResult {
    val confirmed = linkedMapOf<String, VerifiedTransaction>()
    val unresolved = mutableListOf<String>()
    val excluded = mutableListOf<String>()
    val conflicted = mutableSetOf<String>()
    records.forEach { transaction ->
      val inDate = AgenticDates.includes(transaction.date, windows)
      when {
        transaction.match == Match.NO ||
            (inDate == false && !transaction.requiresReview) ||
            (requireCompleted &&
                !transaction.requiresReview &&
                transaction.status in
                    setOf(PaymentStatus.failed, PaymentStatus.pending, PaymentStatus.refunded)) ->
            excluded += transaction.imageIds
        transaction.id in conflicted ||
            transaction.match != Match.YES ||
            transaction.requiresReview ||
            inDate == null ||
            (requireAmount && (transaction.amount == null || transaction.currency == null)) ||
            (requireCompleted && transaction.status != PaymentStatus.completed) ->
            unresolved += transaction.imageIds
        else -> {
          val previous = confirmed[transaction.id]
          if (previous == null) confirmed[transaction.id] = transaction
          else if (equalAmounts(previous.amount, transaction.amount) &&
              previous.currency == transaction.currency &&
              previous.date == transaction.date)
              confirmed[transaction.id] =
                  previous.copy(imageIds = (previous.imageIds + transaction.imageIds).distinct())
          else {
            confirmed.remove(transaction.id)
            conflicted += transaction.id
            unresolved += previous.imageIds + transaction.imageIds
          }
        }
      }
    }
    val totals =
        confirmed.values
            .filter { it.amount != null && it.currency != null }
            .groupBy { it.currency!! }
            .mapValues { (_, values) ->
              values.fold(BigDecimal.ZERO) { total, v -> total + v.amount!! }
            }
    return CalculationResult(
        totals, confirmed.values.toList(), unresolved.distinct(), excluded.distinct())
  }

  private fun equalAmounts(a: BigDecimal?, b: BigDecimal?) =
      if (a == null || b == null) a == b else a.compareTo(b) == 0

  fun monetaryAnswerIsGrounded(candidate: String, result: CalculationResult): Boolean =
      result.totals.all { (currency, total) ->
        val names = if (currency == "MYR") "(?:MYR|RM)" else Regex.escape(currency)
        Regex("(?i)$names\\s*${Regex.escape(total.toPlainString())}(?![0-9.])")
            .containsMatchIn(candidate)
      } &&
          Regex("\\b[A-Z]{3}\\b")
              .findAll(candidate)
              .map { it.value }
              .filter { runCatching { java.util.Currency.getInstance(it) }.isSuccess }
              .all { it in result.totals }

  fun answer(intent: Intent, result: CalculationResult, category: String? = null): String {
    val money =
        result.totals.entries
            .sortedBy { it.key }
            .joinToString("; ") { "${it.key} ${it.value.toPlainString()}" }
    val main =
        when (intent) {
          Intent.count_transactions -> "${result.transactions.size} confirmed payments."
          Intent.calculate_spending ->
              if (money.isBlank() && category != null && result.unresolvedIds.isNotEmpty())
                  "No confirmed $category spending found. The available evidence cannot determine $category spending."
              else if (money.isBlank()) "No confirmed spending found."
              else if (result.unresolvedIds.isNotEmpty())
                  "Confirmed subtotal: $money across ${result.transactions.size} transactions. A complete total cannot be determined from the available evidence."
              else "Confirmed spending: $money across ${result.transactions.size} transactions."
          else -> "${result.transactions.size} confirmed matching transactions found."
        }
    return main +
        if (result.unresolvedIds.isNotEmpty())
            " ${result.unresolvedIds.size} images need review and were excluded from the total."
        else ""
  }
}
