package com.deryk.skarmetoo.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.Uri
import com.deryk.skarmetoo.data.ScreenshotDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

data class LanProgress(
    val connected: Boolean = false,
    val total: Int = 0,
    val sent: Int = 0,
    val processed: Int = 0,
    val failed: Int = 0,
    val isRunning: Boolean = false,
    val lastError: String? = null,
    val settings: DesktopJobSettings = DesktopJobSettings(),
)

class LanServer(
    private val context: Context,
    private val database: ScreenshotDatabase,
    private val settingsProvider: () -> DesktopJobSettings,
    private val onResultApplied: () -> Unit,
    private val onProgressChanged: (LanProgress) -> Unit,
    private val onStateChanged: (State) -> Unit,
) {
  enum class State {
    STOPPED,
    RUNNING,
    ERROR
  }

  private val running = AtomicBoolean(false)
  private var serverSocket: ServerSocket? = null
  private var executor: ExecutorService? = null
  @Volatile private var sessionToken: String? = null
  @Volatile private var jobId: String? = null
  @Volatile private var jobEntries: List<String> = emptyList()
  private val processedHashes = mutableSetOf<String>()
  @Volatile private var progress = LanProgress()
  @Volatile private var jobSettings = DesktopJobSettings()
  private val progressLock = Any()

  fun start() {
    if (!running.compareAndSet(false, true)) return
    executor = Executors.newCachedThreadPool()
    executor?.execute {
      try {
        serverSocket = ServerSocket(LanProtocol.PORT)
        onStateChanged(State.RUNNING)
        emitProgress(progress.copy(connected = true))
        while (running.get()) {
          val socket = serverSocket?.accept() ?: break
          executor?.execute { handle(socket) }
        }
      } catch (_: Exception) {
        if (running.get()) onStateChanged(State.ERROR)
      } finally {
        running.set(false)
        serverSocket = null
        sessionToken = null
        jobId = null
        jobEntries = emptyList()
        executor?.shutdownNow()
        executor = null
        emitProgress(LanProgress())
        onStateChanged(State.STOPPED)
      }
    }
  }

  fun stop() {
    running.set(false)
    sessionToken = null
    jobId = null
    jobEntries = emptyList()
    runCatching { serverSocket?.close() }
  }

  fun hasActiveSession(): Boolean = sessionToken != null

  fun isRunning(): Boolean = running.get()

  fun startJob() {
    if (!running.get()) return
    synchronized(progressLock) { if (progress.isRunning && jobId != null) cancelJob() }
    resetJobIfIdle()
    val entries = database.getAllEntries().filter { it.isDesktopAnalysisCandidate() }
    val settings = settingsProvider().copy(customPrompt = settingsProvider().customPrompt.take(500))
    synchronized(progressLock) {
      if (progress.isRunning) return
      jobId = UUID.randomUUID().toString()
      jobSettings = settings
      jobEntries = entries.map { it.imageHash }
      processedHashes.clear()
      progress =
          LanProgress(connected = true, total = entries.size, isRunning = true, settings = settings)
    }
    emitProgress(progress)
  }

  fun cancelJob() {
    synchronized(progressLock) { progress = progress.copy(isRunning = false) }
    emitProgress(progress)
  }

  fun resetJobIfIdle() {
    synchronized(progressLock) {
      if (!progress.isRunning) {
        jobId = null
        jobEntries = emptyList()
        processedHashes.clear()
      }
    }
  }

  fun localAddress(): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    connectivity?.allNetworks?.forEach { network ->
      val capabilities = connectivity.getNetworkCapabilities(network)
      if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
        connectivity.getLinkProperties(network)?.linkAddresses?.forEach { address: LinkAddress ->
          val host = address.address
          if (host is Inet4Address && !host.isLoopbackAddress) return host.hostAddress
        }
      }
    }
    return runCatching {
          NetworkInterface.getNetworkInterfaces()
              .toList()
              .asSequence()
              .flatMap { it.inetAddresses.toList().asSequence() }
              .filterIsInstance<Inet4Address>()
              .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
              ?.hostAddress
        }
        .getOrNull()
  }

  private fun handle(socket: Socket) {
    socket.use {
      it.soTimeout = 30_000
      val input = BufferedInputStream(it.getInputStream())
      val output = BufferedOutputStream(it.getOutputStream())
      val request = readRequest(input) ?: return
      val path = request.path.substringBefore('?')
      val authorized =
          request.headers["authorization"] == "Bearer $sessionToken" && sessionToken != null
      when {
        request.method == "GET" && path == "/v1/health" ->
            respondJson(
                output,
                200,
                JSONObject().put("version", LanProtocol.VERSION).put("state", "running"))
        request.method == "POST" && path == "/v1/session" -> {
          sessionToken = UUID.randomUUID().toString()
          val resp = JSONObject().put("version", LanProtocol.VERSION).put("token", sessionToken)
          respondJson(output, 200, resp)
          emitProgress(progress)
        }
        !authorized ->
            respondJson(output, 401, errorJson("unauthorized", "Session is missing or expired"))
        request.method == "POST" && path == "/v1/desktop/job" -> startJobResponse(output)
        request.method == "GET" && path == "/v1/desktop/status" ->
            respondJson(output, 200, progressJson())
        request.method == "POST" && path == "/v1/desktop/cancel" -> {
          cancelJob()
          resetJobIfIdle()
          respondJson(output, 200, progressJson())
        }
        request.method == "GET" && path == "/v1/entries" -> {
          synchronized(progressLock) {
            if (!progress.isRunning && jobId != null && progress.processed >= progress.total) {
              resetJobIfIdle()
            }
          }
          entriesResponse(output)
        }
        request.method == "GET" && path.startsWith("/v1/entries/") && path.endsWith("/image") -> {
          val encodedHash = path.removePrefix("/v1/entries/").removeSuffix("/image").trim('/')
          streamImage(
              output,
              runCatching { URLDecoder.decode(encodedHash, StandardCharsets.UTF_8.name()) }
                  .getOrNull())
        }
        request.method == "POST" && path == "/v1/results" -> applyResults(output, request.body)
        request.method == "POST" && path == "/v1/session/close" -> {
          sessionToken = null
          cancelJob()
          respondJson(output, 200, JSONObject().put("ok", true))
          emitProgress(progress)
        }
        else -> respondJson(output, 404, errorJson("not_found", "Endpoint not found"))
      }
    }
  }

  private fun startJobResponse(output: BufferedOutputStream) {
    startJob()
    respondJson(output, 200, progressJson().put("jobId", jobId))
  }

  private fun entriesResponse(output: BufferedOutputStream) {
    val entries =
        database
            .getAllEntries()
            .filter { it.isDesktopAnalysisCandidate() }
            .filter { jobId == null || it.imageHash in jobEntries }
    respondJson(
        output,
        200,
        JSONObject().apply {
          put("version", LanProtocol.VERSION)
          put("jobId", jobId)
          put("entries", JSONArray().apply { entries.forEach { put(LanProtocol.entryJson(it)) } })
          put("progress", progressJson())
          put("settings", LanProtocol.settingsJson(jobSettings))
        })
  }

  private fun streamImage(output: BufferedOutputStream, hash: String?) {
    synchronized(progressLock) {
      if (!progress.isRunning && jobId != null) {
        respondJson(output, 409, errorJson("job_cancelled", "Desktop job was cancelled"))
        return
      }
    }
    if (hash.isNullOrBlank()) {
      respondJson(output, 400, errorJson("invalid_hash", "Image hash is empty"))
      return
    }
    val entry = database.getEntryByHash(hash)
    if (entry == null || entry.imageUri.isBlank()) {
      incrementFailed("image_not_found")
      respondJson(
          output,
          404,
          errorJson("image_not_found", "No readable entry exists for this image hash")
              .put("imageHash", hash))
      return
    }
    if (jobId != null && hash !in jobEntries) {
      incrementFailed("image_not_in_job")
      respondJson(
          output,
          404,
          errorJson("image_not_in_job", "Image is not part of the active desktop job")
              .put("imageHash", hash))
      return
    }
    val uri = Uri.parse(entry.imageUri)
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val bytes =
        runCatching { resolver.openInputStream(uri)?.use(InputStream::readBytes) }.getOrNull()
    if (bytes == null) {
      incrementFailed("image_unreadable")
      respondJson(
          output,
          404,
          errorJson("image_unreadable", "Android could not read the image URI")
              .put("imageHash", hash))
      return
    }
    if (bytes.size > MAX_IMAGE_BYTES) {
      incrementFailed("image_too_large")
      respondJson(
          output,
          413,
          errorJson("image_too_large", "Image exceeds the transfer limit").put("imageHash", hash))
      return
    }
    val header =
        "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    output.write(header.toByteArray(Charsets.UTF_8))
    output.write(bytes)
    output.flush()
    synchronized(progressLock) {
      progress = progress.copy(sent = (progress.sent + 1).coerceAtMost(progress.total))
    }
    emitProgress(progress)
  }

  private fun applyResults(output: BufferedOutputStream, body: ByteArray) {
    synchronized(progressLock) {
      if (!progress.isRunning && jobId != null) {
        respondJson(output, 409, errorJson("job_cancelled", "Desktop job was cancelled"))
        return
      }
    }
    if (body.size > LanProtocol.MAX_RESULT_BYTES) {
      respondJson(output, 413, errorJson("payload_too_large", "Result payload exceeds the limit"))
      return
    }
    runCatching {
          val root = JSONObject(String(body, Charsets.UTF_8))
          val requestJobId = root.optString("jobId")
          if (jobId != null && requestJobId != jobId) error("job_mismatch")
          val entries = root.getJSONArray("entries")
          var accepted = 0
          for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val hash = item.getString("imageHash")
            if (database.getEntryByHash(hash) == null || (jobId != null && hash !in jobEntries)) {
              incrementFailed("unknown_hash")
              continue
            }
            val shouldApply = synchronized(progressLock) { processedHashes.add(hash) }
            if (shouldApply) {
              database.applyLanResult(
                  hash,
                  item.optString("summary"),
                  item.optString("tags"),
                  item.optLong("analyzedAt", System.currentTimeMillis()),
                  item.optString("note"),
                  item.optString("modelUsed"))
              accepted++
            }
          }
          if (accepted > 0) {
            synchronized(progressLock) {
              progress =
                  progress.copy(
                      processed = (progress.processed + accepted).coerceAtMost(progress.total))
            }
            onResultApplied()
          }
          if (progress.total > 0 && progress.processed >= progress.total) {
            synchronized(progressLock) { progress = progress.copy(isRunning = false) }
          }
          emitProgress(progress)
          respondJson(
              output, 200, JSONObject().put("accepted", accepted).put("progress", progressJson()))
        }
        .onFailure {
          respondJson(
              output, 400, errorJson("invalid_results", it.message ?: "Invalid result payload"))
        }
  }

  private fun incrementFailed(error: String) {
    synchronized(progressLock) {
      progress = progress.copy(failed = progress.failed + 1, lastError = error)
    }
    emitProgress(progress)
  }

  private fun emitProgress(value: LanProgress) {
    progress = value
    onProgressChanged(value.copy(connected = sessionToken != null))
  }

  private fun progressJson(): JSONObject =
      synchronized(progressLock) {
        JSONObject()
            .put("jobId", jobId)
            .put("connected", running.get())
            .put("total", progress.total)
            .put("sent", progress.sent)
            .put("processed", progress.processed)
            .put("failed", progress.failed)
            .put("isRunning", progress.isRunning)
            .put("lastError", progress.lastError)
            .put("settings", LanProtocol.settingsJson(jobSettings))
      }

  private fun errorJson(error: String, message: String): JSONObject =
      JSONObject().put("error", error).put("message", message)

  private data class Request(
      val method: String,
      val path: String,
      val headers: Map<String, String>,
      val body: ByteArray
  )

  private fun readRequest(input: InputStream): Request? {
    val headerBytes = ByteArrayOutputStream()
    var previous = 0
    while (headerBytes.size() < 16 * 1024) {
      val current = input.read()
      if (current < 0) return null
      headerBytes.write(current)
      if (previous == '\r'.code && current == '\n'.code) {
        val bytes = headerBytes.toByteArray()
        if (bytes.size >= 4 &&
            bytes[bytes.size - 4] == '\r'.code.toByte() &&
            bytes[bytes.size - 3] == '\n'.code.toByte())
            break
      }
      previous = current
    }
    val lines = String(headerBytes.toByteArray(), Charsets.ISO_8859_1).trim().split("\r\n")
    val requestLine = lines.firstOrNull()?.split(' ') ?: return null
    if (requestLine.size < 2) return null
    val headers =
        lines
            .drop(1)
            .mapNotNull { line ->
              val separator = line.indexOf(':')
              if (separator <= 0) null
              else line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()
    val length =
        headers["content-length"]?.toIntOrNull()?.coerceIn(0, LanProtocol.MAX_RESULT_BYTES) ?: 0
    val body = ByteArray(length)
    var offset = 0
    while (offset < length) {
      val count = input.read(body, offset, length - offset)
      if (count < 0) return null
      offset += count
    }
    return Request(requestLine[0], requestLine[1], headers, body)
  }

  private fun respondJson(output: BufferedOutputStream, status: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    val reason = if (status == 200) "OK" else if (status == 401) "Unauthorized" else "Error"
    output.write(
        "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            .toByteArray())
    output.write(bytes)
    output.flush()
  }
}
