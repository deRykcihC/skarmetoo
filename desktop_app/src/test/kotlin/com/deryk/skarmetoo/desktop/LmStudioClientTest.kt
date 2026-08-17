package com.deryk.skarmetoo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class LmStudioClientTest {
  @Test
  fun quoteEscapesJsonCharacters() {
    val client = LmStudioClient()
    val method = LmStudioClient::class.java.getDeclaredMethod("quote", String::class.java)
    method.isAccessible = true
    assertEquals("\"a\\\\b\\\"c\"", method.invoke(client, "a\\b\"c"))
  }
}
