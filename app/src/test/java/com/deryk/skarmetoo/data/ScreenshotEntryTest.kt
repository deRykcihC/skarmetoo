package com.deryk.skarmetoo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotEntryTest {
  @Test
  fun blankUnprocessedEntryIsEligible() {
    assertTrue(entry(analyzedAt = 0L).isDesktopAnalysisCandidate())
  }

  @Test
  fun completedEntryIsNotEligible() {
    assertFalse(
        entry(summary = "Existing context", analyzedAt = 100L, modelUsed = "Gemma 4")
            .isDesktopAnalysisCandidate())
  }

  @Test
  fun restrictedGeminiNanoResultIsEligible() {
    assertTrue(
        entry(
                summary = "Could not analyze",
                analyzedAt = 100L,
                tags = "restricted",
                modelUsed = "Gemini Nano")
            .isDesktopAnalysisCandidate())
  }

  @Test
  fun restrictedOtherModelResultIsNotEligible() {
    assertFalse(
        entry(
                summary = "Could not analyze",
                analyzedAt = 100L,
                tags = "restricted",
                modelUsed = "Gemma 4")
            .isDesktopAnalysisCandidate())
  }

  @Test
  fun invalidReferenceIsNotEligible() {
    assertFalse(entry(imageUri = "").isDesktopAnalysisCandidate())
    assertFalse(entry(imageHash = "").isDesktopAnalysisCandidate())
  }

  @Test
  fun activeEntryIsNotEligible() {
    assertFalse(entry(isAnalyzing = true).isDesktopAnalysisCandidate())
  }

  private fun entry(
      imageUri: String = "content://image/1",
      imageHash: String = "hash-1",
      summary: String = "",
      tags: String = "",
      analyzedAt: Long = 0L,
      isAnalyzing: Boolean = false,
      modelUsed: String = "",
  ) =
      ScreenshotEntry(
          imageUri = imageUri,
          imageHash = imageHash,
          summary = summary,
          tags = tags,
          analyzedAt = analyzedAt,
          isAnalyzing = isAnalyzing,
          modelUsed = modelUsed)
}
