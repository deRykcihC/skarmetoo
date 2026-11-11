package com.deryk.skarmetoo.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the installed phone's date parser without running GGUF inference or editing the library.
 */
@RunWith(AndroidJUnit4::class)
class ChatQueryDeviceTest {
  @Test
  fun pastWeekCountsBackFromThePhonesToday() {
    val today = LocalDate.now()
    val query = ChatQuery.parse("how much did I spend past last week?", today)
    assertEquals(today.minusDays(7), query.start)
    assertEquals(today.plusDays(1), query.endExclusive)
    assertTrue(query.includes(today))
    assertTrue(query.includes(today.minusDays(7)))
    assertFalse(query.includes(today.minusDays(8)))
    val october = ChatQuery.parse("How much did I spend last week?", LocalDate.parse("2026-10-04"))
    assertEquals("2026-09-27 to 2026-10-04", october.period)
    val report =
        JSONObject()
            .put("today", today.toString())
            .put("timezone", ZoneId.systemDefault().id)
            .put("period", query.period)
            .put("october_4_period", october.period)
            .put("passed", true)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    File(context.cacheDir, "chat-query-device.json").writeText(report.toString(2))
  }
}
