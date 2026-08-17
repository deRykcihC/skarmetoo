package com.deryk.skarmetoo.network

import com.deryk.skarmetoo.data.ScreenshotEntry
import org.json.JSONArray
import org.json.JSONObject

data class DesktopJobSettings(
    val languageCode: String = "en",
    val languageName: String = "English",
    val detailLevel: String = "DETAILED",
    val customPrompt: String = "",
)

object LanProtocol {
  const val VERSION = 1
  const val PORT = 18765
  const val MAX_RESULT_BYTES = 256 * 1024

  fun settingsJson(settings: DesktopJobSettings): JSONObject {
    return JSONObject().apply {
      put("languageCode", settings.languageCode)
      put("languageName", settings.languageName)
      put("detailLevel", settings.detailLevel)
      put("customPrompt", settings.customPrompt)
    }
  }

  fun defaultSettings(): DesktopJobSettings = DesktopJobSettings()

  fun settingsFromJson(json: JSONObject?): DesktopJobSettings {
    if (json == null) return defaultSettings()
    val detail = json.optString("detailLevel", "DETAILED").uppercase()
    val normalizedDetail =
        if (detail in setOf("BRIEF", "DETAILED", "COMPREHENSIVE", "CUSTOM")) detail else "DETAILED"
    return DesktopJobSettings(
        languageCode = json.optString("languageCode", "en").ifBlank { "en" },
        languageName = json.optString("languageName", "English").ifBlank { "English" },
        detailLevel = normalizedDetail,
        customPrompt = json.optString("customPrompt").take(500),
    )
  }

  fun entryJson(entry: ScreenshotEntry): JSONObject {
    return JSONObject().apply {
      put("imageHash", entry.imageHash)
      put("summary", entry.summary)
      put("tags", entry.tags)
      put("note", entry.note)
      put("analyzedAt", entry.analyzedAt)
      put("modelUsed", entry.modelUsed)
    }
  }

  fun entriesJson(entries: List<ScreenshotEntry>): JSONObject {
    return JSONObject().apply {
      put("version", VERSION)
      put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } })
    }
  }
}
