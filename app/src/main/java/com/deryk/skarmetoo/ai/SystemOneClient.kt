package com.deryk.skarmetoo.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

data class SystemOneConfig(
    val serverUrl: String,
    val apiKey: String? = null,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 120_000,
    val maxResponseBytes: Int = 2 * 1024 * 1024
) {
  init {
    val uri = URI(serverUrl)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
      "Use an HTTP or HTTPS server URL."
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
      "Server URL must not contain credentials, a query or a fragment."
    }
    require(apiKey == null || (apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey)) {
      "Invalid API key."
    }
    require(
        connectTimeoutMs > 0 && readTimeoutMs > 0 && maxResponseBytes in 1 until Int.MAX_VALUE) {
          "Timeouts and response size limit must be positive and finite."
        }
  }

  internal val endpoint: String
    get() {
      val base = serverUrl.trimEnd('/')
      return when {
        base.endsWith("/v1/systemone") -> base
        base.endsWith("/v1") -> "$base/systemone"
        else -> "$base/v1/systemone"
      }
    }
}

class SystemOneHttpException(val statusCode: Int, val serverMessage: String) :
    IOException("System One HTTP $statusCode: $serverMessage")

class SystemOneProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Reusable, stateless HTTP adapter for llama-server's /v1/systemone decision endpoint. This does
 * not add Clef support to Llamatik JNI. The server must load a decision model. No retries,
 * text-generation fallback, logging, preference storage or implicit image upload.
 */
class SystemOneClient(private val config: SystemOneConfig) {
  suspend fun evaluate(request: SystemOneRequest): SystemOneResponse {
    // Snapshot mutable caller collections through serialization before entering background I/O.
    val snapshot = decodeRequestQuestions(request)
    val payload =
        request
            .copy(questions = snapshot, images = request.images.toList())
            .toJson()
            .toString()
            .toByteArray(Charsets.UTF_8)
    return runInterruptible(Dispatchers.IO) {
      val connection = URI(config.endpoint).toURL().openConnection() as HttpURLConnection
      try {
        connection.requestMethod = "POST"
        connection.connectTimeout = config.connectTimeoutMs
        connection.readTimeout = config.readTimeoutMs
        // Avoid forwarding a bearer token to a redirected host.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        config.apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body =
            stream
                ?.use {
                  val output = ByteArrayOutputStream()
                  val buffer = ByteArray(8192)
                  while (true) {
                    val read =
                        it.read(
                            buffer,
                            0,
                            minOf(buffer.size, config.maxResponseBytes - output.size() + 1))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > config.maxResponseBytes)
                        throw SystemOneProtocolException(
                            "System One response exceeds the configured size limit.")
                  }
                  output.toString("UTF-8")
                }
                .orEmpty()
        if (status !in 200..299) throw SystemOneHttpException(status, errorMessage(status, body))
        SystemOneCodec.decode(body, snapshot)
      } finally {
        connection.disconnect()
      }
    }
  }

  private fun errorMessage(status: Int, body: String): String {
    val detail =
        runCatching {
              val json = JSONObject(body)
              val error = json.opt("error")
              when (error) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> json.optString("message")
              }
            }
            .getOrDefault("")
    return detail.take(1000).ifBlank {
      when (status) {
        404 -> "The server does not expose /v1/systemone."
        501 -> "The loaded model or server does not support this decision request or its images."
        401,
        403 -> "The server rejected authentication."
        else -> "The server rejected the request."
      }
    }
  }

  private fun decodeRequestQuestions(request: SystemOneRequest) =
      request.questions.mapValues { (_, question) ->
        when (question) {
          is SystemOneQuestion.Choice -> question.copy(criteria = question.criteria.toMap())
          is SystemOneQuestion.Score -> question.copy(levels = question.levels.toList())
          is SystemOneQuestion.Noul -> question
        }
      }
}

internal object SystemOneCodec {
  fun decode(body: String, questions: Map<String, SystemOneQuestion>): SystemOneResponse {
    try {
      val root = JSONObject(body)
      val answers = root.getJSONObject("answers")
      require(answers.keysSet() == questions.keys) {
        "Response question IDs do not match the request."
      }
      val typed =
          questions.mapValues { (id, question) ->
            val answer = answers.getJSONObject(id)
            when (question) {
              is SystemOneQuestion.Choice -> {
                require(answer.getString("type") == "choice") { "Wrong answer type for $id." }
                val probabilities = probabilities(answer.getJSONObject("probabilities"))
                require(probabilities.keys == question.criteria.keys) {
                  "Choice options do not match for $id."
                }
                val choice = answer.getString("choice")
                require(choice in probabilities) { "Unknown choice for $id." }
                SystemOneAnswer.Choice(choice, probabilities, probability(answer, "confidence"))
              }
              is SystemOneQuestion.Score -> {
                require(answer.getString("type") == "score") { "Wrong answer type for $id." }
                val expected = question.levels.indices.map { it.toString() }.toSet()
                val probabilities = probabilities(answer.getJSONObject("probabilities"))
                val legend = answer.getJSONObject("legend")
                require(probabilities.keys == expected && legend.keysSet() == expected) {
                  "Score levels do not match for $id."
                }
                val score = number(answer, "score")
                require(score in 0.0..question.levels.lastIndex.toDouble()) {
                  "Score is out of range for $id."
                }
                SystemOneAnswer.Score(
                    score,
                    expected.associate { it.toInt() to legend.getString(it) },
                    probabilities.mapKeys { it.key.toInt() },
                    probability(answer, "confidence"))
              }
              is SystemOneQuestion.Noul -> {
                require(answer.getString("type") == "noul") { "Wrong answer type for $id." }
                SystemOneAnswer.Noul(probability(answer, "noul"))
              }
            }
          }
      val usage = root.getJSONObject("usage")
      val input = integer(usage, "input_tokens")
      val output = integer(usage, "output_tokens")
      require(output == 0L) { "Decision responses must have zero output tokens." }
      return SystemOneResponse(
          root.optString("model").takeIf { it.isNotBlank() },
          typed,
          SystemOneUsage(input, output),
          body)
    } catch (e: Exception) {
      throw SystemOneProtocolException(
          "Invalid System One response: ${e.message ?: "unexpected format"}", e)
    }
  }

  private fun number(json: JSONObject, key: String): Double {
    val value = json.get(key)
    require(value is Number) { "$key must be a number." }
    return value.toDouble().also { require(it.isFinite()) { "$key must be finite." } }
  }

  private fun probability(json: JSONObject, key: String) =
      number(json, key).also { require(it in 0.0..1.0) { "$key must be between 0 and 1." } }

  private fun integer(json: JSONObject, key: String): Long {
    val value = number(json, key)
    require(value >= 0 && value < Long.MAX_VALUE.toDouble() && value % 1 == 0.0) {
      "$key must be a non-negative integer."
    }
    return value.toLong()
  }

  private fun probabilities(json: JSONObject): Map<String, Double> {
    val result = json.keysSet().associateWith { probability(json, it) }
    require(kotlin.math.abs(result.values.sum() - 1.0) <= 0.001) {
      "Answer probabilities must sum to 1."
    }
    return result
  }
}

private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()
