package com.deryk.skarmetoo.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class ChatDateWindow(
    val start: LocalDate? = null,
    val endExclusive: LocalDate? = null,
    val spans: List<IntRange> = emptyList(),
    val error: String? = null,
)

/** Resolves calendar language once, before retrieval; end dates are inclusive to the user. */
object ChatDateWindows {
  private val monthNumbers =
      listOf(
              listOf("january", "jan"),
              listOf("february", "feb"),
              listOf("march", "mar"),
              listOf("april", "apr"),
              listOf("may"),
              listOf("june", "jun"),
              listOf("july", "jul"),
              listOf("august", "aug"),
              listOf("september", "sept", "sep"),
              listOf("october", "oct"),
              listOf("november", "nov"),
              listOf("december", "dec"))
          .flatMapIndexed { i, names -> names.map { it to i + 1 } }
          .toMap()
  private val months = monthNumbers.keys.sortedByDescending { it.length }.joinToString("|")

  private data class Day(val day: Int, val month: Int, val year: Int?, val span: IntRange)

  private data class Month(val month: YearMonth, val span: IntRange)

  private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last

  private fun invalid(spans: List<IntRange> = emptyList()) =
      ChatDateWindow(
          spans = spans,
          error =
              "Please use a valid date or an ordered date range. Add a year or use YYYY-MM-DD if the date is ambiguous.")

  private fun days(text: String, order: ChatReceiptDateOrder): List<Day> {
    val result = mutableListOf<Day>()
    fun add(regex: String, read: (MatchResult) -> Day) {
      Regex(regex, RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
        if (result.none { overlaps(it.span, match.range) }) result += read(match)
      }
    }
    add("""(?<![\p{L}\p{N}])(\d{4})-(\d{2})-(\d{2})(?![\p{L}\p{N}])""") {
      Day(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt(), it.range)
    }
    add("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($months)\.?(?:\s*,?\s*(\d{4}))?\b""") {
      Day(
          it.groupValues[1].toInt(),
          monthNumbers.getValue(it.groupValues[2].lowercase()),
          it.groupValues[3].toIntOrNull(),
          it.range)
    }
    add("""\b($months)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s*,?\s*(\d{4}))?\b""") {
      Day(
          it.groupValues[2].toInt(),
          monthNumbers.getValue(it.groupValues[1].lowercase()),
          it.groupValues[3].toIntOrNull(),
          it.range)
    }
    add("""(?<![\p{L}\p{N}/-])(\d{1,2})[/-](\d{1,2})(?:[/-](\d{4}))?(?![\p{L}\p{N}/-])""") {
      val a = it.groupValues[1].toInt()
      val b = it.groupValues[2].toInt()
      val pair =
          when {
            order == ChatReceiptDateOrder.DAY_FIRST -> a to b
            order == ChatReceiptDateOrder.MONTH_FIRST -> b to a
            a > 12 -> a to b
            b > 12 -> b to a
            a == b -> a to b
            else -> 0 to 0
          }
      Day(pair.first, pair.second, it.groupValues[3].toIntOrNull(), it.range)
    }
    return result.sortedBy { it.span.first }
  }

  /** Stored descriptions may contain an event date even when the image was imported later. */
  fun explicitDates(text: String, order: ChatReceiptDateOrder): List<LocalDate> =
      days(text, order)
          .mapNotNull { day ->
            day.year?.let { year ->
              runCatching { LocalDate.of(year, day.month, day.day) }.getOrNull()
            }
          }
          .distinct()

