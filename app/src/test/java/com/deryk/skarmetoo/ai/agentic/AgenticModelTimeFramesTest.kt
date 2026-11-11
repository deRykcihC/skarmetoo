package com.deryk.skarmetoo.ai.agentic

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticModelTimeFramesTest {
  private val today = LocalDate.parse("2026-10-11")

  private fun frame(
      quote: String,
      start: String?,
      end: String?,
      kind: String = "period",
      order: String = "day_first"
  ) =
      JSONObject()
          .put("source_quote", quote)
          .put("kind", kind)
          .put("numeric_date_order", order)
          .put("start_date", start ?: JSONObject.NULL)
          .put("end_date", end ?: JSONObject.NULL)

  private fun raw(vararg frames: JSONObject) =
      JSONObject().put("frames", JSONArray(frames.toList())).put("entry_count", frames.size)

  private fun parse(question: String, vararg frames: JSONObject) =
      AgenticModelTimeFrames.parse(raw(*frames).toString(), question)

  private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

  @Test
  fun consumesAnchoredModelDatesWithoutReplacingThemWithToday() {
    val question = "yesterday and all 4days before that spending"
    val result =
        parse(
            question,
            frame("yesterday", "2026-10-10", "2026-10-10"),
            frame("all 4days before that", "2026-10-06", "2026-10-09", "range"))
    val windows = AgenticDates.resolve(result.filter, LocalDate.parse("2026-10-12"))
    assertEquals(LocalDate.parse("2026-10-06"), windows[1].start)
    assertEquals(LocalDate.parse("2026-10-10"), windows[1].endExclusive)
    val prompt =
        AgenticModelTimeFrames.prompt(
            question,
            JSONObject().put("today", "2026-10-11").put("timezone", "Asia/Singapore"),
            result)
    assertTrue(prompt.contains(question))
    assertTrue(prompt.contains("2026-10-11"))
    assertTrue(prompt.contains("2026-10-06"))
  }

  @Test
  fun rejectsPastSpanThatDoubleAppliesItsCountToTheAnchor() {
    val question = "past two days spending"
    val entry =
        frame("past two days", null, null, "range")
            .put(
                "relative",
                JSONObject()
                    .put("anchor_reference", "today")
                    .put("anchor_offset_days", -2)
                    .put("selection", "preceding_days")
                    .put("count", 2))
    rejected { AgenticModelTimeFrames.parseOperations(raw(entry).toString(), question, today) }
    entry.getJSONObject("relative").put("anchor_offset_days", 0)
    AgenticModelTimeFrames.parseOperations(raw(entry).toString(), question, today)
  }

  @Test
  fun rejectsSpanAnchorThatContradictsNamedSourceReference() {
    val question = "2days before yesterday spending"
    val entry =
        frame("2days before yesterday", null, null, "range")
            .put(
                "relative",
                JSONObject()
                    .put("anchor_reference", "today")
                    .put("anchor_offset_days", -2)
                    .put("selection", "preceding_days")
                    .put("count", 2))
    rejected {
      AgenticModelTimeFrames.parseOperations(
          raw(entry).toString(), question, LocalDate.parse("2026-10-12"))
    }
    entry
        .getJSONObject("relative")
        .put("anchor_reference", "yesterday")
        .put("anchor_offset_days", 0)
    val operations =
        AgenticModelTimeFrames.parseOperations(
            raw(entry).toString(), question, LocalDate.parse("2026-10-12"))
    val resolved =
        AgenticModelTimeFrames.executeOperations(operations, LocalDate.parse("2026-10-12"))
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2026-10-09", resolved.getString("start_date"))
    assertEquals("2026-10-10", resolved.getString("end_date"))
  }

  @Test
  fun separatesRelativeAnchorFromSingleDayOrSpanSelection() {
    fun result(shift: Int, selection: String, count: Int, reference: String = "today"): JSONObject {
      val entry =
          frame("requested period", null, null, "range")
              .put("reference_quote", "")
              .put("start_days_from_reference", JSONObject.NULL)
              .put("end_days_from_reference", JSONObject.NULL)
              .put(
                  "relative",
                  JSONObject()
                      .put("anchor_reference", reference)
                      .put("anchor_offset_days", shift)
                      .put("selection", selection)
                      .put("count", count))
      return AgenticModelTimeFrames.executeOperations(raw(entry), LocalDate.parse("2026-10-12"))
          .getJSONArray("frames")
          .getJSONObject(0)
    }
    assertEquals("2026-10-09", result(-1, "preceding_days", 2).getString("start_date"))
    assertEquals("2026-10-10", result(-1, "preceding_days", 2).getString("end_date"))
    assertEquals("2026-10-10", result(-2, "single_day", 1).getString("start_date"))
    assertEquals("2026-10-11", result(0, "preceding_days", 2).getString("end_date"))
    assertEquals("2026-10-09", result(0, "preceding_days", 2, "yesterday").getString("start_date"))
    assertEquals(
        "2026-10-10", result(0, "single_day", 1, "day_before_yesterday").getString("start_date"))
    rejected { result(-1, "preceding_days", 2, "yesterday") }
    rejected { result(-1, "single_day", 2) }
    rejected { result(0, "preceding_days", 0) }
  }

  @Test
  fun combinedNamedDayAndRollingSpanRemainSeparateRequests() {
    val question = "spending today and past 2 days"
    val day =
        frame("today", null, null)
            .put("day_selection", "all")
            .put("calendar", JSONObject.NULL)
            .put("rolling", JSONObject.NULL)
            .put("temporal_mode", "single_day")
            .put(
                "relative",
                JSONObject()
                    .put("anchor_reference", "today")
                    .put("anchor_offset_days", 0)
                    .put("selection", "single_day")
                    .put("count", 1))
    val span =
        frame("past 2 days", null, null, "range")
            .put("day_selection", "all")
            .put("calendar", JSONObject.NULL)
            .put("relative", JSONObject.NULL)
            .put("temporal_mode", "rolling")
            .put(
                "rolling",
                JSONObject()
                    .put("anchor", "today")
                    .put("direction", "past")
                    .put("duration_days", 2)
                    .put("include_anchor", false))
    val clock = LocalDate.parse("2026-10-15")
    day.put("period_id", "period_1")
    span.put("period_id", "period_2")
    span.getJSONObject("rolling").remove("anchor")
    span
        .getJSONObject("rolling")
        .put("anchor_spec", JSONObject().put("type", "named_day").put("value", "today"))
    val operations =
        AgenticModelTimeFrames.parsePlanningOperations(raw(day, span).toString(), question, clock)
    val result =
        AgenticModelTimeFrames.parse(
            AgenticModelTimeFrames.executeOperations(operations, clock).toString(), question)
    assertEquals(2, result.inventory.entries.size)
    assertEquals(LocalDate.parse("2026-10-15"), result.frames[0].start)
    assertEquals(LocalDate.parse("2026-10-13"), result.frames[1].start)
    assertEquals(LocalDate.parse("2026-10-14"), result.frames[1].endInclusive)
    val merged = JSONObject(span.toString()).put("source_quote", "today and past 2 days")
    merged.getJSONObject("rolling").put("duration_days", 3).put("include_anchor", true)
    rejected {
      AgenticModelTimeFrames.parsePlanningOperations(raw(merged).toString(), question, clock)
    }
  }

  @Test
  fun independentQuestionsKeepTheSimplerAnchorTransport() {
    val question = "spending today and past 2 days"
    assertFalse(AgenticModelTimeFrames.usesPeriodReferences(question))
    assertTrue(AgenticModelTimeFrames.usesPeriodReferences("yesterday and two days before that"))
    val properties =
        AgenticModelTimeFrames.schemaFor(question)
            .getJSONObject("properties")
            .getJSONObject("frames")
            .getJSONObject("items")
            .getJSONObject("properties")
    assertFalse(properties.has("period_id"))
    val rolling = properties.getJSONObject("rolling").getJSONObject("properties")
    assertTrue(rolling.has("anchor"))
    assertFalse(rolling.has("anchor_spec"))
    val entry =
        frame("past 2 days", null, null, "range")
            .put("calendar", JSONObject.NULL)
            .put("relative", JSONObject.NULL)
            .put("day_selection", "all")
            .put("temporal_mode", "rolling")
            .put(
                "rolling",
                JSONObject()
                    .put("anchor", "today")
                    .put("direction", "past")
                    .put("duration_days", 2)
                    .put("include_anchor", false))
    AgenticModelTimeFrames.parseQuestionOperations(raw(entry).toString(), "past 2 days", today)
  }

  @Test
  fun typedPeriodReferencesResolveEarlierPeriodsAndRejectInvalidLinks() {
    val question = "yesterday and all 2 days before that"
    val day =
        frame("yesterday", null, null)
            .put("period_id", "period_1")
            .put("day_selection", "all")
            .put("calendar", JSONObject.NULL)
            .put("rolling", JSONObject.NULL)
            .put("temporal_mode", "single_day")
            .put(
                "relative",
                JSONObject()
                    .put("anchor_reference", "yesterday")
                    .put("anchor_offset_days", 0)
                    .put("selection", "single_day")
                    .put("count", 1))
    val span =
        frame("all 2 days before that", null, null, "range")
            .put("period_id", "period_2")
            .put("day_selection", "all")
            .put("calendar", JSONObject.NULL)
            .put("relative", JSONObject.NULL)
            .put("temporal_mode", "rolling")
            .put(
                "rolling",
                JSONObject()
                    .put(
                        "anchor_spec",
                        JSONObject().put("type", "period_reference").put("value", "period_1"))
                    .put("direction", "past")
                    .put("duration_days", 2)
                    .put("include_anchor", false))
    val clock = LocalDate.parse("2026-10-15")
    val operations =
        AgenticModelTimeFrames.parsePlanningOperations(raw(day, span).toString(), question, clock)
    val resolved =
        AgenticModelTimeFrames.executeOperations(operations, clock).getJSONArray("frames")
    assertEquals("2026-10-14", resolved.getJSONObject(0).getString("start_date"))
    assertEquals("2026-10-12", resolved.getJSONObject(1).getString("start_date"))
    assertEquals("2026-10-13", resolved.getJSONObject(1).getString("end_date"))
    val misplaced = JSONObject(span.toString())
    misplaced.getJSONObject("rolling").put("period_id", misplaced.remove("period_id"))
    val normalized =
        AgenticModelTimeFrames.parsePlanningOperations(
            raw(day, misplaced).toString(), question, clock)
    assertEquals(
        "period_1",
        normalized
            .getJSONArray("frames")
            .getJSONObject(1)
            .getJSONObject("rolling")
            .getJSONObject("anchor_spec")
            .getString("value"))
    val review =
        JSONObject()
            .put(
                "references",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("period_id", "period_2")
                            .put(
                                "anchor_spec",
                                JSONObject()
                                    .put("type", "period_reference")
                                    .put("value", "period_1"))))
    AgenticModelTimeFrames.validateReferenceAgreement(
        operations, AgenticModelTimeFrames.parseReferenceAudit(review.toString(), operations))
    val prompt = AgenticModelTimeFrames.referenceAuditPrompt(question, operations)
    assertFalse(prompt.contains("anchor_spec\":{\"type\"")) // No candidate anchor JSON is exposed.
    for (target in listOf("period_2", "period_3", "missing")) {
      span.getJSONObject("rolling").getJSONObject("anchor_spec").put("value", target)
      rejected {
        AgenticModelTimeFrames.parsePlanningOperations(raw(day, span).toString(), question, clock)
      }
    }
    span
        .getJSONObject("rolling")
        .getJSONObject("anchor_spec")
        .put("type", "named_day")
        .put("value", "today")
    rejected {
      AgenticModelTimeFrames.parsePlanningOperations(raw(day, span).toString(), question, clock)
    }
    val wrong = JSONObject(operations.toString())
    wrong
        .getJSONArray("frames")
        .getJSONObject(1)
        .getJSONObject("rolling")
        .getJSONObject("anchor_spec")
        .put("type", "named_day")
        .put("value", "today")
    rejected { AgenticModelTimeFrames.validateReferenceAgreement(wrong, review) }
  }

  @Test
  fun semanticSpansSeparateDurationFromIncludingTheAnchorAndUseFreshDates() {
    fun span(
        include: Boolean,
        direction: String = "past",
        anchor: String = "today",
        count: Int = 2
    ) =
        raw(
            frame("requested span", null, null, "range")
                .put(
                    "rolling",
                    JSONObject()
                        .put("anchor", anchor)
                        .put("direction", direction)
                        .put("duration_days", count)
                        .put("include_anchor", include)))
    fun result(operations: JSONObject, clock: String) =
        AgenticModelTimeFrames.executeOperations(operations, LocalDate.parse(clock))
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2026-10-13", result(span(false), "2026-10-15").getString("start_date"))
    assertEquals("2026-10-14", result(span(false), "2026-10-15").getString("end_date"))
    assertEquals("2026-10-14", result(span(true), "2026-10-15").getString("start_date"))
    assertEquals("2026-10-15", result(span(true), "2026-10-15").getString("end_date"))
    assertEquals("2026-10-14", result(span(false), "2026-10-16").getString("start_date"))
    assertEquals("2026-09-29", result(span(false), "2026-10-01").getString("start_date"))
    assertEquals("2026-10-16", result(span(false, "future"), "2026-10-15").getString("start_date"))
    assertEquals("2026-10-15", result(span(true, "future"), "2026-10-15").getString("start_date"))
    assertEquals(
        "2026-10-12",
        result(span(false, anchor = "yesterday"), "2026-10-15").getString("start_date"))
    rejected { result(span(false, count = 0), "2026-10-15") }
    for (phrase in
        listOf("last 2 days", "past two days", "these 2 days", "this two days")) assertTrue(
        AgenticContracts.isTemporalConcept(phrase))
    // The production planning schema cannot encode the double-subtraction bug.
    val invalid =
        frame("last 2 days", null, null, "range")
            .put("calendar", JSONObject.NULL)
            .put("rolling", JSONObject.NULL)
            .put("temporal_mode", "single_day")
            .put("day_selection", "all")
            .put(
                "relative",
                JSONObject()
                    .put("anchor_reference", "today")
                    .put("anchor_offset_days", -2)
                    .put("selection", "preceding_days")
                    .put("count", 2))
    rejected {
      AgenticModelTimeFrames.parsePlanningOperations(raw(invalid).toString(), "last 2 days", today)
    }
  }

  @Test
  fun containingDateWeeksUseMondaySundayWithoutMonthClipping() {
    fun resolved(anchor: String, selection: String = "all"): ModelTimeFrames {
      val date = LocalDate.parse(anchor)
      val question = "week containing $anchor"
      val entry =
          frame(question, null, null, "range")
              .put("day_selection", selection)
              .put(
                  "calendar",
                  JSONObject()
                      .put("operation", "week_containing_date")
                      .put("anchor_date", anchor)
                      .put("year", date.year)
                      .put("month", date.monthValue)
                      .put("week", 0))
      return AgenticModelTimeFrames.parse(
          AgenticModelTimeFrames.executeOperations(raw(entry), today).toString(), question)
    }
    val october = resolved("2026-10-10").frames.single()
    assertEquals(LocalDate.parse("2026-10-05"), october.start)
    assertEquals(LocalDate.parse("2026-10-11"), october.endInclusive)
    val monthBoundary = resolved("2026-10-01").frames.single()
    assertEquals(LocalDate.parse("2026-09-28"), monthBoundary.start)
    assertEquals(LocalDate.parse("2026-10-04"), monthBoundary.endInclusive)
    val yearBoundary = resolved("2027-01-01").frames.single()
    assertEquals(LocalDate.parse("2026-12-28"), yearBoundary.start)
    assertEquals(LocalDate.parse("2027-01-03"), yearBoundary.endInclusive)
    assertEquals(
        LocalDate.parse("2026-10-10"),
        resolved("2026-10-10", "weekend").filter.resolvedWindows!!.single().start)
    rejected { resolved("2026-02-30") }
  }

  @Test
  fun weekendAndWeekdaySelectionsPreserveOneRequestAndFilterCalendarDates() {
    val question = "2nd week weekend of oct"
    fun operations(selection: String) =
        raw(
            frame(question, null, null, "range")
                .put("day_selection", selection)
                .put(
                    "calendar",
                    JSONObject()
                        .put("operation", "week_of_month")
                        .put("year", 2026)
                        .put("month", 10)
                        .put("week", 2)))
    rejected {
      AgenticModelTimeFrames.parseOperations(operations("all").toString(), question, today)
    }
    val result =
        AgenticModelTimeFrames.parse(
            AgenticModelTimeFrames.executeOperations(operations("weekend"), today).toString(),
            question)
    assertEquals(1, result.inventory.entries.size)
    assertEquals(LocalDate.parse("2026-10-10"), result.filter.resolvedWindows!!.single().start)
    assertEquals(
        LocalDate.parse("2026-10-12"), result.filter.resolvedWindows!!.single().endExclusive)
    val weekdays =
        AgenticModelTimeFrames.parse(
            AgenticModelTimeFrames.executeOperations(operations("weekdays"), today).toString(),
            question)
    assertEquals(LocalDate.parse("2026-10-05"), weekdays.filter.resolvedWindows!!.single().start)
    assertEquals(
        LocalDate.parse("2026-10-10"), weekdays.filter.resolvedWindows!!.single().endExclusive)
    val month =
        parse(
            "October weekends",
            frame("October weekends", "2026-10-01", "2026-10-31", "range")
                .put("day_selection", "weekend"))
    assertEquals(5, month.filter.resolvedWindows!!.size)
    val roundTrip = AgenticModelTimeFrames.parse(month.json().toString(), "October weekends")
    assertEquals(month.filter.resolvedWindows, roundTrip.filter.resolvedWindows)
    rejected { parse("October weekends", frame("October", "2026-10-01", "2026-10-31", "range")) }
    assertTrue(month.display().single().contains("03/10/2026 – 04/10/2026"))
  }

  @Test
  fun mondayCalendarWeeksAreClippedToMonthBoundaries() {
    fun calendar(month: Int, year: Int, week: Int) =
        raw(
            frame("last week of sep", null, null, "range")
                .put("reference_quote", "")
                .put("start_days_from_reference", JSONObject.NULL)
                .put("end_days_from_reference", JSONObject.NULL)
                .put(
                    "calendar",
                    JSONObject()
                        .put("operation", "week_of_month")
                        .put("year", year)
                        .put("month", month)
                        .put("week", week)))
    fun resolved(month: Int, year: Int, week: Int) =
        AgenticModelTimeFrames.executeOperations(calendar(month, year, week), today)
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2026-09-28", resolved(9, 2026, -1).getString("start_date"))
    assertEquals("2026-09-30", resolved(9, 2026, -1).getString("end_date"))
    assertEquals("2026-09-01", resolved(9, 2026, 1).getString("start_date"))
    assertEquals("2026-09-06", resolved(9, 2026, 1).getString("end_date"))
    assertEquals("2026-09-07", resolved(9, 2026, 2).getString("start_date"))
    assertEquals("2028-02-29", resolved(2, 2028, -1).getString("end_date"))
    rejected { resolved(9, 2026, 6) }
  }

  @Test
  fun calendarToolExecutesModelReferencesAcrossMonthBoundary() {
    fun operation(quote: String, reference: String, first: Int, last: Int, kind: String) =
        frame(quote, null, null, kind)
            .put("reference_quote", reference)
            .put("start_days_from_reference", first)
            .put("end_days_from_reference", last)
    val question = "day before yesterday and all 3days before that spending"
    val value =
        raw(
            operation("day before yesterday", "today", -2, -2, "period"),
            operation("all 3days before that", "day before yesterday", -3, -1, "range"))
    val result =
        AgenticModelTimeFrames.parse(
            AgenticModelTimeFrames.executeOperations(value, LocalDate.parse("2026-10-03"))
                .toString(),
            question)
    assertEquals(LocalDate.parse("2026-10-01"), result.frames[0].start)
    assertEquals(LocalDate.parse("2026-09-28"), result.frames[1].start)
    assertEquals(LocalDate.parse("2026-09-30"), result.frames[1].endInclusive)
    value.getJSONArray("frames").getJSONObject(0).put("end_days_from_reference", -1)
    rejected { AgenticModelTimeFrames.executeOperations(value, LocalDate.parse("2026-10-03")) }
  }

  @Test
  fun checksModelReferenceAndOffsetsBeforeAcceptingDates() {
    fun relative(
        quote: String,
        start: String,
        end: String,
        reference: String,
        anchor: String,
        first: Int,
        last: Int,
        kind: String = "period"
    ) =
        frame(quote, start, end, kind)
            .put("reference_quote", reference)
            .put("reference_date", anchor)
            .put("start_days_from_reference", first)
            .put("end_days_from_reference", last)
    val question = "yesterday and all 4days before that spending"
    val output =
        raw(
            relative("yesterday", "2026-10-11", "2026-10-11", "today", "2026-10-12", -1, -1),
            relative(
                "all 4days before that",
                "2026-10-07",
                "2026-10-10",
                "yesterday",
                "2026-10-11",
                -4,
                -1,
                "range"))
    assertEquals(
        LocalDate.parse("2026-10-07"),
        AgenticModelTimeFrames.parseReasoned(
                output.toString(), question, LocalDate.parse("2026-10-12"))
            .frames[1]
            .start)
    output.getJSONArray("frames").getJSONObject(1).put("start_date", "2026-10-03")
    rejected {
      AgenticModelTimeFrames.parseReasoned(
          output.toString(), question, LocalDate.parse("2026-10-12"))
    }
    output
        .getJSONArray("frames")
        .getJSONObject(1)
        .put("start_date", "2026-10-07")
        .put("reference_date", "2026-10-07")
    rejected {
      AgenticModelTimeFrames.parseReasoned(
          output.toString(), question, LocalDate.parse("2026-10-12"))
    }
  }

  @Test
  fun rejectsIncorrectInclusiveDayCountForModelSelfCorrection() {
    rejected {
      parse(
          "yesterday and all 4days before that spending",
          frame("yesterday", "2026-10-11", "2026-10-11"),
          frame("all 4days before that", "2026-10-08", "2026-10-10", "range"))
    }
  }

  @Test
  fun modelExtractedTimeCannotLeakIntoSemanticVerification() {
    val result =
        parse(
            "spending past day and past week?",
            frame("past day", "2026-10-10", "2026-10-10"),
            frame("past week?", "2026-10-04", "2026-10-10"))
    assertTrue(result.containsRequestedTime("past day"))
    assertTrue(AgenticContracts.isTemporalConcept("Does this image show spending in the past day?"))
    assertTrue(result.containsRequestedTime("Does this image show spending in the past week?"))
    assertFalse(result.containsRequestedTime("Does this image show a completed outgoing payment?"))
    val month = parse("spending May", frame("May", "2026-05-01", "2026-05-31"))
    assertFalse(month.containsRequestedTime("Maybank"))
  }

  @Test
  fun consumesTwoModelGeneratedPeriodsWithoutPhraseResolution() {
    val result =
        parse(
            "what is my spending past day and past week?",
            frame("past day", "2026-10-10", "2026-10-10"),
            frame("past week", "2026-10-04", "2026-10-10"))
    val windows = AgenticDates.resolve(result.filter, today)
    assertEquals(listOf("2026-10-10", "2026-10-04"), windows.map { it.start.toString() })
    assertEquals(listOf("2026-10-11", "2026-10-11"), windows.map { it.endExclusive.toString() })
    assertEquals(
        listOf("past day: 10/10/2026", "past week: 04/10/2026 – 10/10/2026"),
        result.inventory.display(windows))
    assertEquals(windows, AgenticDates.resolve(result.inventory.bind(result.filter), today))
    assertEquals(
        result,
        AgenticModelTimeFrames.parse(
            result.json().toString(), "what is my spending past day and past week?"))
  }

  @Test
  fun acceptsUnfamiliarModelInterpretedLanguageAndOpenRanges() {
    val result =
        parse(
            "spending during the weekend before my trip",
            frame("the weekend before my trip", "2026-09-19", "2026-09-20"))
    assertEquals(
        LocalDate.parse("2026-09-19"), AgenticDates.resolve(result.filter, today).single().start)
    val open = parse("payments before Oct 5", frame("before Oct 5", null, "2026-10-04", "range"))
    assertNull(AgenticDates.resolve(open.filter, today).single().start)
    assertEquals(
        LocalDate.parse("2026-10-05"),
        AgenticDates.resolve(open.filter, today).single().endExclusive)
  }

  @Test
  fun overlapAndRepeatedFramesDoNotDoubleCountPayments() {
    val result =
        parse(
            "past day and past week spending",
            frame("past day", "2026-10-10", "2026-10-10"),
            frame("past week", "2026-10-04", "2026-10-10"))
    fun transaction(id: String, date: String, amount: String) =
        VerifiedTransaction(
            id,
            listOf(id),
            BigDecimal(amount),
            "MYR",
            LocalDate.parse(date),
            "printed",
            "Store",
            PaymentStatus.completed,
            Match.YES,
            false)
    val calculation =
        AgenticCalculation.calculate(
            listOf(
                transaction("a", "2026-10-10", "5.72"),
                transaction("b", "2026-10-07", "7.82"),
                transaction("c", "2026-10-03", "9.00")),
            result.filter.resolvedWindows!!)
    assertEquals(BigDecimal("13.54"), calculation.totals.getValue("MYR"))
    assertEquals(2, calculation.transactions.size)
  }

  @Test
  fun rejectsMalformedDatesReversedRangesMissingKnownPeriodsAndChangedQuotes() {
    rejected { parse("spending last week") }
    rejected { parse("spending Oct 5 and Oct 1", frame("Oct 5", "2026-10-05", "2026-10-05")) }
    rejected { parse("spending yesterday", frame("yesterday", "yesterday", "2026-10-10")) }
    rejected { parse("spending yesterday", frame("yesterday", "2026-02-30", "2026-02-30")) }
    rejected { parse("spending yesterday", frame("yesterday", null, null)) }
    rejected { parse("spending yesterday", frame("yesterday", "2026-10-11", "2026-10-10")) }
    rejected { parse("spending yesterday", frame("last week", "2026-10-10", "2026-10-10")) }
    rejected {
      parse(
          "past day and past week spending",
          frame("past day and past week", "2026-10-04", "2026-10-10"))
    }
    rejected {
      parse(
          "Oct 5, Oct 1 spending",
          frame("Oct 1", "2026-10-01", "2026-10-01"),
          frame("Oct 5", "2026-10-05", "2026-10-05"))
    }
    rejected {
      AgenticModelTimeFrames.parse(
          raw(frame("yesterday", "2026-10-10", "2026-10-10")).put("entry_count", 2).toString(),
          "spending yesterday")
    }
  }

  @Test
  fun retainsStandaloneDatesRangesMonthsAndContextualNumericOrders() {
    val result =
        parse(
            "Oct 5, 10/2, Sep 28 till 29, September spending",
            frame("Oct 5", "2026-10-05", "2026-10-05"),
            frame("10/2", "2026-10-02", "2026-10-02", order = "month_first"),
            frame("Sep 28 till 29", "2026-09-28", "2026-09-29", "range"),
            frame("September", "2026-09-01", "2026-09-30"))
    assertEquals(4, AgenticDates.resolve(result.filter, today).size)
    rejected {
      parse("spending 10/2", frame("10/2", "2026-10-02", "2026-10-02", order = "month_first"))
    }
    assertEquals(0, AgenticDates.resolve(parse("what have I spent?").filter, today).size)
    val namedDate =
        parse(
            "spending September 30, 2026", frame("September 30, 2026", "2026-09-30", "2026-09-30"))
    assertEquals(1, namedDate.frames.size)
  }
}
