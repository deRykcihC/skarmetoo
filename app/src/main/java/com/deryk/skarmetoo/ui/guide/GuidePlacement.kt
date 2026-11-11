package com.deryk.skarmetoo.ui.guide

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/** Empty/clipped layout bounds are not destinations, even if padding would make them nonempty. */
internal fun guideFocusBounds(
    bounds: Rect?,
    origin: Offset,
    padding: Float,
    viewport: Rect
): Rect? {
  if (bounds == null || bounds.width <= 0f || bounds.height <= 0f) return null
  val visible = bounds.translate(-origin).intersect(viewport)
  if (visible.width <= 0f || visible.height <= 0f) return null
  return visible.inflate(padding).intersect(viewport)
}

/** Keep a genuine off-screen target's full shape at the nearest edge while its page scrolls. */
internal fun guideMovingFocusBounds(
    bounds: Rect?,
    origin: Offset,
    padding: Float,
    viewport: Rect
): Rect? {
  if (bounds == null ||
      bounds.width <= 0f ||
      bounds.height <= 0f ||
      viewport.width <= 0f ||
      viewport.height <= 0f)
      return null
  val local = bounds.translate(-origin)
  val width = minOf(local.width, viewport.width)
  val height = minOf(local.height, viewport.height)
  val left = local.left.coerceIn(viewport.left, viewport.right - width)
  val top = local.top.coerceIn(viewport.top, viewport.bottom - height)
  return Rect(left, top, left + width, top + height).inflate(padding).intersect(viewport)
}

data class GuideBox(val left: Float, val top: Float, val width: Float, val height: Float) {
  val right
    get() = left + width

  val bottom
    get() = top + height
}

/** Places measured content within the usable viewport, preferring an unobstructed spotlight. */
fun placeGuideCard(
    viewport: GuideBox,
    target: GuideBox?,
    width: Float,
    height: Float,
    gap: Float
): GuideBox {
  val w = width.coerceIn(0f, viewport.width)
  val h = height.coerceIn(0f, viewport.height)
  fun candidate(x: Float, y: Float) =
      GuideBox(
          x.coerceIn(viewport.left, viewport.right - w),
          y.coerceIn(viewport.top, viewport.bottom - h),
          w,
          h)
  if (target == null)
      return candidate(viewport.left + (viewport.width - w) / 2, viewport.bottom - h)
  val centerX = target.left + (target.width - w) / 2
  val centerY = target.top + (target.height - h) / 2
  val candidates =
      listOf(
          candidate(centerX, target.bottom + gap),
          candidate(centerX, target.top - h - gap),
          candidate(target.right + gap, centerY),
          candidate(target.left - w - gap, centerY))
  fun overlap(box: GuideBox): Float =
      (minOf(box.right, target.right) - maxOf(box.left, target.left)).coerceAtLeast(0f) *
          (minOf(box.bottom, target.bottom) - maxOf(box.top, target.top)).coerceAtLeast(0f)
  return candidates.minWith(
      compareBy<GuideBox> { overlap(it) }
          .thenBy {
            abs(it.left + w / 2 - target.left - target.width / 2) +
                abs(it.top + h / 2 - target.top - target.height / 2)
          })
}
