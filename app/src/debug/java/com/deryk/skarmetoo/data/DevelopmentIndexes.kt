package com.deryk.skarmetoo.data

import android.content.Context
import com.deryk.skarmetoo.ai.agentic.AgenticIndex
import com.deryk.skarmetoo.chat.ChatWorkflowCache

internal object DevelopmentIndexes {
  val uriTables =
      listOf(
          Triple("agentic_rag.db", "images", "uri"),
          Triple("chat_workflow.db", "responses", "image_uri"),
      )

  fun prune(context: Context, entries: List<ScreenshotEntry>, imageRemoved: Boolean): Int {
    var removed = 0
    if (context.getDatabasePath("agentic_rag.db").exists()) {
      AgenticIndex(context).use { removed += it.pruneToEntries(entries, imageRemoved) }
    }
    if (context.getDatabasePath("chat_workflow.db").exists()) {
      ChatWorkflowCache(context).use {
        removed += it.pruneToUris(entries.mapTo(hashSetOf()) { entry -> entry.imageUri })
      }
    }
    return removed
  }
}
