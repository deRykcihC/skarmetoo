package com.deryk.skarmetoo.chat

data class ChatTextLine(val text: String, val left: Int, val top: Int, val bottom: Int)

/**
 * Join aligned labels and values before moving down the image, instead of reading whole columns.
 */
object ChatTextReadingOrder {
  fun text(lines: List<ChatTextLine>): String {
    val rows = mutableListOf<MutableList<ChatTextLine>>()
    for (line in lines.sortedWith(compareBy<ChatTextLine> { it.top }.thenBy { it.left })) {
      val row = rows.lastOrNull()
      val sameRow =
          row?.any {
            val overlap = minOf(it.bottom, line.bottom) - maxOf(it.top, line.top)
            val height = minOf(it.bottom - it.top, line.bottom - line.top)
            height > 0 && overlap * 2 >= height
          } == true
      if (sameRow) requireNotNull(row).add(line) else rows += mutableListOf(line)
    }
    return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
  }
}
