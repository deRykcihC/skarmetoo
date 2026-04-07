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
    fun getTagList(): List<String> {
        return if (tags.isBlank()) {
            emptyList()
        } else {
            tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }
}