  private fun monthRefs(text: String, today: LocalDate): List<Month> {
    val result = mutableListOf<Month>()
    fun add(regex: String, read: (MatchResult) -> YearMonth?) {
      Regex(regex, RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
        if (result.none { overlaps(it.span, match.range) })
            read(match)?.let { result += Month(it, match.range) }
      }
    }
    add("""\b(\d{4})\s+($months)\b""") {
      YearMonth.of(it.groupValues[1].toInt(), monthNumbers.getValue(it.groupValues[2].lowercase()))
    }
    add("""\b($months)(?:\s+(?:of\s+)?(\d{4}))?\b""") {
      if (it.value.equals("may", true) &&
          text.substring(it.range.last + 1).trimStart().startsWith("i "))
          null
      else
          YearMonth.of(
              it.groupValues[2].toIntOrNull() ?: today.year,
              monthNumbers.getValue(it.groupValues[1].lowercase()))
    }
    add("""(?<![\p{L}\p{N}-])(\d{4})-(\d{2})(?![-\d])""") {
      runCatching { YearMonth.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
    }
    add("""(?<![\p{L}\p{N}/])(\d{1,2})/(\d{4})(?![/\d])""") {
      runCatching { YearMonth.of(it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
    }
    return result.sortedBy { it.span.first }
  }

  fun resolve(text: String, today: LocalDate, order: ChatReceiptDateOrder): ChatDateWindow {
    // "May I ..." is a request, not a reference to the month of May. Keep character positions.
    val q =
        text.lowercase().replace(Regex("""^\s*may(?=\s+(?:i|you|we)\b)""")) {
          " ".repeat(it.value.length)
        }
    val window = resolveWindow(q, today, order)
    if (window.error != null) return window
    var remaining =
        q.toCharArray()
            .also { chars -> window.spans.forEach { span -> for (i in span) chars[i] = ' ' } }
            .concatToString()
    // Preserve the existing explicit-range override and rolling-week "including today" wording.
    if (days(q, order).size == 2)
        remaining =
            remaining.replace(
                Regex("""\b(?:last|past|previous)(?:\s+last)?\s+(?:calendar\s+)?week\b"""), " ")
    if (window.endExclusive == today.plusDays(1))
        remaining = remaining.replace(Regex("""\bincluding\s+today\b"""), " ")
    val unmatched =
        Regex(
            """\b(last|past|previous|next|ago|years?|weeks?|months?|yesterday|today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|$months)\b|\d{4}[-/]\d{2}|\d{1,2}[/-]\d{1,2}""")
    return if (unmatched.containsMatchIn(remaining)) invalid(window.spans) else window
  }

  private fun resolveWindow(
      text: String,
      today: LocalDate,
      order: ChatReceiptDateOrder
  ): ChatDateWindow {
    val q = text.lowercase()
    val dates = days(q, order)
    if (dates.isNotEmpty()) {
      if (dates.size > 2) return invalid(dates.map { it.span })
      val lastYear = dates.last().year
      val firstYear = dates.first().year
      val actual =
          dates.mapIndexed { i, day ->
            var year = day.year ?: lastYear ?: firstYear ?: today.year
            if (dates.size == 2 && dates.first().month > dates.last().month) {
              if (i == 0 && day.year == null && firstYear == null) year -= 1
              else if (i == 1 && day.year == null && firstYear != null) year += 1
            }
            runCatching { LocalDate.of(year, day.month, day.day) }.getOrNull()
          }
      if (actual.any { it == null }) return invalid(dates.map { it.span })
      val start = requireNotNull(actual.first())
      if (dates.size == 2) {
        val between = q.substring(dates.first().span.last + 1, dates.last().span.first)
        val rangeWord =
            Regex("""\b(to|through|until)\b|[–—-]""").containsMatchIn(between) ||
                (Regex("""\bbetween\b""").containsMatchIn(q.take(dates.first().span.first)) &&
                    Regex("""\band\b""").containsMatchIn(between))
        val end = requireNotNull(actual.last()).plusDays(1)
        return if (!rangeWord || !end.isAfter(start)) invalid(dates.map { it.span })
        else ChatDateWindow(start, end, dates.map { it.span })
      }
      val prefix = q.take(dates.first().span.first)
      val week = Regex("""\bweek\s+(?:of|starting|beginning|commencing)\s*$""").find(prefix)
      if (week != null)
          return ChatDateWindow(start, start.plusDays(7), listOf(week.range, dates.first().span))
      if (Regex("""\b(since|before|after|until|between)\s*$""").containsMatchIn(prefix))
          return invalid(dates.map { it.span })
      return ChatDateWindow(start, start.plusDays(1), dates.map { it.span })
    }

    val currentMonth = YearMonth.from(today)
    val numberedWeek =
        Regex(
                """\b(?:week\s+([1-5])(?:st|nd|rd|th)?|(first|second|third|fourth|fifth|last|[1-5](?:st|nd|rd|th))\s+week)\b""")
            .find(q)
    val relativeMonth =
        Regex("""\b(last|past|previous|this)\s+month\b""").find(q)
            ?: if (numberedWeek != null) Regex("""\b(?:of|in)\s+the\s+(month)\b""").find(q)
            else null
    val named = monthRefs(q, today)
    if (named.size > 2 || (named.isNotEmpty() && relativeMonth != null)) return invalid()
    var selected =
        if (relativeMonth != null) {
          if (relativeMonth.groupValues[1] in listOf("this", "month")) currentMonth
          else currentMonth.minusMonths(1)
        } else named.firstOrNull()?.month
    val monthSpans =
        if (relativeMonth != null) listOf(relativeMonth.range) else named.map { it.span }
    if (selected != null) {
      val firstNamed = named.firstOrNull()
      val qualifier =
          firstNamed?.let { Regex("""\b(last|previous|this)\s+$""").find(q.take(it.span.first)) }
      if (qualifier?.groupValues?.get(1) in listOf("last", "previous") && selected >= currentMonth)
          selected = selected.minusYears(1)
      val week = numberedWeek
      if (week != null) {
        if (named.size > 1) return invalid()
        val monthStart = monthSpans.first().first
        if (week.range.last >= monthStart ||
            !Regex("""\s*(?:(?:of|in)\s+)?(?:the\s+)?""")
                .matches(q.substring(week.range.last + 1, monthStart)))
            return invalid()
        val n =
            week.groupValues[1].toIntOrNull()
                ?: when (week.groupValues[2]) {
                  "first",
                  "1st" -> 1
                  "second",
                  "2nd" -> 2
                  "third",
                  "3rd" -> 3
                  "fourth",
                  "4th" -> 4
                  "fifth",
                  "5th" -> 5
                  else -> (selected.lengthOfMonth() + 6) / 7
                }
        val day = 1 + (n - 1) * 7
        if (day > selected.lengthOfMonth()) return invalid()
        val start = selected.atDay(day)
        val end = minOf(start.plusDays(7), selected.plusMonths(1).atDay(1))
        return ChatDateWindow(
            start, end, monthSpans + listOf(week.range) + listOfNotNull(qualifier?.range))
      }
      if (named.size == 2) {
        val between = q.substring(named[0].span.last + 1, named[1].span.first)
        val end = named[1].month.plusMonths(1).atDay(1)
        if (!Regex("""\b(to|through|until|and)\b|[–—-]""").containsMatchIn(between) ||
            !end.isAfter(selected.atDay(1)))
            return invalid()
        return ChatDateWindow(selected.atDay(1), end, monthSpans)
      }
      val end =
          if (relativeMonth?.groupValues?.get(1) == "this") today.plusDays(1)
          else selected.plusMonths(1).atDay(1)
      return ChatDateWindow(selected.atDay(1), end, monthSpans + listOfNotNull(qualifier?.range))
    }
    val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun relative(regex: String, start: LocalDate, end: LocalDate): ChatDateWindow? =
        Regex(regex).find(q)?.let { ChatDateWindow(start, end, listOf(it.range)) }
    relative("""\b(?:last|previous)\s+calendar\s+week\b""", currentWeek.minusWeeks(1), currentWeek)
        ?.let {
          return it
        }
    relative(
            """\b(?:last|past|previous)(?:\s+last)?\s+week\b""",
            today.minusDays(7),
            today.plusDays(1))
        ?.let {
          return it
        }
    relative("""\b(?:last|past)\s+7\s+days\b""", today.minusDays(6), today.plusDays(1))?.let {
      return it
    }
    relative("""\b(?:last|past)\s+30\s+days\b""", today.minusDays(29), today.plusDays(1))?.let {
      return it
    }
    relative("""\byesterday\b""", today.minusDays(1), today)?.let {
      return it
    }
    relative("""\btomorrow\b""", today.plusDays(1), today.plusDays(2))?.let {
      return it
    }
    relative("""\btoday\b""", today, today.plusDays(1))?.let {
      return it
    }
    relative("""\bthis\s+week\b""", currentWeek, today.plusDays(1))?.let {
      return it
    }
    val weekday =
        Regex(
                """\b(?:(last|previous|this)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
            .find(q)
    if (weekday != null) {
      val day = DayOfWeek.valueOf(weekday.groupValues[2].uppercase())
      val date =
          when (weekday.groupValues[1]) {
            "last",
            "previous" -> today.with(TemporalAdjusters.previous(day))
            "this" -> currentWeek.plusDays(day.value.toLong() - 1)
            else -> today.with(TemporalAdjusters.previousOrSame(day))
          }
      return ChatDateWindow(date, date.plusDays(1), listOf(weekday.range))
    }
    val unknown =
        Regex("""\b(last|past|previous|next|ago|year|week|month|$months)\b|\d{4}[-/]\d{2}""")
            .containsMatchIn(q)
    return if (unknown) invalid() else ChatDateWindow()
  }
}
