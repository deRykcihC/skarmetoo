package com.deryk.skarmetoo.ai.agentic

/** Validates model-produced clauses against source text; never supplies a replacement plan. */
object AgenticQueryCoverage {
  private const val months =
      "(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)"
  private const val date =
      "(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/-]\\d{1,2}(?:[/-](?:\\d{4}|\\d{2}))?|\\d{1,2}(?:st|nd|rd|th)?\\s+$months(?:\\s+\\d{4})?|$months\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?)"
  private val atoms =
      Regex(
          "(?i)(?<![\\p{L}\\p{N}])(?:$date|(?:first|second|third|fourth|fifth|last|[1-5](?:st|nd|rd|th))\\s+week\\s+of\\s+$months(?:\\s+\\d{4})?|(?:the\\s+)?day\\s+before\\s+yesterday|(?:last|past|previous)(?:\\s+last)?\\s+(?:calendar\\s+)?week|(?:past|last)\\s+\\d+\\s+days|(?:this|last|previous)\\s+month(?:\\s+so\\s+far)?|weekdays?|weekends?|today|yesterday|$months(?:\\s+\\d{4})?)(?![\\p{L}\\p{N}])")
  private const val rangeStart = "(?:$date|$months(?:\\s+\\d{4})?)"
  private const val rangeEnd = "(?:$rangeStart|\\d{1,2}(?:st|nd|rd|th)?)"
  private val ranges =
      Regex(
          "(?i)(?<![\\p{L}\\p{N}])(?:\\bfrom\\s+)?($rangeStart)\\s*(?:to|till|through|until|[-–])\\s*($rangeEnd)(?![\\p{L}\\p{N}/-])|\\bbetween\\s+($rangeStart)\\s+and\\s+($rangeEnd)(?![\\p{L}\\p{N}/-])")

  fun hasExplicitRange(value: String): Boolean = ranges.containsMatchIn(value)

  fun validateInventorySpans(question: String, spans: List<IntRange>) {
    require(
        atoms.findAll(question).all { atom ->
          spans.any { atom.range.first >= it.first && atom.range.last <= it.last }
        }) {
          "Inventory every original time entry, including each repeated occurrence."
        }
  }

  // Calendar spelling aliases describe the same source phrase. This is comparison only:
  // the model still supplies every clause, and no day, year or range is inferred here.
  private val monthNames =
      listOf(
          "january",
          "february",
          "march",
          "april",
          "may",
          "june",
          "july",
          "august",
          "september",
          "october",
          "november",
          "december")
  private val monthAliases =
      monthNames.flatMap { month -> listOf(month to month, month.take(3) to month) }.toMap() +
          ("sept" to "september")
  private val monthWord = Regex("(?i)(?<![\\p{L}\\p{N}])$months(?![\\p{L}\\p{N}])")

  fun validateNumericOrder(question: String, quote: String, monthFirst: Boolean) {
    if (!monthFirst) return
    val namedMonths =
        monthWord
            .findAll(question)
            .map { monthNames.indexOf(monthAliases.getValue(it.value.lowercase())) + 1 }
            .toSet()
    Regex(
            "(?<![\\p{L}\\p{N}/-])(\\d{1,2})[/-](\\d{1,2})(?:[/-](?:\\d{4}|\\d{2}))?(?![\\p{L}\\p{N}/-])")
        .findAll(quote)
        .forEach { match ->
          val a = match.groupValues[1].toInt()
          val b = match.groupValues[2].toInt()
          if (a in 1..12 && b in 1..12 && a != b) {
            require(a in namedMonths && b !in namedMonths) {
              "Ambiguous numeric dates require numeric_date_order=day_first unless an explicit surrounding month uniquely supports month_first. Do not assume a locale or silently switch the default."
            }
          }
        }
  }

  private fun normalized(value: String) =
      monthWord
          .replace(value.lowercase()) { monthAliases.getValue(it.value) }
          .replace(Regex("\\s+"), " ")
          .trim()
          .removePrefix("the ")

  // Compare a model-expanded shorthand endpoint with the quoted source. The model must
  // supply the repeated month; this only checks that it reused the source month/day/year.
  private fun sameEndpoint(source: String, candidate: String, anchor: String): Boolean {
    if (source == candidate) return true
    val day =
        Regex("^(\\d{1,2})(?:st|nd|rd|th)?$")
            .matchEntire(source)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: return false
    val anchorMonth = monthWord.find(anchor)?.value ?: return false
    if (monthWord.find(candidate)?.value != anchorMonth) return false
    val numbers = Regex("\\d+").findAll(candidate).map { it.value.toInt() }.toList()
    val anchorYear = Regex("\\b\\d{4}\\b").find(anchor)?.value?.toInt()
    return numbers == listOfNotNull(day, anchorYear)
  }

  fun validateNormalization(original: String, rewritten: String) {
    val missing =
        atoms
            .findAll(original)
            .map { normalized(it.value) }
            .distinct()
            .filterNot { normalized(rewritten).contains(it) }
            .toList()
    require(missing.isEmpty()) {
      "Spelling normalization dropped or changed original date phrases: $missing"
    }
  }

  fun validate(question: String, expressions: List<String>) {
    val clauses = expressions.map(::normalized)
    clauses.forEach { clause ->
      val parts = atoms.findAll(clause).toList()
      val rangeCount = ranges.findAll(clause).count()
      require(parts.size <= if (rangeCount == 1) 2 else 1) {
        "Split separate periods into separate expressions; keep only each range's endpoints together."
      }
    }
    val sourceAtoms = atoms.findAll(question).map { normalized(it.value) }.distinct().toList()
    require(
        sourceAtoms.isNotEmpty() ||
            expressions.isEmpty() ||
            Regex("(?i)\\b(?:lst|wk|week|month|ystrdy)\\b").containsMatchIn(question)) {
          "The original question has no requested date. Return an empty date_expressions array for all-time scope; do not insert the current datetime."
        }
    val missing = sourceAtoms.filter { atom -> clauses.none { it.contains(atom) } }
    require(missing.isEmpty()) {
      "Preserve all original date clauses verbatim, including: $missing"
    }
    val sourceRanges = ranges.findAll(question).toList()
    val standalone =
        atoms
            .findAll(question)
            .filter { atom ->
              sourceRanges.none {
                atom.range.first >= it.range.first && atom.range.last <= it.range.last
              }
            }
            .map { normalized(it.value) }
            .distinct()
            .toList()
    val standaloneMissing =
        standalone.filter { atom ->
          clauses.none { clause ->
            ranges.findAll(clause).none() &&
                atoms.findAll(clause).any { normalized(it.value) == atom }
          }
        }
    require(standaloneMissing.isEmpty()) {
      "Preserve separately requested periods as separate clauses even when contained in a range: $standaloneMissing"
    }
    ranges.findAll(question).forEach { range ->
      val start = normalized(range.groupValues[1].ifBlank { range.groupValues[3] })
      val end = normalized(range.groupValues[2].ifBlank { range.groupValues[4] })
      require(
          clauses.any {
            ranges.findAll(it).any { candidate ->
              normalized(candidate.groupValues[1].ifBlank { candidate.groupValues[3] }) == start &&
                  sameEndpoint(
                      end,
                      normalized(candidate.groupValues[2].ifBlank { candidate.groupValues[4] }),
                      start)
            }
          }) {
            "Preserve the complete range ${range.value} in ONE expression; two endpoint days are not a range."
          }
    }
  }
}
