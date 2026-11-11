package com.deryk.skarmetoo.ui.guide

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePlacementTest {
  @Test
  fun movingSpotlightRejectsEmptyBoundsButAllowsARealCornerControl() {
    val viewport = Rect(0f, 0f, 400f, 800f)
    assertNull(guideMovingFocusBounds(Rect.Zero, Offset.Zero, 6f, viewport))
    assertEquals(
        Rect(0f, 0f, 36f, 36f),
        guideMovingFocusBounds(Rect(0f, 0f, 30f, 30f), Offset.Zero, 6f, viewport))
  }

  @Test
  fun genuineTargetBelowTheScreenMovesTheLightTowardTheBottomEdge() {
    assertEquals(
        Rect(94f, 694f, 306f, 800f),
        guideMovingFocusBounds(
            Rect(100f, 2000f, 300f, 2100f), Offset.Zero, 6f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun genuineTargetAboveTheScreenKeepsItsHorizontalPosition() {
    assertEquals(
        Rect(194f, 0f, 306f, 106f),
        guideMovingFocusBounds(
            Rect(200f, -400f, 300f, -300f), Offset.Zero, 6f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun emptyOffscreenBoundsDoNotBecomeATopLeftSpotlight() {
    assertNull(guideFocusBounds(Rect.Zero, Offset.Zero, 6f, Rect(0f, 0f, 400f, 800f)))
    assertNull(
        guideFocusBounds(Rect(100f, 100f, 100f, 100f), Offset.Zero, 6f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun fullyClippedControlIsRejectedBeforePadding() {
    assertNull(
        guideFocusBounds(Rect(-20f, 10f, -1f, 30f), Offset.Zero, 6f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun visibleControlGetsPaddingInTheOverlayCoordinateSpace() {
    assertEquals(
        Rect(14f, 24f, 66f, 76f),
        guideFocusBounds(
            Rect(100f, 200f, 140f, 240f), Offset(80f, 170f), 6f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun partiallyVisibleControlIsClippedAndPaddedWithinTheViewport() {
    assertEquals(
        Rect(0f, 92f, 38f, 148f),
        guideFocusBounds(Rect(-10f, 100f, 30f, 140f), Offset.Zero, 8f, Rect(0f, 0f, 400f, 800f)))
  }

  @Test
  fun cardStaysInsidePhoneTabletLandscapeAndSplitScreenViewports() {
    val sizes =
        listOf(
            320f to 480f,
            411f to 900f,
            900f to 411f,
            600f to 960f,
            1280f to 800f,
            320f to 180f,
            220f to 600f)
    for ((width, height) in sizes) {
      val safe = GuideBox(12f, 24f, width - 24f, height - 48f)
      for (target in
          listOf(
              GuideBox(0f, 0f, 40f, 40f),
              GuideBox(width - 48, height - 60, 48f, 60f),
              GuideBox(24f, height / 2, width - 48, 64f))) {
        val card = placeGuideCard(safe, target, 360f, 240f, 12f)
        assertTrue("$width x $height left", card.left >= safe.left)
        assertTrue("$width x $height top", card.top >= safe.top)
        assertTrue("$width x $height right", card.right <= safe.right + 0.01f)
        assertTrue("$width x $height bottom", card.bottom <= safe.bottom + 0.01f)
      }
    }
  }

  @Test
  fun usesFreeSideInLandscapeInsteadOfCoveringTarget() {
    val target = GuideBox(40f, 100f, 200f, 100f)
    val card = placeGuideCard(GuideBox(12f, 12f, 876f, 276f), target, 320f, 240f, 12f)
    assertTrue(card.left >= target.right)
  }

  @Test
  fun missingTargetStillLeavesTheGuideReachable() {
    val card = placeGuideCard(GuideBox(12f, 24f, 296f, 400f), null, 280f, 200f, 12f)
    assertEquals(224f, card.top)
    assertEquals(20f, card.left)
  }

  @Test
  fun largerTextCardIsClampedEvenInAnExtremelySmallViewport() {
    val safe = GuideBox(4f, 4f, 160f, 100f)
    val card = placeGuideCard(safe, GuideBox(4f, 4f, 160f, 100f), 400f, 600f, 12f)
    assertEquals(safe, card)
  }
}
