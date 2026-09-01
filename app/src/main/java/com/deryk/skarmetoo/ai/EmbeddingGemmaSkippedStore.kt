package com.deryk.skarmetoo.ai

import android.content.Context

object EmbeddingGemmaSkippedStore {
  private const val PREFS_NAME = "embedding_gemma_skipped"
  private const val KEY_TOO_LONG_ENTRY_IDS = "too_long_entry_ids"

  fun getEntryIds(context: Context): Set<Long> =
      context
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getStringSet(KEY_TOO_LONG_ENTRY_IDS, emptySet())
          .orEmpty()
          .mapNotNullTo(linkedSetOf()) { it.toLongOrNull() }

  fun saveEntryIds(context: Context, entryIds: Set<Long>) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_TOO_LONG_ENTRY_IDS, entryIds.mapTo(linkedSetOf()) { it.toString() })
        .apply()
  }
}
