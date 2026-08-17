package com.deryk.skarmetoo.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class OllamaJobSettings(
    val languageCode: String = "en",
    val languageName: String = "English",
    val detailLevel: String = "DETAILED",
    val customPrompt: String = "",
)

class LmStudioClient(endpoint: String = "http://127.0.0.1:1234/v1") {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
  private val baseUrl = normalizeEndpoint(endpoint)
  private val inFlight = AtomicReference<CompletableFuture<HttpResponse<String>>?>(null)

  fun models(): List<String> {
    val request = requestBuilder("/models").GET().timeout(Duration.ofSeconds(15)).build()
    val body = response(request)
    return Regex("(?is)\\\"id\\\"\\s*:\\s*\\\"([^\"]+)\\\"").findAll(body).map { it.groupValues[1] }.toList()
  }

  fun cancelInFlight() {
    inFlight.getAndSet(null)?.cancel(true)
  }

  suspend fun analyze(model: String, image: ImagePayload, settings: OllamaJobSettings): Pair<String, String> {
    require(model.isNotBlank()) { "Select an LM Studio model" }
    val encoded = Base64.getEncoder().encodeToString(image.bytes)
    val mime = image.contentType.ifBlank { "image/jpeg" }
    val prompt = buildPrompt(settings)
    val content = "[{\"type\":\"text\",\"text\":${quote(prompt)}},{\"type\":\"image_url\",\"image_url\":{\"url\":${quote("data:$mime;base64,$encoded")}}}]"
    val json = "{\"model\":${quote(model)},\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":$content}]}"
    val request = requestBuilder("/chat/completions").header("Content-Type", "application/json").header("Authorization", "Bearer lm-studio").timeout(Duration.ofMinutes(10)).POST(HttpRequest.BodyPublishers.ofString(json)).build()
    val body = cancellableResponse(request)
    val assistantContent = Regex("(?s)\\\"message\\\"\\s*:\\s*\\{.*?\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\"\\\\])*)\\\"").find(body)?.groupValues?.get(1)?.let(::unescapeJsonString)
        ?: error("LM Studio returned no assistant content")
    return parseAnalysisResult(assistantContent)
  }

  internal fun normalizeEndpoint(endpoint: String): String {
    val input = endpoint.trim().removeSuffix("/")
    val withScheme = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
    val uri = runCatching { URI.create(withScheme) }.getOrElse { error("Invalid LM Studio endpoint") }
    require(!uri.host.isNullOrBlank()) { "Invalid LM Studio endpoint" }
    val origin = "${uri.scheme}://${uri.authority}"
    return if (uri.path.trimEnd('/').endsWith("/v1")) origin + uri.path.trimEnd('/') else origin + "/v1"
  }

  internal fun buildPrompt(settings: OllamaJobSettings): String {
    val language = settings.languageName.ifBlank { "English" }
    return when (settings.detailLevel.uppercase()) {
      "BRIEF" -> """Describe this image briefly in $language. Respond with EXACTLY this format and nothing else:
SUMMARY: [your one sentence description]
TAGS: [tag1, tag2, tag3]"""
      "COMPREHENSIVE" -> """Describe this image with maximum detail in $language, using a single paragraph. Respond with EXACTLY this format and nothing else:
SUMMARY: [your comprehensive paragraph describing absolutely everything visible in the image]
TAGS: [tag1, tag2, tag3, tag4, tag5, tag6, tag7, tag8]"""
      "CUSTOM" -> """${settings.customPrompt.ifBlank { "Describe this screenshot." }} Output your summary in $language.
Respond with EXACTLY this format and nothing else:
SUMMARY: [your response based on the instruction]
TAGS: [extracted tag1, tag2, tag3]"""
      else -> """Describe this image in detail in $language. Write 2-3 sentences. Respond with EXACTLY this format and nothing else:
SUMMARY: [your detailed 2-3 sentence description]
TAGS: [tag1, tag2, tag3, tag4, tag5]"""
    }
  }

  internal fun parseAnalysisResult(result: String): Pair<String, String> {
    val cleaned = result.replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("```\\s*$"), "").trim()
    val summaryLabel = Regex("(?is)\\bSUMMARY\\s*:\\s*(.*?)(?=\\s*\\bTAGS\\s*:|$)").find(cleaned)?.groupValues?.get(1)
    val tagsLabel = Regex("(?is)\\bTAGS\\s*:\\s*(.*)$").find(cleaned)?.groupValues?.get(1)
    if (!summaryLabel.isNullOrBlank()) return summaryLabel.trim().trim('[', ']', '"') to normalizeTags(tagsLabel.orEmpty())
    val summaryJson = findJsonString(cleaned, "summary") ?: findJsonString(cleaned, "description") ?: findJsonString(cleaned, "caption") ?: findJsonString(cleaned, "content")
    if (!summaryJson.isNullOrBlank()) {
      val tagsArray = Regex("(?is)[\\\"']tags[\\\"']\\s*:\\s*\\[([^]]*)]").find(cleaned)?.groupValues?.get(1)
      val tagsString = findJsonString(cleaned, "tags")
      val rawTags = tagsArray?.let { Regex("[\\\"']((?:\\\\.|[^\\\"'])+)[\\\"']").findAll(it).joinToString(", ") { match -> unescapeJsonString(match.groupValues[1]) } } ?: tagsString.orEmpty()
      return summaryJson.trim() to normalizeTags(rawTags)
    }
    error("LM Studio returned invalid analysis format: ${cleaned.take(240)}")
  }

  private fun requestBuilder(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(baseUrl + path))

  private fun findJsonString(source: String, key: String): String? {
    val pattern = Regex("(?is)[\\\"']${Regex.escape(key)}[\\\"']\\s*:\\s*[\\\"']((?:\\\\.|[^\\\"'])*)[\\\"']", RegexOption.IGNORE_CASE)
    return pattern.find(source)?.groupValues?.get(1)?.let(::unescapeJsonString)
  }

  private fun unescapeJsonString(value: String): String = value.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

  private fun normalizeTags(value: String): String = value.trim().trim('[', ']', '"').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.joinToString(", ")

  fun unloadModel(model: String): Boolean {
    if (model.isBlank()) return false
    val origin = runCatching { URI.create(baseUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return false
    val instanceIds = mutableListOf(model)
    runCatching {
        val req = HttpRequest.newBuilder(URI.create(origin + "/api/v0/models"))
            .timeout(Duration.ofSeconds(5)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) {
            Regex("\"instance_?id\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(resp.body()).forEach {
                val id = it.groupValues[1]
                if (id.contains(model, ignoreCase = true) || model.contains(id, ignoreCase = true)) instanceIds.add(0, id)
                else if (id !in instanceIds) instanceIds.add(id)
            }
            Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(resp.body()).forEach {
                val id = it.groupValues[1]
                if (id !in instanceIds && (id.contains(model, ignoreCase = true) || model.contains(id, ignoreCase = true))) instanceIds.add(id)
            }
        }
    }
    val bodies = instanceIds.distinct().map { "{\"instance_id\":${quote(it)}}" }
    val paths = listOf("/api/v1/models/unload", "/api/v0/models/unload", "/v1/models/unload")
    for (path in paths) {
        for (body in bodies) {
            val ok = runCatching {
                val req = HttpRequest.newBuilder(URI.create(origin + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                resp.statusCode() in 200..299
            }.getOrDefault(false)
            if (ok) return true
        }
    }
    return false
  }

  private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

  private suspend fun cancellableResponse(request: HttpRequest): String {
    val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    inFlight.set(future)
    try {
      val response = withContext(Dispatchers.IO) { future.await() }
      val body = response.body().orEmpty()
      if (response.statusCode() !in 200..299) {
        val err = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        throw IllegalStateException("LM Studio request failed: ${response.statusCode()} ${err ?: body.take(600)}")
      }
      if (body.isBlank()) error("LM Studio returned empty response")
      return body
    } catch (e: CancellationException) {
      future.cancel(true)
      throw e
    } finally {
      inFlight.compareAndSet(future, null)
    }
  }

  private fun response(request: HttpRequest): String {
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    val body = response.body().orEmpty()
    if (response.statusCode() !in 200..299) {
      val err = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
      throw IllegalStateException("LM Studio request failed: ${response.statusCode()} ${err ?: body.take(600)}")
    }
    if (body.isBlank()) error("LM Studio returned empty response")
    return body
  }

  private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
