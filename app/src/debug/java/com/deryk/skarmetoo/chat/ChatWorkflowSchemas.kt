package com.deryk.skarmetoo.chat

import org.json.JSONArray
import org.json.JSONObject

/** Topic-independent envelopes; LFM writes each question, condition and field reading rule. */
object ChatWorkflowSchemas {
  val extractionFields = setOf("value", "unit", "event_date", "record_id", "evidence")

  private fun objectSchema(properties: JSONObject) =
      JSONObject()
          .put("type", "object")
          .put("properties", properties)
          .put("required", JSONArray(properties.keys().asSequence().toList()))
          .put("additionalProperties", false)

  private fun text() = JSONObject().put("type", "string").put("minLength", 12)

  fun forStage(stage: ChatWorkflowStage): String? =
      when (stage) {
        ChatWorkflowStage.PLAN -> {
          val format = JSONObject()
          extractionFields.forEach { format.put(it, JSONObject().put("type", "null")) }
          objectSchema(
                  JSONObject()
                      .put("verification_question", text())
                      .put("extraction_question", text())
                      .put("response_format", objectSchema(format)))
              .toString()
        }
        else -> null
      }
}
