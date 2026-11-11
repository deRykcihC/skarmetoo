package com.deryk.skarmetoo.ai

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Text or structured state/instructions supported by the System One wire protocol. */
sealed interface SystemOneContent {
  data class Text(val value: String) : SystemOneContent
  /** An object or array encoded as JSON; never interpreted as a generated answer. */
  data class Json(val value: String) : SystemOneContent
}

sealed interface SystemOneQuestion {
  val instructions: SystemOneContent

  data class Choice(
      override val instructions: SystemOneContent,
      val criteria: Map<String, String?>
  ) : SystemOneQuestion

  /** Levels are ordered from lowest to highest; returned scores use zero-based indices. */
  data class Score(override val instructions: SystemOneContent, val levels: List<String>) :
      SystemOneQuestion

  data class Noul(
      override val instructions: SystemOneContent,
      val trueDescription: String? = null,
      val falseDescription: String? = null
  ) : SystemOneQuestion
}

data class SystemOneRequest(
    val state: SystemOneContent,
    val questions: Map<String, SystemOneQuestion>,
    val images: List<SystemOneImage> = emptyList(),
    /** Optional model alias for a llama-server router. */
    val model: String? = null
)

/** Inline images only: the client does not fetch remote images or upload local files implicitly. */
class SystemOneImage private constructor(val dataUrl: String) {
  companion object {
    fun fromBytes(bytes: ByteArray, mimeType: String = "image/jpeg"): SystemOneImage {
      require(bytes.isNotEmpty()) { "Image bytes must not be empty." }
      require(mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/bmp")) {
        "Unsupported image MIME type."
      }
      return SystemOneImage("data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}")
    }
  }
}

sealed interface SystemOneAnswer {
  data class Choice(
      val choice: String,
      val probabilities: Map<String, Double>,
      val confidence: Double
  ) : SystemOneAnswer

  data class Score(
      val score: Double,
      val legend: Map<Int, String>,
      val probabilities: Map<Int, Double>,
      val confidence: Double
  ) : SystemOneAnswer

  /** Probability of true, rather than a boolean or confidence in the most likely answer. */
  data class Noul(val probabilityTrue: Double) : SystemOneAnswer
}

data class SystemOneUsage(val inputTokens: Long, val outputTokens: Long)

data class SystemOneResponse(
    val model: String?,
    val answers: Map<String, SystemOneAnswer>,
    val usage: SystemOneUsage,
    val rawJson: String
)

internal fun SystemOneContent.toJsonValue(): Any =
    when (this) {
      is SystemOneContent.Text -> value
      is SystemOneContent.Json -> {
        val parser = JSONTokener(value)
        val parsed = parser.nextValue()
        require(parsed is JSONObject || parsed is JSONArray) {
          "Structured content must be a JSON object or array."
        }
        require(parser.nextClean() == '\u0000') { "Unexpected data after structured JSON content." }
        parsed
      }
    }

internal fun SystemOneRequest.toJson(): JSONObject {
  require(questions.isNotEmpty()) { "At least one System One question is required." }
  require(questions.keys.all { it.isNotBlank() }) { "Question IDs must not be blank." }
  val questionJson = JSONObject()
  questions.forEach { (id, question) ->
    val item = JSONObject().put("instructions", question.instructions.toJsonValue())
    when (question) {
      is SystemOneQuestion.Choice -> {
        require(question.criteria.size in 2..255) {
          "Choice questions require 2 to 255 options; the server/model may impose a lower limit."
        }
        require(question.criteria.keys.all { it.isNotBlank() }) {
          "Choice option IDs must not be blank."
        }
        val criteria = JSONObject()
        question.criteria.forEach { (option, description) ->
          criteria.put(option, description ?: JSONObject.NULL)
        }
        item.put("type", "choice").put("criteria", criteria)
      }
      is SystemOneQuestion.Score -> {
        require(question.levels.size in 2..10) { "Score questions require 2 to 10 levels." }
        item.put("type", "score").put("criteria", JSONArray(question.levels))
      }
      is SystemOneQuestion.Noul -> {
        item.put("type", "noul")
        if (question.trueDescription != null || question.falseDescription != null) {
          item.put(
              "criteria",
              JSONObject()
                  .put("true", question.trueDescription ?: JSONObject.NULL)
                  .put("false", question.falseDescription ?: JSONObject.NULL))
        }
      }
    }
    questionJson.put(id, item)
  }
  return JSONObject().put("state", state.toJsonValue()).put("questions", questionJson).also {
    if (images.isNotEmpty()) it.put("images", JSONArray(images.map { image -> image.dataUrl }))
    if (model != null) {
      require(model.isNotBlank()) { "Model alias must not be blank." }
      it.put("model", model)
    }
  }
}
