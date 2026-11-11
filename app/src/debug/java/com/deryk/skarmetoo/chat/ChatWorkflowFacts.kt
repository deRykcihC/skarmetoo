package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import org.json.JSONObject

data class ChatAmount(
    val record: ChatExtractedRecord,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate?,
    val printedDate: ChatPrintedEventDate? = null,
    val printedAmount: ChatPrintedPaidAmount? = null,
    val resolvedDate: ChatResolvedDate? = null,
)

data class ChatAmountExclusion(val sourceId: Long, val reason: String)

data class ChatAmountResult(
    val amounts: List<ChatAmount>,
    val excluded: Int,
    val duplicates: Int,
    val exclusions: List<ChatAmountExclusion> = emptyList(),
) {
  val totals: Map<String, BigDecimal>
    get() =
        amounts
            .groupBy { it.currency }
            .mapValues { (_, records) ->
              records.fold(BigDecimal.ZERO) { sum, record -> sum + record.amount }
            }
}

/**
 * Adapts common model-selected fields without changing the generated prompt or inventing fields.
 */
object ChatWorkflowFacts {
  private data class NumericValue(val amount: BigDecimal?, val currency: String? = null)

  private val moneyPrefix = Regex("^([A-Za-z]{2,3})\\s*([+-]?\\d+(?:\\.\\d+)?)$")
  private val moneySuffix = Regex("^([+-]?\\d+(?:\\.\\d+)?)\\s*([A-Za-z]{2,3})$")

  private fun numericValue(raw: String): NumericValue {
    val text = raw.trim()
    text.toBigDecimalOrNull()?.let {
      return NumericValue(it)
    }
    moneyPrefix.matchEntire(text)?.let {
      val unit = currency(it.groupValues[1]) ?: return NumericValue(null)
      return NumericValue(it.groupValues[2].toBigDecimalOrNull(), unit)
    }
    moneySuffix.matchEntire(text)?.let {
      val unit = currency(it.groupValues[2]) ?: return NumericValue(null)
      return NumericValue(it.groupValues[1].toBigDecimalOrNull(), unit)
    }
    return NumericValue(null)
  }

  private fun quotedMoney(evidence: String): List<NumericValue> {
    val prefix =
        Regex("(?<![\\p{L}\\p{N}])([A-Za-z]{2,3})\\s*(\\d+(?:\\.\\d+)?)(?![\\p{L}\\p{N}]|[.,]\\d)")
    val suffix =
        Regex("(?<![\\p{L}\\p{N}.,])(\\d+(?:\\.\\d+)?)\\s*([A-Za-z]{2,3})(?![\\p{L}\\p{N}])")
    return prefix
        .findAll(evidence)
        .mapNotNull {
          val amount = it.groupValues[2].toBigDecimalOrNull()
          currency(it.groupValues[1])?.let { unit -> NumericValue(amount, unit) }
        }
        .toList() +
        suffix
            .findAll(evidence)
            .mapNotNull {
              val amount = it.groupValues[1].toBigDecimalOrNull()
              currency(it.groupValues[2])?.let { unit -> NumericValue(amount, unit) }
            }
            .toList()
  }

  private fun quotedCurrencies(evidence: String, amount: BigDecimal) =
      quotedMoney(evidence).filter { it.amount?.compareTo(amount) == 0 }.mapNotNull { it.currency }

  private val amountFields =
      listOf(
          "amount",
          "amount_paid",
          "paid_amount",
          "amount_spent",
          "spent_amount",
          "value",
          "payment_amount",
          "total_paid",
          "total_amount",
          "total_spent",
          "total_expenditure",
          "expenditure_amount",
          "monetary_value",
          "numeric_value",
          "total",
          "spending_amount")
  private val dateFields =
      listOf(
          "transaction_date",
          "payment_date",
          "purchase_date",
          "date",
          "event_date",
          "visible_event_date",
          "record_date",
          "receipt_date",
          "completed_time",
          "payment_datetime",
          "transaction_datetime")

  fun date(
      json: JSONObject,
      numericOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.UNRESOLVED
  ): LocalDate? {
    val dates =
        dateFields
            .filter { json.has(it) && !json.isNull(it) }
            .map { parseDate(json.optString(it), numericOrder) }
    return if (dates.isNotEmpty() && dates.all { it != null } && dates.distinct().size == 1)
        dates.first()
    else null
  }

