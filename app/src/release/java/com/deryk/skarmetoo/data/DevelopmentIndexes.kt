package com.deryk.skarmetoo.data

import android.content.Context

internal object DevelopmentIndexes {
  val uriTables = emptyList<Triple<String, String, String>>()

  fun prune(context: Context, entries: List<ScreenshotEntry>, imageRemoved: Boolean): Int {
    // Discard regenerable indexes from older development builds without shipping their code.
    // This also prevents removed images leaving cached observations behind after an upgrade.
    return listOf("agentic_rag.db", "chat_workflow.db", "chat_receipts.db").count {
      context.getDatabasePath(it).exists() && context.deleteDatabase(it)
    }
  }
}
