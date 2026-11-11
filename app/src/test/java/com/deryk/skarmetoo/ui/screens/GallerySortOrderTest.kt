package com.deryk.skarmetoo.ui.screens

import org.junit.Assert.*
import org.junit.Test

class GallerySortOrderTest {
  @Test
  fun searchCyclesAllFourOptionsInOrder() {
    val orders = GallerySortOrder.entries
    orders.forEachIndexed { index, order ->
      assertEquals(orders[(index + 1) % 4], order.next(true))
      assertEquals(index % 2 == 0, order.descending)
    }
  }

  @Test
  fun browsingExcludesRelevanceOptions() {
    assertEquals(GallerySortOrder.OLDEST, GallerySortOrder.NEWEST.next(false))
    assertEquals(GallerySortOrder.NEWEST, GallerySortOrder.OLDEST.next(false))
    assertFalse(GallerySortOrder.MOST_RELEVANCE.next(false).relevance)
    assertFalse(GallerySortOrder.LEAST_RELEVANCE.next(false).relevance)
  }
}