  fun rawDate(json: JSONObject): String? =
      dateFields.firstNotNullOfOrNull { key ->
        json.optString(key).takeIf { json.has(key) && !json.isNull(key) && it.isNotBlank() }
      }

  fun parseDate(
      raw: String,
      numericOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.UNRESOLVED
  ): LocalDate? {
    val text = raw.trim()
    runCatching { LocalDate.parse(text) }
        .getOrNull()
        ?.let {
          return it
        }
    if (Regex("^\\d{4}-\\d{2}-\\d{2}[T ]").containsMatchIn(text))
        runCatching { LocalDate.parse(text.take(10)) }
            .getOrNull()
            ?.let {
              return it
            }
    val parts = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})(?:[ T].*)?$").matchEntire(text)
    if (parts != null) {
      val a = parts.groupValues[1].toInt()
      val b = parts.groupValues[2].toInt()
      val year = parts.groupValues[3].toInt()
      // The visible app convention resolves numeric order; UNRESOLVED retains the strict parser.
      val pair =
          when {
            numericOrder == ChatReceiptDateOrder.DAY_FIRST -> b to a
            numericOrder == ChatReceiptDateOrder.MONTH_FIRST -> a to b
            a > 12 -> b to a
            b > 12 -> a to b
            a == b -> a to b
            else -> return null
          }
      return runCatching { LocalDate.of(year, pair.first, pair.second) }.getOrNull()
    }
    for (pattern in
        listOf("d MMM uuuu", "d MMMM uuuu", "MMM d, uuuu", "MMMM d, uuuu")) runCatching {
          LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        }
        .getOrNull()
        ?.let {
          return it
        }
    return null
  }

  fun currency(raw: String): String? {
    val value =
        when (val code = raw.trim().uppercase(Locale.ROOT)) {
          "RM" -> "MYR"
          else -> code
        }
    if (!Regex("[A-Z]{3}").matches(value)) return null
    return runCatching { Currency.getInstance(value).currencyCode }.getOrNull()
  }

  fun amounts(
      query: ChatQuery,
      records: List<ChatExtractedRecord>,
      imageHashes: Map<Long, String>,
      numericOrder: ChatReceiptDateOrder = ChatReceiptDateOrder.UNRESOLVED,
      printedDates: Map<Long, ChatPrintedEventDate> = emptyMap(),
      printedPaidAmounts: Map<Long, ChatPrintedPaidAmount> = emptyMap(),
      imageDates: Map<Long, ChatImageDates> = emptyMap(),
      visibleEventLabels: Set<Long> = emptySet(),
  ): ChatAmountResult {
    val accepted = mutableListOf<ChatAmount>()
    val seen = mutableSetOf<String>()
    var excluded = 0
    var duplicates = 0
    val exclusions = mutableListOf<ChatAmountExclusion>()
    for (record in records) {
      val json = record.json
      var parsedValues =
          amountFields
              .filter { json.has(it) && !json.isNull(it) }
              .map { numericValue(json.opt(it)?.toString().orEmpty()) }
      var usedPrintedAmount: ChatPrintedPaidAmount? = null
      // A missing/zero quantity may be recovered only when LFM's own first-pass amount agrees with
      // an explicit paid label in this image, for an unqualified spending-total question.
      if (query.spending &&
          query.terms.isEmpty() &&
          parsedValues.all { it.amount == null || it.amount.signum() == 0 } &&
          record.verification.decision == ChatDecision.YES) {
        val printed = printedPaidAmounts[record.sourceId]
        val quotes =
            quotedMoney(record.verification.evidence).distinctBy {
              it.currency to it.amount?.stripTrailingZeros()
            }
        val quote = quotes.singleOrNull()
        if (printed != null &&
            quote?.currency == printed.currency &&
            quote.amount?.compareTo(printed.amount) == 0) {
          parsedValues = listOf(NumericValue(printed.amount, printed.currency))
          usedPrintedAmount = printed
        }
      }
      val values = parsedValues.map { it.amount }
      val amount = values.firstOrNull()
      val declaredCurrencies =
          listOf("currency", "currency_code", "unit")
              .filter { json.has(it) && !json.isNull(it) }
              .map { currency(json.optString(it)) }
      // A model-read quantity can be paired with its explicitly labeled printed currency only
      // when all parsed quantities agree exactly. Item-specific questions cannot use this aid.
      val matchingPaid =
          printedPaidAmounts[record.sourceId]?.takeIf {
            query.spending &&
                query.terms.isEmpty() &&
                record.verification.decision == ChatDecision.YES &&
                amount != null &&
                values.all { value -> value?.compareTo(it.amount) == 0 }
          }
      val corroboratedCurrencies =
          parsedValues.mapNotNull { it.currency } +
              (amount?.let { quotedCurrencies(record.verification.evidence, it) } ?: emptyList()) +
              listOfNotNull(matchingPaid?.currency)
      if (usedPrintedAmount == null &&
          matchingPaid != null &&
          declaredCurrencies.none { it != null } &&
          parsedValues.none { it.currency != null })
          usedPrintedAmount = matchingPaid
      // An invalid optional code cannot erase a currency embedded in the exact amount/quote.
      // Valid conflicting currencies remain a conflict; there is no RM-from-RMB guess.
      val currencies =
          (if (corroboratedCurrencies.isNotEmpty()) declaredCurrencies.filterNotNull()
          else declaredCurrencies) + corroboratedCurrencies
      val currency = currencies.firstOrNull()
      // Explicitly labeled text from the same image can correct tiny digits misread by vision.
      // Date provenance is resolved separately; capture is a fallback only when no event is
      // visible.
      val printedDate = printedDates[record.sourceId]
      val resolvedDate =
          ChatEventDates.resolve(
              query,
              json,
              printedDate,
              imageDates[record.sourceId] ?: ChatImageDates(),
              numericOrder,
              record.sourceId in visibleEventLabels)
      val date = resolvedDate.date
      val status =
          listOf("payment_status", "transaction_status", "status")
              .joinToString(" ") { json.optString(it) }
              .lowercase(Locale.ROOT)
      val invalidStatus =
          Regex("\\b(unpaid|pending|failed|cancelled|canceled|refunded|refund|incoming)\\b")
              .containsMatchIn(status) ||
              (json.has("paid") && !json.isNull("paid") && !json.optBoolean("paid"))
      val reason =
          when {
            record.verification.decision != ChatDecision.YES -> "The image check was uncertain."
            invalidStatus -> "The extracted status does not confirm completed spending."
            amount == null -> "No readable amount was extracted."
            values.any { it == null || it.compareTo(amount) != 0 } ->
                "The extracted amounts conflict or cannot be read."
            amount.signum() <= 0 ||
                amount.stripTrailingZeros().scale() > 2 ||
                amount > BigDecimal("1000000000") -> "The extracted amount is invalid."
            currency == null -> "No readable currency was extracted."
            currencies.any { it == null || it != currency } -> "The extracted currencies conflict."
            query.spending &&
                query.terms.isEmpty() &&
                printedPaidAmounts[record.sourceId]?.let {
                  amount.compareTo(it.amount) != 0 || currency != it.currency
                } == true -> "The extracted amount conflicts with the explicit paid label."
            query.start != null && date == null -> resolvedDate.error
            !query.includes(date) -> "${resolvedDate.label} $date is outside ${query.period}."
            else -> null
          }
      if (reason != null) {
        excluded++
        exclusions += ChatAmountExclusion(record.sourceId, reason)
        continue
      }
      val validAmount = requireNotNull(amount)
      val validCurrency = requireNotNull(currency)
      val imageHash = imageHashes[record.sourceId].orEmpty()
      val reference =
          listOf("transaction_id", "transaction_reference", "payment_reference")
              .firstNotNullOfOrNull {
                json.optString(it).takeIf { value -> !json.isNull(it) && value.isNotBlank() }
              }
      // Equal merchant/date/amount alone is insufficient to merge separate real purchases.
      val keys =
          listOfNotNull(
              imageHash.takeIf { it.isNotBlank() }?.let { "image:$it" },
              reference?.let {
                "reference:$validCurrency:$it:${validAmount.stripTrailingZeros()}:$date"
              })
      if (keys.any { it in seen }) {
        duplicates++
        continue
      }
      seen.addAll(keys)
      accepted +=
          ChatAmount(
              record,
              validAmount,
              validCurrency,
              date,
              printedDate,
              usedPrintedAmount,
              resolvedDate)
    }
    return ChatAmountResult(accepted, excluded, duplicates, exclusions)
  }
}
