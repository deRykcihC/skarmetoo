package com.deryk.skarmetoo.ai

import android.content.Context

object EmbeddingGemmaSkippedStore {
  private const val PREFS_NAME = "embedding_gemma_skipped"
  private const val KEY_TOO_LONG_ENTRY_IDS = "too_long_entry_ids"

  private fun key(model: SearchModel) =
      if (model == SearchModel.ORIGINAL) KEY_TOO_LONG_ENTRY_IDS
      else "${KEY_TOO_LONG_ENTRY_IDS}_${model.name}"

  fun getEntryIds(context: Context, model: SearchModel = SearchModel.ORIGINAL): Set<Long> =
      context
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getStringSet(key(model), emptySet())
          .orEmpty()
          .mapNotNullTo(linkedSetOf()) { it.toLongOrNull() }

  fun saveEntryIds(
      context: Context,
      entryIds: Set<Long>,
      model: SearchModel = SearchModel.ORIGINAL
  ) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(key(model), entryIds.mapTo(linkedSetOf()) { it.toString() })
        .apply()
  }
}
