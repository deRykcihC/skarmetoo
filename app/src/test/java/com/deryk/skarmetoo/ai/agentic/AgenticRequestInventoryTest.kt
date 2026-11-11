package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgenticRequestInventoryTest {
  private val today = LocalDate.parse("2026-10-11")

  private fun entry(quote: String, kind: String = "period", order: String = "day_first") =
      JSONObject().put("source_quote", quote).put("kind", kind).put("numeric_date_order", order)

  private fun raw(vararg entries: JSONObject) =
      JSONObject().put("entry_count", entries.size).put("entries", JSONArray(entries.toList()))

  private fun inventory(question: String, vararg entries: JSONObject) =
      AgenticRequestInventory.parse(raw(*entries).toString(), question)

  private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

  @Test
  fun threeMixedDatesRequireThreeIndependentClausesAndExposeInterpretation() {
    val question = "oct 5, oct 1, 10/2 spending"
    val scope =
        inventory(question, entry("oct 5"), entry("oct 1"), entry("10/2", order = "month_first"))
    val expressions = listOf("October 5", "October 1", "10/2")
    AgenticRequestInventory.validatePlan(scope, expressions, listOf("period", "period", "period"))
    val windows = AgenticDates.resolve(scope.bind(DateFilter(expressions)), today)
    assertEquals(
        listOf("2026-10-05", "2026-10-01", "2026-10-02"), windows.map { it.start.toString() })
    assertEquals(
        listOf("oct 5: 05/10/2026", "oct 1: 01/10/2026", "10/2: 02/10/2026"),
        scope.display(windows))
    rejected { AgenticRequestInventory.validatePlan(scope, listOf("October 5", "10/2")) }
    rejected {
      AgenticRequestInventory.validatePlan(scope, listOf("October 5", "October 2", "10/2"))
    }
    rejected { inventory(question, entry("oct 5"), entry("10/2")) }
  }

  @Test
  fun twoTillRangesRemainTwoEntriesWithTheirOwnEndpoints() {
    val question = "oct 5 till oct 7 and sep 28 till 29 spending"
    val scope =
        inventory(question, entry("oct 5 till oct 7", "range"), entry("sep 28 till 29", "range"))
    val expressions = listOf("from October 5 to October 7", "from September 28 to September 29")
    AgenticRequestInventory.validatePlan(scope, expressions, listOf("range", "range"))
    val windows = AgenticDates.resolve(scope.bind(DateFilter(expressions)), today)
    assertEquals(listOf("2026-10-08", "2026-09-30"), windows.map { it.endExclusive.toString() })
    assertEquals("sep 28 till 29: 28/09/2026 – 29/09/2026", scope.display(windows)[1])
    rejected { AgenticRequestInventory.validatePlan(scope, listOf("Oct 5", "Sep 28")) }
    rejected {
      AgenticRequestInventory.validatePlan(
          scope, listOf(expressions[0], "from September 28 to October 29"))
    }
    rejected {
      inventory(question, entry("oct 5 till oct 7", "period"), entry("sep 28 till 29", "range"))
    }
  }

  @Test
  fun fullRangeCannotBeSplitAndStandaloneDayCannotBeAbsorbed() {
    val question = "Oct 5 and Oct 5 to Oct 7 spending"
    val scope = inventory(question, entry("Oct 5"), entry("Oct 5 to Oct 7", "range"))
    AgenticRequestInventory.validatePlan(scope, listOf("Oct 5", "from Oct 5 to Oct 7"))
    rejected { inventory(question, entry("Oct 5 to Oct 7", "range")) }
    rejected { inventory(question, entry("Oct 5"), entry("Oct 5"), entry("Oct 7")) }
  }

  @Test
  fun repeatedDatesPreserveEachRequestWhileUnionsStillCountOnce() {
    val question = "Oct 5, Oct 5 spending"
    val scope = inventory(question, entry("Oct 5"), entry("Oct 5"))
    rejected { inventory(question, entry("Oct 5")) }
    AgenticRequestInventory.validatePlan(scope, listOf("Oct 5", "Oct 5"))
    assertTrue(
        AgenticDates.includes(
            LocalDate.parse("2026-10-05"),
            AgenticDates.resolve(scope.bind(DateFilter(listOf("Oct 5", "Oct 5"))), today))!!)
  }

  @Test
  fun relativePeriodsAndNamedMonthAreIndividualEntries() {
    val question = "spending last week and first week of September and October"
    val scope =
        inventory(question, entry("last week"), entry("first week of September"), entry("October"))
    AgenticRequestInventory.validatePlan(scope, scope.entries.map { it.sourceQuote })
    assertEquals(
        3,
        AgenticDates.resolve(scope.bind(DateFilter(scope.entries.map { it.sourceQuote })), today)
            .size)
  }

  @Test
  fun relativeOnlyQuestionsCannotLoseTheirTimeRestriction() {
    listOf(
            "what is my spending last week " to "last week",
            "show my purchases last month" to "last month",
            "what did I pay yesterday" to "yesterday")
        .forEach { (question, phrase) ->
          rejected { inventory(question) }
          val scope = inventory(question, entry(phrase))
          AgenticRequestInventory.validatePlan(scope, listOf(phrase))
          assertEquals(1, AgenticDates.resolve(scope.bind(DateFilter(listOf(phrase))), today).size)
        }
    rejected { inventory("spending last week and yesterday", entry("last week")) }
  }

  @Test
  fun allTimeDoesNotInventAnyDateEntries() {
    val scope = inventory("how much have I paid?")
    assertTrue(scope.entries.isEmpty())
    AgenticRequestInventory.validatePlan(scope, emptyList())
    rejected { inventory("how much have I paid?", entry("today")) }
  }

  @Test
  fun mismatchedCountInventedQuotesOrChangedOrderAreRejected() {
    val question = "Oct 5, Oct 1 spending"
    rejected {
      AgenticRequestInventory.parse(
          raw(entry("Oct 5"), entry("Oct 1")).put("entry_count", 3).toString(), question)
    }
    rejected { inventory(question, entry("October 5"), entry("Oct 1")) }
    rejected { inventory(question, entry("Oct 1"), entry("Oct 5")) }
    rejected { inventory(question, entry("Oct 5, Oct 1")) }
  }

  @Test
  fun sameCountCannotChangeKindsOrReorderTheDates() {
    val scope = inventory("Oct 5 and Oct 7 spending", entry("Oct 5"), entry("Oct 7"))
    rejected {
      AgenticRequestInventory.validatePlan(
          scope, listOf("Oct 5", "Oct 7"), listOf("range", "period"))
    }
    rejected { AgenticRequestInventory.validatePlan(scope, listOf("Oct 7", "Oct 5")) }
  }

  @Test
  fun numericDatesAcceptBothUnambiguousOrdersShortYearsAndIso() {
    val filter = DateFilter(listOf("10/2", "02/10/26", "09/29/2026", "29/09/2026", "2026-10-05"))
    assertEquals(
        listOf("2026-02-10", "2026-10-02", "2026-09-29", "2026-09-29", "2026-10-05"),
        AgenticDates.resolve(filter, today).map { it.start.toString() })
    val order = ChatReceiptDateOrder.MONTH_FIRST
    assertEquals(
        "2026-09-29",
        AgenticDates.resolve(DateFilter(listOf("29/09/26"), listOf(order)), today)
            .single()
            .start
            .toString())
    rejected { AgenticDates.resolve(DateFilter(listOf("31/02/26")), today) }
  }

  @Test
  fun modelMustUseDayFirstWithoutDisambiguatingNamedMonth() {
    rejected { inventory("spending 10/2", entry("10/2", order = "month_first")) }
    rejected { inventory("spending 02/10/26", entry("02/10/26", order = "month_first")) }
    rejected {
      inventory(
          "February and October and 10/2 spending",
          entry("February"),
          entry("October"),
          entry("10/2", order = "month_first"))
    }
    assertEquals(
        ChatReceiptDateOrder.DAY_FIRST,
        inventory("spending 10/2", entry("10/2")).entries.single().numericOrder)
    assertEquals(
        ChatReceiptDateOrder.MONTH_FIRST,
        inventory("Oct 5 and 10/2 spending", entry("Oct 5"), entry("10/2", order = "month_first"))
            .entries
            .last()
            .numericOrder)
  }

  @Test
  fun auditMustHonorInventoryBeforeCalendarResolution() {
    val question = "Oct 5, Oct 1, 10/2 spending"
    val scope =
        inventory(question, entry("Oct 5"), entry("Oct 1"), entry("10/2", order = "month_first"))
    val earlier =
        QueryPlan(
            Intent.unknown,
            Operation.retrieve,
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            "Does this image show payment?",
            Operation.retrieve)
    fun audit(dates: List<String>) =
        JSONObject()
            .put("normalized_question", question)
            .put("intent", "calculate_spending")
            .put("operation", "sum")
            .put("action_quote", "")
            .put("subject_phrase", "")
            .put("search_keywords", JSONArray(listOf("payment")))
            .put(
                "date_clauses",
                JSONArray(
                    dates.map {
                      JSONObject()
                          .put("kind", "period")
                          .put("start", it)
                          .put("end", JSONObject.NULL)
                    }))
    val plan =
        AgenticPlanParser.parse(
            audit(listOf("Oct 5", "Oct 1", "10/2")).toString(), question, today, earlier, scope)
    assertEquals("2026-10-02", AgenticDates.resolve(plan.dateFilter, today).last().start.toString())
    rejected {
      AgenticPlanParser.parse(
          audit(listOf("Oct 5", "10/2")).toString(), question, today, earlier, scope)
    }
  }
}
