package com.deryk.skarmetoo.data

data class ScreenshotEntry(
    val id: Long = 0,
    val imageUri: String = "",
    val imageHash: String,
    val summary: String = "",
    val tags: String = "",
    val analyzedAt: Long = 0,
    val isAnalyzing: Boolean = false,
    val note: String = "",
    val modelUsed: String = "",
) {
  val sortKey: Long
    get() {
      val mediaStoreId = imageUri.substringAfterLast("/").toLongOrNull()
      return if (mediaStoreId != null && mediaStoreId > 0) mediaStoreId else id
    }

  fun getTagList(): List<String> {
    return if (tags.isBlank()) {
      emptyList()
    } else {
      tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
  }

  fun isDesktopAnalysisCandidate(): Boolean {
    if (imageUri.isBlank() || imageHash.isBlank() || isAnalyzing) return false

    // Explicit reanalysis keeps the old description visible while resetting its timestamp.
    val isUnprocessed = analyzedAt == 0L
    val isGeminiNanoRejected =
        modelUsed.equals("Gemini Nano", ignoreCase = true) &&
            getTagList().any { it.equals("restricted", ignoreCase = true) }

    return isUnprocessed || isGeminiNanoRejected
  }
}
