package com.deryk.skarmetoo.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AicoreManager"

class AicoreManager private constructor(private val context: Context) {
  private val scope = CoroutineScope(Dispatchers.IO)
  private var generativeModel: GenerativeModel? = null

  private val _uiState = MutableStateFlow<AicoreState>(AicoreState.Initial)
  val uiState: StateFlow<AicoreState> = _uiState.asStateFlow()

  companion object {
    @Volatile private var instance: AicoreManager? = null

    fun getInstance(context: Context): AicoreManager {
      return instance
          ?: synchronized(this) {
            instance ?: AicoreManager(context.applicationContext).also { instance = it }
          }
    }
  }

  init {
    try {
      generativeModel = Generation.getClient()
      Log.d(TAG, "Successfully retrieved ML Kit Generation client")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to retrieve ML Kit Generation client", e)
    }
  }

  /**
   * Check status of on-device AICore / Gemini Nano model. Returns a FeatureStatus, or unavailable
   * if initialization failed.
   */
  suspend fun checkStatus(): Int =
      withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext FeatureStatus.UNAVAILABLE
        return@withContext try {
          val status = model.checkStatus()
          Log.d(TAG, "AICore Gemini Nano status: $status")
          status
        } catch (e: Exception) {
          Log.e(TAG, "Failed to check AICore status", e)
          FeatureStatus.UNAVAILABLE
        }
      }

  /**
   * Triggers a model download if status is DOWNLOADABLE. Calls generativeModel.download() which
   * handles Play Services background download.
   */
  fun requestDownload() {
    val model = generativeModel ?: return
    scope.launch {
      try {
        if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) {
          Log.d(TAG, "Requesting AICore Gemini Nano download...")
          _uiState.value = AicoreState.Downloading
          model.download().collect { status ->
            Log.d(TAG, "AICore Download status: $status")
            // Update state according to availability status
            val check = model.checkStatus()
            if (check == FeatureStatus.AVAILABLE) {
              _uiState.value = AicoreState.Ready
            } else if (check == FeatureStatus.UNAVAILABLE) {
              _uiState.value = AicoreState.Error("AICore download failed or is unavailable.")
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed during AICore download request", e)
        _uiState.value = AicoreState.Error("Download request failed: ${e.message}")
      }
    }
  }

  /** Perform basic text diagnostics to check if the Gemini Nano model responds. */
  suspend fun testInference(prompt: String): Result<String> =
      withContext(Dispatchers.IO) {
        val model =
            generativeModel
                ?: return@withContext Result.failure(Exception("AICore client not initialized"))
        try {
          val status = model.checkStatus()
          if (status != FeatureStatus.AVAILABLE) {
            return@withContext Result.failure(
                Exception("Model is not ready. Current status: $status"))
          }

          val request = GenerateContentRequest.Builder(TextPart(prompt)).build()

          Log.d(TAG, "Running diagnostic inference with prompt: '$prompt'")
          val response = model.generateContent(request)
          val responseText = response.candidates.firstOrNull()?.text ?: ""

          Log.d(TAG, "Diagnostic inference completed. Response length: ${responseText.length}")
          Result.success(responseText)
        } catch (e: Exception) {
          Log.e(TAG, "Diagnostic inference failed", e)
          Result.failure(e)
        }
      }

  /** Multimodal screenshot analysis. */
  suspend fun analyzeScreenshot(bitmap: Bitmap, prompt: String): Result<Pair<String, String>> =
      withContext(Dispatchers.IO) {
        val model =
            generativeModel
                ?: return@withContext Result.failure(Exception("AICore client not initialized"))
        try {
          val status = model.checkStatus()
          if (status != FeatureStatus.AVAILABLE) {
            return@withContext Result.failure(
                Exception("Model is not ready. Current status: $status"))
          }

          val request = GenerateContentRequest.Builder(ImagePart(bitmap), TextPart(prompt)).build()

          Log.d(TAG, "Sending multimodal request to AICore (Gemini Nano)...")
          val response = model.generateContent(request)
          val responseText = response.candidates.firstOrNull()?.text ?: ""

          // Basic parse logic for SUMMARY and TAGS
          val parsed = parseAnalysisResult(responseText)
          Result.success(parsed)
        } catch (e: Exception) {
          Log.e(TAG, "Multimodal inference failed", e)
          Result.failure(e)
        }
      }

  private fun parseAnalysisResult(result: String): Pair<String, String> {
    var summary = ""
    var tags = ""

    val cleaned = result.replace(Regex("```(json)?\\s*"), "").replace(Regex("```\\s*$"), "").trim()

    // 1. Standard SUMMARY extraction (support SUMMARY: or Summary:)
    val summaryMatch =
        Regex(
                "(?i)SUMMARY\\s*:\\s*(.*?)(?=\\s*(?:TAGS|KEYWORDS|\\s*$))",
                RegexOption.DOT_MATCHES_ALL)
            .find(cleaned)
    if (summaryMatch != null) {
      summary = summaryMatch.groupValues[1].trim().trim('"').trim('[', ']')
    }

    // 2. Resilient TAGS extraction (support Tags:, TAGS:, Keywords:, tags -, etc.)
    val tagsMatch =
        Regex("(?i)(?:TAGS|KEYWORDS)\\s*[:\\-]\\s*(.*)", RegexOption.DOT_MATCHES_ALL).find(cleaned)
    if (tagsMatch != null) {
      tags = tagsMatch.groupValues[1].trim().trim('"').trim('[', ']')
    }

    // 3. Hashtag Fallback: if no tags were matched, try to extract all words starting with '#'
    if (tags.isBlank()) {
      val hashtagRegex = Regex("#(\\w+)")
      val hashtags = hashtagRegex.findAll(cleaned).map { it.groupValues[1] }.toList()
      if (hashtags.isNotEmpty()) {
        tags = hashtags.joinToString(", ")
      }
    }

    // 4. Summary Fallback: if summary couldn't be extracted, use the whole cleaned text minus any
    // tags line
    if (summary.isBlank() && cleaned.isNotBlank()) {
      val firstLine = cleaned.lines().firstOrNull { it.isNotBlank() } ?: ""
      summary = if (firstLine.length > 30) firstLine else cleaned.replace("\n", " ").take(1000)
    }

    // 5. Intelligent Tag Fallback: if tags are still blank, extract key nouns/capitalized terms
    // from the summary
    if (tags.isBlank() && summary.isNotBlank()) {
      // Find capitalized words (often brand names, app names, nouns)
      val capitalizedWords =
          Regex("\\b[A-Z][a-zA-Z0-9_]{2,}\\b")
              .findAll(summary)
              .map { it.value }
              .filterNot {
                it.lowercase() in
                    listOf("this", "the", "image", "screenshot", "there", "some", "what", "where")
              }
              .distinct()
              .take(4)
              .toList()

      if (capitalizedWords.isNotEmpty()) {
        tags = capitalizedWords.joinToString(", ")
      } else {
        // Fallback to basic words if no capitalized ones exist (take first 3 nouns/adjectives of 4+
        // letters)
        val basicWords =
            summary
                .split(Regex("[^a-zA-Z]"))
                .map { it.trim() }
                .filter { it.length >= 4 }
                .filterNot {
                  it.lowercase() in
                      listOf(
                          "this",
                          "that",
                          "with",
                          "from",
                          "show",
                          "here",
                          "image",
                          "screenshot",
                          "display",
                          "user",
                          "view",
                          "could",
                          "have",
                          "would")
                }
                .distinct()
                .take(3)
                .toList()
        tags = basicWords.joinToString(", ")
      }
    }

    tags = cleanTags(tags)
    return summary to tags
  }

  private fun cleanTags(raw: String): String {
    return raw.replace("[", "")
        .replace("]", "")
        .replace("\"", "")
        .replace("'", "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")
  }

  sealed class AicoreState {
    object Initial : AicoreState()

    object Downloading : AicoreState()

    object Ready : AicoreState()

    data class Error(val message: String) : AicoreState()
  }
}
