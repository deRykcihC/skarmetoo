package com.deryk.skarmetoo.ai.agentic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's JSON and regex implementations; does not load or call a model. */
@RunWith(AndroidJUnit4::class)
class AgenticFormattingDeviceTest {
  @Test
  fun sentenceCleanupAndLiteralBindingWorkOnAndroid() {
    val schema =
        AgenticContracts.objectSchema(
            mapOf("verification_question" to JSONObject().put("type", "string")))
    val fixed =
        AgenticResponseFormatting.normalize(
            """{"verification_question":"}Does this image show completed payment evidence?"}""",
            schema)
    assertEquals(
        "Does this image show completed payment evidence?",
        AgenticContracts.parse(fixed.text).getString("verification_question"))
    assertEquals(1, fixed.changes.size)
    val input = AgenticPlanParser.normalizationInput("spending on 23/12/2021")
    assertEquals("spending on 23/12/2021", input.restore(input.question))
    assertTrue(runCatching { input.restore("spending on December 23, 2021") }.isFailure)
  }

  @Test
  fun numericRepresentationsAreLosslessAndDuplicatesRejectedOnAndroid() {
    val raw =
        """{"image_kind":"PAYMENT_RECEIPT","direction":"OUTGOING","payment_status":"COMPLETED","transaction_date":23/12/2021,"amount":123456789012.9999,"currency":"RM","merchant":null,"evidence":"You Paid"}"""
    val observation = AgenticPaymentFacts.observation(raw, "fixture")
    assertEquals("123456789012.9999", observation.verification.amount?.toPlainString())
    assertEquals("2021-12-23", observation.verification.transactionDate?.toString())
    assertEquals("MYR", observation.verification.currency)
    assertEquals(Match.YES, observation.verification.match)
    assertTrue(
        runCatching {
              AgenticResponseFormatting.normalize(
                  """{"verification_question":"first","verification_question":"second"}""",
                  AgenticContracts.objectSchema(
                      mapOf("verification_question" to JSONObject().put("type", "string"))))
            }
            .isFailure)
  }
}
