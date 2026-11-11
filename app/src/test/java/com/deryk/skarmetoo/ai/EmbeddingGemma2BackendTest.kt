package com.deryk.skarmetoo.ai

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class EmbeddingGemma2BackendTest {
  @Test
  fun retrievalInputsUseDifferentTaskPrefixes() {
    assertEquals(
        "task: search result | query: red car", EmbeddingGemma2Backend.queryInput("red car"))
    assertEquals("title: none | text: red car", EmbeddingGemma2Backend.documentInput("red car"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun blankQueryIsRejected() {
    EmbeddingGemma2Backend.queryInput("  ")
  }

  @Test(expected = IllegalArgumentException::class)
  fun blankDocumentIsRejected() {
    EmbeddingGemma2Backend.documentInput("")
  }

  @Test
  fun checksumUsesSha256() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        EmbeddingGemma2Backend.sha256(ByteArrayInputStream("abc".toByteArray())))
  }

  @Test(expected = IllegalArgumentException::class)
  fun differentVectorDimensionsAreRejected() {
    EmbeddingGemma2Backend.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f))
  }

  @Test
  fun matchingVectorsHaveUnitSimilarity() {
    assertEquals(
        1.0, EmbeddingGemma2Backend.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)), 0.000001)
  }
}
