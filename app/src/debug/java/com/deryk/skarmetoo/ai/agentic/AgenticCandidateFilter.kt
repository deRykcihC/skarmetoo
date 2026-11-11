package com.deryk.skarmetoo.ai.agentic

import com.deryk.skarmetoo.chat.ChatDateWindow
import com.deryk.skarmetoo.chat.ChatDateWindows
import com.deryk.skarmetoo.chat.ChatReceiptDateOrder
import java.time.LocalDate

/** Cheap, evidence-based screening; no top-K cutoff or amount extraction/approval. */
object AgenticCandidateFilter {
  data class Evidence(
      val ocr: String?,
      val captureDate: LocalDate?,
      val storedContext: String = "",
      val verifiedEventDates: List<LocalDate> = emptyList()
  )

  data class Decision(
      val include: Boolean,
      val reason: String,
      val date: LocalDate?,
      val dateBasis: String
  )

  private val eventLabel =
      Regex(
          "(?im)(?:\\b(?:completed\\s*time|payment\\s*date|transaction\\s*date|paid\\s*on|purchase\\s*date)\\b|^\\s*date\\b)\\s*:?[^\\S\\n]*(?:\\n)?([^\\n]{0,100})")
  private val paymentSignal =
      Regex(
          "(?i)\\b(?:receipts?|payments?|paid|purchases?|transactions?|invoices?|expenses?|spent|checkout|amount\\s+due)\\b|收据|收據|付款|支付|发票|發票")
  private val unrelatedContext =
      Regex(
          "(?i)\\b(?:stock\\s+(?:chart|price)|trading\\s+(?:app|interface)|market\\s+chart|game(?:\\s+(?:menu|screenshot|event))?|settings\\s+(?:page|screen)|login\\s+(?:page|screen)|sign[- ]in\\s+(?:page|screen))\\b")

  fun decide(intent: Intent, windows: List<ChatDateWindow>, evidence: Evidence): Decision {
    val financial =
        intent in
            setOf(
                Intent.calculate_spending,
                Intent.count_transactions,
                Intent.search_receipts,
                Intent.find_transaction)
    if (!financial) return Decision(true, "nonfinancial query; preserve candidate", null, "unknown")
    val text = evidence.ocr.orEmpty()
    val labels = eventLabel.findAll(text).toList()
    val parsed =
        labels.map { label ->
          ChatDateWindows.explicitDates(label.groupValues[1], ChatReceiptDateOrder.DAY_FIRST)
        }
    val printedDates = parsed.flatten().distinct()
    val known = evidence.verifiedEventDates.distinct()
    val ambiguous = labels.isNotEmpty() && (parsed.any { it.size != 1 } || printedDates.size != 1)
    val conflicts = printedDates.isNotEmpty() && known.any { it !in printedDates }
    val date: LocalDate?
    val basis: String
    when {
      ambiguous || conflicts || known.size > 1 -> {
        date = null
        basis = "ambiguous_event"
      }
      printedDates.size == 1 -> {
        date = printedDates.single()
        basis = "printed_event_ocr"
      }
      known.size == 1 -> {
        date = known.single()
        basis = "verified_event"
      }
      labels.isNotEmpty() || AgenticReceiptEvidence.hasPrintedEventDate(text) -> {
        date = null
        basis = "unreadable_event"
      }
      else -> {
        date = evidence.captureDate
        basis = if (date == null) "unknown" else "capture_fallback"
      }
    }
    if (AgenticDates.includes(date, windows) == false)
        return Decision(false, "known date outside requested periods", date, basis)
    // Absence of a receipt keyword or a low embedding score is not evidence of irrelevance.
    // Only explicit nonpayment context can exclude an image, and payment clues override it.
    if (unrelatedContext.containsMatchIn(evidence.storedContext) &&
        !paymentSignal.containsMatchIn(text + "\n" + evidence.storedContext))
        return Decision(false, "stored context explicitly unrelated to payment", date, basis)
    return Decision(
        true, if (date == null) "uncertain date retained" else "in-scope candidate", date, basis)
  }
}
