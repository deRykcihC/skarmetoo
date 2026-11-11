package com.deryk.skarmetoo.ui.screens

enum class GallerySortOrder(val descending: Boolean, val relevance: Boolean) {
  MOST_RELEVANCE(true, true),
  LEAST_RELEVANCE(false, true),
  NEWEST(true, false),
  OLDEST(false, false);

  fun next(searching: Boolean): GallerySortOrder =
      if (searching) entries[(ordinal + 1) % entries.size]
      else if (this == NEWEST) OLDEST else NEWEST
}
