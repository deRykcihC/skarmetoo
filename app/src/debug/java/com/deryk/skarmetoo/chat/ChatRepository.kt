package com.deryk.skarmetoo.chat

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.deryk.skarmetoo.ai.EmbeddingGemma
import com.deryk.skarmetoo.data.ScreenshotDatabase
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.data.ScreenshotTextEmbeddingDatabase
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ChatRetrieval(
    val candidates: List<ChatCandidate>,
    val mode: String,
    val missingDates: Int,
    val libraryCount: Int,
    val relevantCount: Int
)

class ChatRepository(private val context: Context) {
  val numericDateOrder: ChatReceiptDateOrder
    get() = ChatReceiptDates.get(context)

  private val screenshots = ScreenshotDatabase(context)
  private val vectors = ScreenshotTextEmbeddingDatabase(context)
  private val embedding = EmbeddingGemma(context)
  private var initialized = false
  private var imageTextReader: ChatImageTextReader? = null
  private val dateReader = ChatImageDateReader(context)

  suspend fun retrieve(
      query: ChatQuery,
      generatedSearchText: String? = null,
      onProgress: (String) -> Unit
  ): ChatRetrieval =
      withContext(Dispatchers.IO) {
        val entries =
            screenshots.getAllEntries().filter { !it.isAnalyzing && it.imageUri.isNotBlank() }
        val allowed = entries.associateBy { it.imageUri }
        val scores = mutableMapOf<String, Float>()
        var mode = "Keyword search"
        onProgress("Filtering ${entries.size} saved descriptions · no image processing")
        if (EmbeddingGemma.hasRequiredFiles(context)) {
          onProgress("Searching saved semantic context · no image processing")
          if (!initialized) initialized = embedding.initialize()
          val searchText =
              if (generatedSearchText != null) generatedSearchText.take(1800)
              else if (query.spending)
                  "Completed purchase receipt payment transaction expense total ${query.terms.joinToString(" ")}"
              else query.question.take(1000)
          val vector = if (initialized) embedding.embedQuery(searchText) else null
          if (vector != null) {
            vectors
                .findSimilar(vector, similarityThreshold = 0.45f, limit = Int.MAX_VALUE)
                .forEach { (record, score) ->
                  val entry = allowed[record.imageUri]
                  if (entry != null &&
                      record.contentHash == EmbeddingGemma.buildSearchText(entry).hashCode())
                      scores[entry.imageUri] = score
                }
            mode = "Semantic + keyword search"
          }
        }
        // Only relevant descriptions reach metadata lookup; a matching date alone never triggers
        // vision.
        val relevant =
            entries.filter { ChatCandidateSelector.relevant(query, it, scores[it.imageUri] ?: 0f) }
        val dates = mutableMapOf<String, LocalDate?>()
        val metadata = mutableMapOf<String, ChatImageDates>()
        relevant.forEachIndexed { index, entry ->
          currentCoroutineContext().ensureActive()
          val sourceDates = imageDates(entry.imageUri)
          metadata[entry.imageUri] = sourceDates
          dates[entry.imageUri] = sourceDates.forQuery(query)
          if (index % 25 == 0)
              onProgress(
                  "Filtering dates ${index + 1}/${relevant.size} relevant images · no image processing")
        }
        val candidates =
            ChatCandidateSelector.select(query, relevant, dates, scores, metadata, numericDateOrder)
        ChatRetrieval(
            if (query.spending) candidates else candidates.take(10),
            mode,
            relevant.count { dates[it.imageUri] == null },
            entries.size,
            relevant.size)
      }

  fun imageDates(uriText: String): ChatImageDates = dateReader.read(uriText)

  suspend fun loadImage(entry: ScreenshotEntry) = loadImage(entry, 1536)

  private suspend fun loadImage(entry: ScreenshotEntry, maximumDimension: Int) =
      withContext(Dispatchers.IO) {
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.contentResolver, Uri.parse(entry.imageUri))) {
                decoder,
                info,
                _ ->
              decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
              val scale =
                  minOf(1f, maximumDimension.toFloat() / maxOf(info.size.width, info.size.height))
              decoder.setTargetSize(
                  maxOf(1, (info.size.width * scale).toInt()),
                  maxOf(1, (info.size.height * scale).toInt()))
            }
      }

  suspend fun imageText(bitmap: android.graphics.Bitmap): String =
      withContext(Dispatchers.IO) {
        val reader = imageTextReader ?: ChatImageTextReader().also { imageTextReader = it }
        reader.read(bitmap)
      }

  suspend fun imageText(entry: ScreenshotEntry, visionImage: android.graphics.Bitmap): String {
    val first = imageText(visionImage)
    val unreadablePaid =
        ChatPrintedPaidAmounts.hasLabel(first) && ChatPrintedPaidAmounts.find(first) == null
    val unreadableDate =
        ChatPrintedEventDates.hasLabel(first) &&
            ChatPrintedEventDates.find(first, numericDateOrder) == null
    if (!unreadablePaid && !unreadableDate) return first
    // Re-read small labeled digits at their original resolution; never replace letters with
    // guesses.
    // Retain both observations so conflicting readable values remain a conflict.
    val image = loadImage(entry, 4096)
    return try {
      "$first\n\nHigher-resolution reading of the same image:\n${imageText(image)}"
    } finally {
      image.recycle()
    }
  }

  /**
   * Byte identity, not the library's perceptual dHash, for cache freshness and duplicate evidence.
   */
  suspend fun imageContentHash(entry: ScreenshotEntry): String =
      withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(entry.imageUri)).use { stream ->
          requireNotNull(stream) { "Could not read the source image." }
          val buffer = ByteArray(32768)
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
          }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      }

  fun close() {
    imageTextReader?.close()
    imageTextReader = null
    screenshots.close()
    vectors.close()
    embedding.close()
  }
}
