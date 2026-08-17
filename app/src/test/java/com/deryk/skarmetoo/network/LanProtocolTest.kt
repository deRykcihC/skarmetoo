package com.deryk.skarmetoo.network

import org.junit.Assert.assertEquals
import org.junit.Test

class LanProtocolTest {
  @Test
  fun settingsRoundTripPreservesPromptConfiguration() {
    val original =
        DesktopJobSettings(
            "zh-rTW", "Traditional Chinese", "COMPREHENSIVE", "Read every visible label.")
    val restored = LanProtocol.settingsFromJson(LanProtocol.settingsJson(original))
    assertEquals(original, restored)
  }

  @Test
  fun invalidDetailFallsBackToDetailed() {
    val restored = LanProtocol.settingsFromJson(org.json.JSONObject().put("detailLevel", "unknown"))
    assertEquals("DETAILED", restored.detailLevel)
  }
}
