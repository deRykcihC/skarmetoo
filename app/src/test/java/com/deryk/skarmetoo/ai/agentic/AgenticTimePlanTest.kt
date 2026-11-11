package com.deryk.skarmetoo.ai.agentic

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticTimePlanTest {
  private val today = LocalDate.parse("2026-10-11")

  private fun entry(quote: String, mode: String, offset: Int = 0, days: Int = 0) =
      JSONObject()
          .put("source_quote", quote)
          .put("mode", mode)
          .put("offset", offset)
          .put("days", days)
          .put("include_today", false)
          .put("numeric_date_order", "day_first")

  private fun raw(vararg values: JSONObject) =
      JSONObject().put("entries", JSONArray(values.toList())).put("entry_count", values.size)

  @Test
  fun executesModelSelectedRollingAndCalendarModesWithoutReadingQuestion() {
    val plan =
        raw(
            entry("past day", "rolling_days", days = 1),
            entry("past week", "rolling_days", days = 7))
    AgenticTimePlan.parse(plan.toString(), "spending past day and past week?", today)
    val rolling = AgenticTimePlan.resolved(plan, today).getJSONArray("frames")
    assertEquals("2026-10-10", rolling.getJSONObject(0).getString("start_date"))
    assertEquals("2026-10-04", rolling.getJSONObject(1).getString("start_date"))
    assertEquals("2026-10-10", rolling.getJSONObject(1).getString("end_date"))
    val previous =
        AgenticTimePlan.resolved(raw(entry("last week", "calendar_week", offset = -1)), today)
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2026-09-28", previous.getString("start_date"))
    assertEquals("2026-10-04", previous.getString("end_date"))
    val arbitrary =
        AgenticTimePlan.resolved(
                raw(entry("an unfamiliar phrase", "rolling_days", days = 13)), today)
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2026-09-28", arbitrary.getString("start_date"))
  }

  @Test
  fun calendarMonthUsesLeapYearAndExplicitDatesPreserveSourceOrder() {
    val leap =
        AgenticTimePlan.resolved(
                raw(entry("last month", "calendar_month", offset = -1)),
                LocalDate.parse("2024-03-01"))
            .getJSONArray("frames")
            .getJSONObject(0)
    assertEquals("2024-02-01", leap.getString("start_date"))
    assertEquals("2024-02-29", leap.getString("end_date"))
    val values = raw(entry("Oct 5", "explicit"), entry("Oct 1", "explicit"))
    assertEquals(
        values.toString(),
        AgenticTimePlan.parse(values.toString(), "spending Oct 5 and Oct 1", today).toString())
    assertTrue(
        runCatching {
              AgenticTimePlan.parse(
                  raw(values.getJSONArray("entries").getJSONObject(0)).toString(),
                  "spending Oct 5 and Oct 1",
                  today)
            }
            .isFailure)
    assertEquals(
        0, AgenticTimePlan.parse(raw().toString(), "my spending", today).getInt("entry_count"))
  }

  @Test
  fun identifiesExplicitEntriesThenRequiresFocusedModelDatesBeforeExecution() {
    val question = "Oct 5, Oct 1, 10/2 spending"
    val plan =
        AgenticTimePlan.parse(
            raw(entry("Oct 5", "explicit"), entry("Oct 1", "explicit"), entry("10/2", "explicit"))
                .toString(),
            question,
            today)
    assertEquals(3, plan.getInt("entry_count"))
    assertTrue(runCatching { AgenticTimePlan.resolved(plan, today) }.isFailure)
    val entry = plan.getJSONArray("entries").getJSONObject(2)
    val reply =
        JSONObject()
            .put("numeric_date_order", "month_first")
            .put("start_date", "2026-10-02")
            .put("end_date", "2026-10-02")
    val resolved = AgenticExplicitDates.parse(reply.toString(), entry, question, today)
    assertEquals("2026-10-02", resolved.getString("start_date"))
    assertTrue(
        runCatching {
              AgenticExplicitDates.parse(
                  reply
                      .put("start_date", JSONObject.NULL)
                      .put("end_date", JSONObject.NULL)
                      .toString(),
                  entry,
                  question,
                  today)
            }
            .isFailure)
  }

  @Test
  fun rejectsUnsupportedParametersAndIgnoresUnusedRelativeParameters() {
    assertTrue(
        runCatching {
              AgenticTimePlan.parse(
                  raw(entry("past week", "rolling_days", days = 0)).toString(),
                  "spending past week",
                  today)
            }
            .isFailure)
    val relative =
        AgenticTimePlan.parse(
            raw(entry("past week", "rolling_days", days = 7)).toString(),
            "spending past week",
            today)
    assertTrue(relative.getJSONArray("entries").getJSONObject(0).isNull("start_date"))
    assertEquals(
        "2026-10-04",
        AgenticTimePlan.resolved(relative, today)
            .getJSONArray("frames")
            .getJSONObject(0)
            .getString("start_date"))
    val calendar =
        AgenticTimePlan.parse(
            raw(entry("last week", "calendar_week", offset = -1, days = 7)).toString(),
            "spending last week",
            today)
    assertEquals(0, calendar.getJSONArray("entries").getJSONObject(0).getInt("days"))
    assertEquals(
        "2026-09-28",
        AgenticTimePlan.resolved(calendar, today)
            .getJSONArray("frames")
            .getJSONObject(0)
            .getString("start_date"))
    assertTrue(
        runCatching {
              AgenticTimePlan.parse(
                  raw(
                          entry("Oct 5", "explicit")
                              .put("start_date", "2026-10-05")
                              .put("end_date", "2026-10-04"))
                      .toString(),
                  "spending Oct 5",
                  today)
            }
            .isFailure)
  }
}
