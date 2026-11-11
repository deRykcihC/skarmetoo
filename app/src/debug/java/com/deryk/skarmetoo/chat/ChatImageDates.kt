package com.deryk.skarmetoo.chat

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.json.JSONObject

/** Capture, import and file modification dates are distinct facts, never interchangeable. */
data class ChatImageDates(
    val taken: LocalDate? = null,
    val takenSource: String = "Image taken date",
    val added: LocalDate? = null,
    val modified: LocalDate? = null,
) {
  fun forQuery(query: ChatQuery): LocalDate? =
      if (query.dateBasis == ChatDateBasis.ADDED) added else taken
}

data class ChatResolvedDate(
    val date: LocalDate?,
    val label: String,
    val evidence: String = "",
    val error: String? = null,
)

object ChatImageDateValues {
  fun epoch(raw: Long, seconds: Boolean, zone: ZoneId): LocalDate? =
      if (raw <= 0) null
      else
          runCatching {
                Instant.ofEpochMilli(if (seconds) Math.multiplyExact(raw, 1000) else raw)
                    .atZone(zone)
                    .toLocalDate()
              }
              .getOrNull()

  fun exif(raw: String?, offset: String?, zone: ZoneId): LocalDate? =
      runCatching {
            val time =
                LocalDateTime.parse(
                    requireNotNull(raw),
                    DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
                        .withResolverStyle(ResolverStyle.STRICT))
            if (offset.isNullOrBlank()) time.toLocalDate()
            else time.atOffset(ZoneOffset.of(offset)).atZoneSameInstant(zone).toLocalDate()
          }
          .getOrNull()
}

/** Visible event dates win; only an absent event date permits a real capture-date fallback. */
object ChatEventDates {
  fun resolve(
      query: ChatQuery,
      json: JSONObject,
      printed: ChatPrintedEventDate?,
      metadata: ChatImageDates,
      order: ChatReceiptDateOrder,
      visibleEventLabel: Boolean = false,
  ): ChatResolvedDate {
    fun taken() =
        ChatResolvedDate(
            metadata.taken,
            "Image taken date",
            metadata.takenSource,
            if (metadata.taken == null) "No image taken date is available." else null)
    if (query.dateBasis == ChatDateBasis.TAKEN) return taken()
    if (query.dateBasis == ChatDateBasis.ADDED)
        return ChatResolvedDate(
            metadata.added,
            "Image added date",
            "Image import metadata",
            if (metadata.added == null) "No image added/imported date is available." else null)
    if (printed != null)
        return ChatResolvedDate(printed.date, "Event date in image", printed.evidence)
    ChatWorkflowFacts.date(json, order)?.let {
      return ChatResolvedDate(it, "Event date in image", ChatWorkflowFacts.rawDate(json).orEmpty())
    }
    val raw = ChatWorkflowFacts.rawDate(json)
    if (raw != null || visibleEventLabel)
        return ChatResolvedDate(
            null,
            "Event date in image",
            raw.orEmpty(),
            if (raw != null) "The event date '$raw' could not be interpreted."
            else "An event date is visible but could not be read unambiguously.")
    if (metadata.taken != null) return taken()
    return ChatResolvedDate(
        null,
        "No readable date",
        error = "No event date was extracted and no image taken date is available.")
  }
}
