package com.deryk.skarmetoo.ai.agentic

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Schema-directed representation changes only. Never chooses among conflicting facts. */
object AgenticResponseFormatting {
  data class Result(val text: String, val changes: List<String>)

  fun normalize(raw: String, schema: JSONObject): Result {
    val changes = mutableListOf<String>()
    var text = raw.trim().removePrefix("\uFEFF").trim()
    if (text.startsWith("```json\n") && text.endsWith("```")) {
      text = text.removePrefix("```json\n").removeSuffix("```").trim()
      changes += "unwrap exact JSON fence"
    }
    val quoted = AgenticContracts.quoteVerificationDate(text)
    if (quoted != text) changes += "quote existing complete date literal"
    text = quoted
    val value = AgenticContracts.parse(text) // Includes duplicate-key and trailing-data checks.

    fun visit(o: JSONObject, s: JSONObject, path: String) {
      val properties = s.optJSONObject("properties") ?: return
      properties.keys().forEach { key ->
        if (!o.has(key)) return@forEach // Missing facts still fail closed-schema validation.
        val field = properties.getJSONObject(key)
        val types =
            field.get("type").let {
              if (it is JSONArray) (0 until it.length()).map(it::getString)
              else listOf(it.toString())
            }
        val previous = o.get(key)
        var current: Any = previous
        if (current is String && "null" in types && current.trim().equals("null", true))
            current = JSONObject.NULL
        if (current is JSONArray &&
            "string" in types &&
            current.length() == 1 &&
            current.opt(0) is String)
            current = current.getString(0)
        if (current is String &&
            "boolean" in types &&
            current.lowercase() in listOf("true", "false"))
            current = current.equals("true", true)
        if (key == "amount" && path == "root" && current is Number && "string" in types) {
          // Read the original JSON number lexeme, never round through Double.toString().
          // Preserve even unsupported precision/exponents verbatim. Observation validation
          // marks those values unresolved, rather than serializing a rounded JSON Double.
          current = requireNotNull(scalarToken(text, key))
        }
        if (current is String && field.has("enum")) {
          val options = field.getJSONArray("enum")
          val equal =
              (0 until options.length()).map(options::get).filterIsInstance<String>().filter {
                it.equals(current, true)
              }
          if (equal.size == 1) current = equal.single()
        }
        if (current is String && key == "currency") {
          val code = if (current.trim().equals("RM", true)) "MYR" else current.trim().uppercase()
          if (runCatching { java.util.Currency.getInstance(code) }.isSuccess) current = code
        }
        if (current is String && key == "transaction_date") {
          runCatching { AgenticPaymentFacts.date(current.trim()).toString() }
              .getOrNull()
              ?.let { current = it }
        }
        if (current is String &&
            key in setOf("normalized_question", "verification_question") &&
            current.startsWith('}')) {
          val sentence = current.dropWhile { it == '}' || it == ' ' || it == '\t' }
          if (sentence.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true)
              current = sentence
        }
        if (current != previous) {
          o.put(key, current)
          changes += "$path.$key representation"
        }
        if (current is JSONObject) visit(current, field, "$path.$key")
        if (current is JSONArray && field.has("items")) {
          val items = field.getJSONObject("items")
          repeat(current.length()) { n ->
            (current.opt(n) as? JSONObject)?.let { visit(it, items, "$path.$key[$n]") }
          }
        }
      }
    }
    visit(value, schema, "root")
    return Result(if (changes.isEmpty()) text else value.toString(), changes)
  }

  /**
   * Only called on strictly checked JSON. Ignores nested fields and strings containing fake keys.
   */
  private fun scalarToken(text: String, field: String): String? {
    var pos = 0
    var depth = 0
    while (pos < text.length) {
      when (text[pos]) {
        '{',
        '[' -> {
          depth++
          pos++
        }
        '}',
        ']' -> {
          depth--
          pos++
        }
        '"' -> {
          val start = pos++
          while (pos < text.length) {
            if (text[pos++] == '\\') pos++ else if (text[pos - 1] == '"') break
          }
          val end = pos
          var next = pos
          while (next < text.length && text[next].isWhitespace()) next++
          if (depth == 1 &&
              next < text.length &&
              text[next] == ':' &&
              JSONTokener(text.substring(start, end)).nextValue() == field) {
            next++
            while (next < text.length && text[next].isWhitespace()) next++
            val from = next
            while (next < text.length && text[next] !in ",}") next++
            return text.substring(from, next).trim()
          }
        }
        else -> pos++
      }
    }
    return null
  }
}
