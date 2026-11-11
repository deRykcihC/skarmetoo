package com.deryk.skarmetoo.chat

import android.content.Context

enum class ChatReceiptDateOrder(val label: String) {
  DAY_FIRST("DD/MM/YYYY"),
  MONTH_FIRST("MM/DD/YYYY"),
  UNRESOLVED("Leave ambiguous dates unresolved"),
}

/** Visible, reversible user convention; image models always preserve the date as printed. */
object ChatReceiptDates {
  private const val PREFERENCES = "chat_receipt_dates"

  fun get(context: Context): ChatReceiptDateOrder =
      runCatching {
            ChatReceiptDateOrder.valueOf(
                context
                    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getString("numeric_order", ChatReceiptDateOrder.DAY_FIRST.name)!!)
          }
          .getOrDefault(ChatReceiptDateOrder.DAY_FIRST)

  fun set(context: Context, order: ChatReceiptDateOrder) {
    context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString("numeric_order", order.name)
        .apply()
  }
}
