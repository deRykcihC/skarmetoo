package com.deryk.skarmetoo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LmStudioPromptTest {
  private val client = LmStudioClient()

  @Test
  fun buildsBriefPromptInSelectedLanguage() {
    val prompt = client.buildPrompt(OllamaJobSettings(languageName = "Spanish", detailLevel = "BRIEF"))
    assertTrue(prompt.contains("briefly in Spanish"))
    assertTrue(prompt.contains("one sentence"))
  }

  @Test
  fun buildsDetailedPrompt() {
    val prompt = client.buildPrompt(OllamaJobSettings(detailLevel = "DETAILED"))
    assertTrue(prompt.contains("2-3 sentences"))
    assertTrue(prompt.contains("tag5"))
  }

  @Test
  fun buildsFullPromptFromComprehensive() {
    val prompt = client.buildPrompt(OllamaJobSettings(detailLevel = "COMPREHENSIVE"))
    assertTrue(prompt.contains("maximum detail"))
    assertTrue(prompt.contains("tag8"))
  }

  @Test
  fun buildsCustomPromptWithFallback() {
    val custom = client.buildPrompt(OllamaJobSettings(languageName = "Hindi", detailLevel = "CUSTOM", customPrompt = "Read the visible title."))
    assertTrue(custom.startsWith("Read the visible title."))
    assertTrue(custom.contains("Hindi"))
  }

  @Test
  fun parsesMobileTextContract() {
    assertEquals("A receipt" to "receipt, shopping", client.parseAnalysisResult("SUMMARY: A receipt\nTAGS: receipt, shopping"))
  }

  @Test
  fun parsesJsonFallback() {
    assertEquals("A receipt" to "receipt, shopping", client.parseAnalysisResult("{\"summary\":\"A receipt\",\"tags\":[\"receipt\",\"shopping\"]}"))
  }

  @Test
  fun parsesUppercaseJsonAndEscapedText() {
    val parsed = client.parseAnalysisResult("{\"SUMMARY\":\"Line one\\nLine two\",\"TAGS\":\"receipt\"}")
    assertEquals("Line one\nLine two", parsed.first)
    assertEquals("receipt", parsed.second)
  }
}
