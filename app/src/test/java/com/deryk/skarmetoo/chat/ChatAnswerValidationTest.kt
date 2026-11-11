package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class ChatAnswerValidationTest {
  @Test
  fun internalPromptLabelsFromTheMonthWeekRunAreNotShownInTheAnswer() {
    val totals = mapOf("MYR" to BigDecimal("13.00"))
    assertFalse(
        ChatAnswerValidation.canDisplaySpendingExplanation(
            "You spent MYR 13.00 in the fifth week of September 2026, based on APP_CALCULATIONS and ACCEPTED FACTS.",
            totals))
    assertTrue(
        ChatAnswerValidation.canDisplaySpendingExplanation(
            "You spent MYR 13.00 in the fifth week of September 2026 [6][7].", totals))
  }

  @Test
  fun missingResultNarrativeFromThePhoneRunIsNotDisplayed() {
    assertFalse(
        ChatAnswerValidation.includesTotals(
            "Total spending is missing. The provided records do not include the transaction [5][3][1].",
            mapOf("MYR" to BigDecimal("21.31"))))
  }

  @Test
  fun separateCurrencyTotalsMustAllBePreservedExactly() {
    val totals = mapOf("MYR" to BigDecimal("21.31"), "USD" to BigDecimal("6.00"))
    assertTrue(ChatAnswerValidation.includesTotals("Found MYR 21.31 and USD 6.00 [5].", totals))
    assertFalse(ChatAnswerValidation.includesTotals("Found MYR 21.31 [5].", totals))
    assertFalse(ChatAnswerValidation.includesTotals("Found MYR 21.319 and USD 6.00 [5].", totals))
    assertFalse(ChatAnswerValidation.includesTotals("Found MYR 27.31 and USD 6.00 [5].", totals))
  }
}
