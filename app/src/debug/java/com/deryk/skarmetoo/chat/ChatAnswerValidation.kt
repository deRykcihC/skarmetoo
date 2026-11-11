package com.deryk.skarmetoo.chat

import java.math.BigDecimal

/**
 * A writer must reproduce every authoritative currency total before its explanation is displayed.
 */
object ChatAnswerValidation {
  fun canDisplaySpendingExplanation(text: String, totals: Map<String, BigDecimal>): Boolean =
      includesTotals(text, totals) &&
          !Regex("""\bAPP_CALCULATIONS\b|\bACCEPTED[ _]FACTS\b""", RegexOption.IGNORE_CASE)
              .containsMatchIn(text)

  fun includesTotals(text: String, totals: Map<String, BigDecimal>): Boolean =
      totals.isNotEmpty() &&
          totals.all { (currency, amount) ->
            val expected =
                "${Regex.escape(currency)}\\s+${Regex.escape(amount.toPlainString())}(?![\\d.])"
            Regex(expected, RegexOption.IGNORE_CASE).containsMatchIn(text)
          }
}
