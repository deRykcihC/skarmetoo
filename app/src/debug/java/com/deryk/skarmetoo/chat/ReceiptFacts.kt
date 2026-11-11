package com.deryk.skarmetoo.chat

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

data class ReceiptItem(val name: String, val amount: BigDecimal)

data class ReceiptFacts(
    val sourceId: Long,
    val amount: BigDecimal,
    val currency: String,
    val date: LocalDate,
    val merchant: String,
    val transactionId: String,
    val evidence: String,
    val items: String,
    val lineItems: List<ReceiptItem> = emptyList(),
) {
  val duplicateKey: String
    get() =
        if (transactionId.isNotBlank())
            "${merchant.lowercase(Locale.ROOT)}|${transactionId.lowercase(Locale.ROOT)}|$date|$currency|${amount.stripTrailingZeros().toPlainString()}"
        else
            "${merchant.lowercase(Locale.ROOT)}|$date|$currency|${amount.stripTrailingZeros().toPlainString()}"

  companion object {
    fun parse(sourceId: Long, raw: String): ReceiptFacts? =
        runCatching {
              val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
              if (!json.optBoolean("is_transaction") ||
                  !json.optBoolean("paid") ||
                  json.optDouble("confidence", 0.0) !in 0.8..1.0)
                  return null
              val currency = json.optString("currency").uppercase(Locale.ROOT)
              if (!Regex("[A-Z]{3}").matches(currency)) return null
              val amount = json.getString("total").replace(",", "").toBigDecimal()
              if (amount.signum() <= 0 || amount.scale() > 2 || amount > BigDecimal("1000000000"))
                  return null
              val evidence = json.optString("evidence").trim()
              if (evidence.isBlank()) return null
              val merchant = json.optString("merchant").trim()
              val transactionId = json.optString("transaction_id").trim()
              if (merchant.isBlank() && transactionId.isBlank()) return null
              val lines = json.optJSONArray("line_items")
              val lineItems =
                  (0 until (lines?.length() ?: 0)).mapNotNull { index ->
                    val line = lines!!.optJSONObject(index) ?: return@mapNotNull null
                    val value =
                        line.optString("amount").replace(",", "").toBigDecimalOrNull()
                            ?: return@mapNotNull null
                    val name = line.optString("name").trim()
                    if (name.isEmpty() ||
                        value.signum() <= 0 ||
                        value.scale() > 2 ||
                        value > amount)
                        null
                    else ReceiptItem(name, value)
                  }
              ReceiptFacts(
                  sourceId,
                  amount,
                  currency,
                  LocalDate.parse(json.getString("date")),
                  merchant,
                  transactionId,
                  evidence,
                  json.optString("items"),
                  lineItems)
            }
            .getOrNull()

    fun deduplicate(facts: List<ReceiptFacts>): List<ReceiptFacts> =
        facts.distinctBy { it.duplicateKey }

    fun totals(facts: List<ReceiptFacts>): Map<String, BigDecimal> =
        facts
            .groupBy { it.currency }
            .mapValues { (_, values) ->
              values.fold(BigDecimal.ZERO) { acc, fact -> acc + fact.amount }
            }

    /** Specific item questions use visible line totals; never substitute the entire receipt. */
    fun forSubject(fact: ReceiptFacts, terms: Set<String>, tags: List<String>): ReceiptFacts? {
      if (terms.isEmpty()) return fact
      val items =
          fact.lineItems.filter { item -> terms.any { item.name.contains(it, ignoreCase = true) } }
      if (items.isNotEmpty())
          return fact.copy(
              amount = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount },
              evidence =
                  items.joinToString { "${it.name}: ${it.amount}" } +
                      " (item lines; receipt tax/fees excluded)")
      val categoryOrMerchant =
          terms.any { term ->
            fact.merchant.contains(term, ignoreCase = true) ||
                (term in setOf("food", "groceries", "transport", "shopping") &&
                    tags.any { it.equals(term, ignoreCase = true) })
          }
      return if (categoryOrMerchant) fact else null
    }
  }
}
