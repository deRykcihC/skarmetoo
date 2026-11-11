package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticAnswerSynthesisTest {
  @Test
  fun compactParagraphEntriesHaveReadableSeparators() {
    val synthesis = AgenticAnswerSynthesis(result)
    val json = reply()
    for (i in 0 until json.getJSONArray("breakdown").length()) {
      val entry = json.getJSONArray("breakdown").getJSONObject(i)
      entry.put(
          "text", "${synthesis.days[i].date}: MYR ${synthesis.days[i].totals.getValue("MYR")}")
    }
    synthesis.validate(json, synthesis.days)
    assertTrue(
        synthesis
            .render(listOf(json))
            .contains("01/10/2026: MYR 5.72; 02/10/2026: MYR 7.69; 05/10/2026: MYR 7.77."))
  }

  private fun payment(id: String, date: String?, amount: String, currency: String = "MYR") =
      VerifiedTransaction(
          id,
          listOf(id),
          BigDecimal(amount),
          currency,
          date?.let(LocalDate::parse),
          "printed_event",
          null,
          PaymentStatus.completed,
          Match.YES,
          false)

  private val result =
      AgenticCalculation.calculate(
          listOf(
              payment("a", "2026-10-01", "5.72"),
              payment("b", "2026-10-02", "7.69"),
              payment("c", "2026-10-05", "7.77")))

  private fun reply(note: String = "", layout: String = "paragraph") =
      JSONObject()
          .put("summary", "Your confirmed payments total MYR 21.18.")
          .put(
              "breakdown",
              JSONArray(
                  listOf(
                      JSONObject()
                          .put("id", "day_0")
                          .put("text", "On 01/10/2026 you paid RM 5.72."),
                      JSONObject()
                          .put("id", "day_1")
                          .put("text", "02/10/2026 accounts for MYR 7.69."),
                      JSONObject()
                          .put("id", "day_2")
                          .put("text", "MYR 7.77 was paid on 05/10/2026."))))
          .put("review_note", note)
          .put("layout", layout)

  @Test
  fun acceptsNaturalParagraphAndBulletWordingWithoutStockDisclaimer() {
    val synthesis = AgenticAnswerSynthesis(result)
    for (layout in listOf("paragraph", "bullets", "lines")) {
      val json = reply(layout = layout)
      synthesis.validate(json, synthesis.days)
      val rendered = synthesis.render(listOf(json))
      assertTrue(rendered.contains("21.18"))
      assertTrue(rendered.contains("01/10/2026"))
      assertFalse(rendered.contains("cannot be determined"))
    }
  }

  @Test
  fun partialEvidenceAllowsDifferentHonestQualifications() {
    val synthesis = AgenticAnswerSynthesis(result.copy(unresolvedIds = listOf("x", "y")))
    for (note in
        listOf(
            "Other images need review and were excluded, so additional payments may be missing.",
            "2 unverified images are not included in these amounts.")) {
      synthesis.validate(reply(note), synthesis.days)
    }
    assertTrue(runCatching { synthesis.validate(reply(), synthesis.days) }.isFailure)
    assertFalse(
        synthesis.fallback(Intent.calculate_spending, null).contains("cannot be determined"))
  }

  @Test
  fun rejectsWrongTotalSwappedDatesMissingDayInventedNumbersAndCurrencies() {
    val synthesis = AgenticAnswerSynthesis(result)
    val invalid =
        listOf(
            reply().put("summary", "You spent MYR 22.18."),
            reply().also {
              it.getJSONArray("breakdown").getJSONObject(0).put("text", "01/10/2026: MYR 7.77.")
            },
            reply().also {
              it.getJSONArray("breakdown").getJSONObject(0).put("text", "03/10/2026: MYR 5.72.")
            },
            reply().also { it.getJSONArray("breakdown").remove(1) },
            reply().put("summary", "Confirmed MYR 21.18 from 99 payments."),
            reply().also {
              it.getJSONArray("breakdown").getJSONObject(1).put("text", "02/10/2026: USD 7.69.")
            },
            reply().also { it.getJSONArray("breakdown").getJSONObject(1).put("id", "day_0") })
    invalid.forEach { json ->
      assertTrue(
          json.toString(), runCatching { synthesis.validate(json, synthesis.days) }.isFailure)
    }
  }

  @Test
  fun dailySumsUseDeduplicatedPaymentsAndSeparateCurrencies() {
    val a = payment("same", "2026-10-01", "5.72")
    val calculation =
        AgenticCalculation.calculate(
            listOf(
                a,
                a.copy(imageIds = listOf("copy")),
                payment("b", "2026-10-01", "1.28"),
                payment("c", "2026-10-01", "2.00", "USD")))
    val synthesis = AgenticAnswerSynthesis(calculation)
    assertEquals(1, synthesis.days.size)
    assertEquals(3, synthesis.days.single().count)
    assertEquals(BigDecimal("7.00"), synthesis.days.single().totals["MYR"])
    val json =
        JSONObject()
            .put("summary", "Confirmed totals are MYR 7.00 and USD 2.00.")
            .put(
                "breakdown",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "day_0")
                            .put("text", "01/10/2026: MYR 7.00 and USD 2.00, from 3 payments."))))
            .put("review_note", "")
            .put("layout", "lines")
    synthesis.validate(json, synthesis.days)
    json
        .getJSONArray("breakdown")
        .getJSONObject(0)
        .put("text", "01/10/2026: MYR 2.00 and USD 7.00.")
    assertTrue(runCatching { synthesis.validate(json, synthesis.days) }.isFailure)
  }

  @Test
  fun undatedPaymentsDoNotAcquireInventedDates() {
    val synthesis =
        AgenticAnswerSynthesis(AgenticCalculation.calculate(listOf(payment("a", null, "5.72"))))
    val json =
        JSONObject()
            .put("summary", "Confirmed total: MYR 5.72.")
            .put(
                "breakdown",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "day_0")
                            .put("text", "Payment date unknown: MYR 5.72."))))
            .put("review_note", "")
            .put("layout", "paragraph")
    synthesis.validate(json, synthesis.days)
    json.getJSONArray("breakdown").getJSONObject(0).put("text", "01/10/2026: MYR 5.72.")
    assertTrue(runCatching { synthesis.validate(json, synthesis.days) }.isFailure)
  }

  @Test
  fun absentEvidenceIsNotRenderedAsZeroSpending() {
    val synthesis =
        AgenticAnswerSynthesis(CalculationResult(emptyMap(), emptyList(), listOf("x"), emptyList()))
    val json =
        JSONObject()
            .put("summary", "No matching payments are confirmed.")
            .put("breakdown", JSONArray())
            .put("review_note", "An image needs review and is excluded.")
            .put("layout", "paragraph")
    synthesis.validate(json, emptyList())
    json.put("summary", "No confirmed spending: MYR 0.00.")
    assertTrue(runCatching { synthesis.validate(json, emptyList()) }.isFailure)
  }

  @Test
  fun batchingKeepsEveryDayAndOneOverallTotal() {
    val synthesis = AgenticAnswerSynthesis(result)
    val original = reply()
    val parts =
        synthesis.days.chunked(1).map { batch ->
          JSONObject(original.toString())
              .put(
                  "breakdown",
                  JSONArray(
                      listOf(
                          original
                              .getJSONArray("breakdown")
                              .getJSONObject(synthesis.days.indexOf(batch.single())))))
              .also { synthesis.validate(it, batch) }
        }
    assertEquals(synthesis.render(listOf(original)), synthesis.render(parts))
  }
}
