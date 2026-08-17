package com.deryk.skarmetoo.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

data class DesktopEntry(
    val imageHash: String,
    val summary: String,
    val tags: String,
    val note: String,
    val analyzedAt: Long,
    val modelUsed: String,
)

data class ImagePayload(val bytes: ByteArray, val contentType: String)

data class JobInfo(
    val jobId: String?,
    val total: Int,
    val sent: Int,
    val processed: Int,
    val failed: Int,
    val lastError: String? = null,
    val settings: OllamaJobSettings = OllamaJobSettings(),
    val isRunning: Boolean = true,
)

class LanClient {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
  private var baseUrl = ""
  private var token: String? = null

  fun connect(ip: String): String {
    baseUrl = normalizeBaseUrl(ip)
    request("GET", "/v1/health", null, false)
    val session = request("POST", "/v1/session", "{}".toByteArray(), false)
    token = jsonString(session, "token") ?: error("Mobile did not return a session token")
    return token!!
  }

  private fun normalizeBaseUrl(value: String): String {
    val input = value.trim().removeSuffix("/")
    val withScheme = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
    val uri = runCatching { URI.create(withScheme) }.getOrElse { error("Invalid address") }
    require(uri.host != null && uri.host.isNotBlank()) { "Invalid address" }
    val port = if (uri.port == -1) 18765 else uri.port
    require(port in 1..65535) { "Invalid address" }
    return "${uri.scheme}://${uri.authority.substringBeforeLast(":").ifBlank { uri.host }}:$port"
  }

  fun close() {
    if (token != null) runCatching { request("POST", "/v1/session/close", "{}".toByteArray(), true) }
    token = null
    baseUrl = ""
  }

  fun startJob(): JobInfo {
    val body = request("POST", "/v1/desktop/job", "{}".toByteArray(), true)
    return jobInfo(body)
  }

  fun status(): JobInfo {
    val body = request("GET", "/v1/desktop/status", null, true)
    return jobInfo(body)
  }

  private fun jobInfo(body: String): JobInfo {
    val settings = OllamaJobSettings(
        languageCode = jsonString(body, "languageCode") ?: "en",
        languageName = jsonString(body, "languageName") ?: "English",
        detailLevel = jsonString(body, "detailLevel") ?: "DETAILED",
        customPrompt = jsonString(body, "customPrompt") ?: "",
    )
    return JobInfo(
        jsonString(body, "jobId"),
        jsonInt(body, "total"),
        jsonInt(body, "sent"),
        jsonInt(body, "processed"),
        jsonInt(body, "failed"),
        jsonString(body, "lastError"),
        settings,
        isRunning = !body.contains("\"isRunning\":false"),
    )
  }

  fun cancelJob() {
    request("POST", "/v1/desktop/cancel", "{}".toByteArray(), true)
  }

  fun entries(): List<DesktopEntry> {
    val body = request("GET", "/v1/entries", null, true)
    val array = Regex("\\\"entries\\\"\\s*:\\s*\\[(.*)]", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
    return Regex("\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).findAll(array).map { item ->
      val value = item.value
      DesktopEntry(
          jsonString(value, "imageHash").orEmpty(),
          jsonString(value, "summary").orEmpty(),
          jsonString(value, "tags").orEmpty(),
          jsonString(value, "note").orEmpty(),
          jsonLong(value, "analyzedAt"),
          jsonString(value, "modelUsed").orEmpty(),
      )
    }.filter { it.imageHash.isNotBlank() }.toList()
  }

  fun image(hash: String): ImagePayload {
    val response = send("GET", "/v1/entries/${encode(hash)}/image", null, true)
    if (response.statusCode() !in 200..299) {
      val body = String(response.body())
      val error = jsonString(body, "error")
      val message = jsonString(body, "message")
      error("Image request failed (${response.statusCode()}): ${error ?: message ?: body}")
    }
    val bytes = response.body()
    require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large" }
    return ImagePayload(bytes, response.headers().firstValue("content-type").orElse("image/jpeg"))
  }

  fun uploadResults(jobId: String?, entries: List<DesktopEntry>) {
    val json = "{\"version\":1,\"jobId\":${quote(jobId.orEmpty())},\"entries\":[${entries.joinToString(",") { entry ->
      "{\"imageHash\":${quote(entry.imageHash)},\"summary\":${quote(entry.summary)},\"tags\":${quote(entry.tags)},\"note\":${quote(entry.note)},\"analyzedAt\":${entry.analyzedAt},\"modelUsed\":${quote(entry.modelUsed)}}"
    }}]}"
    request("POST", "/v1/results", json.toByteArray(), true)
  }

  private fun request(method: String, path: String, body: ByteArray?, auth: Boolean): String {
    val response = send(method, path, body, auth)
    if (response.statusCode() !in 200..299) error("Mobile request failed: ${response.statusCode()} ${String(response.body())}")
    return String(response.body())
  }

  private fun send(method: String, path: String, body: ByteArray?, auth: Boolean): HttpResponse<ByteArray> {
    val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
    if (auth) builder.header("Authorization", "Bearer ${token ?: error("Not connected")}")
    if (body != null) builder.header("Content-Type", "application/json")
    builder.method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
  }

  private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
  private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
  private fun jsonString(source: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\"\\\\])*)\\\"").find(source)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
  private fun jsonLong(source: String, key: String): Long = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)").find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
  private fun jsonInt(source: String, key: String): Int = jsonLong(source, key).toInt()
}
