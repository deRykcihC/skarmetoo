package com.deryk.skarmetoo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Keep each row's parent node stable as viewport loading and live result membership change. */
@Composable
internal fun <T> GalleryThumbnailRows(
    rows: List<List<T>>,
    renderedRows: Int,
    columns: Int,
    loadStart: Int,
    loadEnd: Int,
    virtualize: Boolean,
    pinching: Boolean,
    placeholderHeight: Dp,
    spacing: Dp,
    itemKey: (T) -> Any,
    cell: @Composable RowScope.(T, Boolean) -> Unit,
) {
  rows.take(renderedRows).forEachIndexed { rowIndex, row ->
    key(rowIndex) {
      Box(Modifier.fillMaxWidth()) {
        val inWindow = rowIndex >= loadStart && rowIndex < loadEnd
        if (virtualize && !inWindow) {
          Spacer(Modifier.fillMaxWidth().height(placeholderHeight))
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            row.forEach { image -> key(itemKey(image)) { cell(image, !pinching && inWindow) } }
            repeat((columns - row.size).coerceAtLeast(0)) {
              Spacer(Modifier.weight(1f).aspectRatio(1f))
            }
          }
        }
      }
    }
  }
}
