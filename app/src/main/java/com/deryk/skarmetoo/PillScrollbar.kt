package com.deryk.skarmetoo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A custom pill-shaped scrollbar for Column with verticalScroll.
 *
 * Uses ScrollState for pixel-perfect position tracking — no row-based jitter.
 * The scrollbar thumb position maps directly to scrollState.value / scrollState.maxValue,
 * giving smooth, continuous movement like a ScrollView scrollbar.
 *
 * Features:
 * - Fades in when scrolling, fades out after ~1.5s of inactivity
 * - Expands horizontally when touched for easier dragging
 * - Touch the track → thumb expands → drag to scroll (single gesture)
 * - Touch target (20dp) so thin scrollbar is easy to grab without blocking content
 * - Light grey border on thumb for visibility against images
 */
@Composable
fun PillScrollbar(
    scrollState: ScrollState,
    bottomPadding: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var hasInteracted by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var trackHeightPx by remember { mutableIntStateOf(0) }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible || isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbarAlpha",
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 14.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "scrollbarWidth",
    )

    // Show on scroll, hide after delay — skip the initial composition
    LaunchedEffect(scrollState.value) {
        if (!isDragging) {
            if (hasInteracted) {
                isVisible = true
                hideJob?.cancel()
                hideJob = coroutineScope.launch {
                    delay(1500)
                    if (!isDragging) isVisible = false
                }
            } else {
                hasInteracted = true
            }
        }
    }

    val maxValue = scrollState.maxValue

    // Don't render if content fits in the viewport or scrollbar is hidden
    // Hidden scrollbar should not be draggable — only interactive after user scrolls
    if ((maxValue <= 0 && !isDragging) || (!isVisible && !isDragging)) return

    // === Pixel-perfect position from ScrollState ===
    val rawNormalizedPosition = if (maxValue > 0) {
        scrollState.value.toFloat() / maxValue.toFloat()
    } else 0f

    // Use raw position directly — no animation delay for snappy scrolling
    val normalizedPosition = rawNormalizedPosition

    // Thumb height: viewport / contentHeight where contentHeight = viewport + maxValue
    val thumbHeightFraction = if (trackHeightPx > 0 && maxValue > 0) {
        (trackHeightPx.toFloat() / (trackHeightPx + maxValue)).coerceIn(0.05f, 0.4f)
    } else 0.15f

    // Only enable touch handling when the scrollbar is visible or being dragged
    val touchModifier = if (isVisible || isDragging) {
        Modifier.pointerInput(scrollState, maxValue, trackHeightPx) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (trackHeightPx <= 0 || maxValue <= 0) return@awaitEachGesture

                down.consume()
                isDragging = true
                isVisible = true
                hideJob?.cancel()

                // Calculate where the thumb center is vs where the finger landed
                val thumbCenterPx = normalizedPosition * trackHeightPx
                val offsetFromCenter = down.position.y - thumbCenterPx

                var totalDragPx = 0f

                // Handle drag — scroll follows finger movement
                val dragResult = drag(down.id) { change ->
                    change.consume()
                    totalDragPx += (change.position.y - change.previousPosition.y)

                    // Compute target position from cumulative drag + initial offset
                    val effectiveY = totalDragPx + offsetFromCenter + thumbCenterPx
                    val scrollableTrack = trackHeightPx - trackHeightPx * thumbHeightFraction
                    val targetPosition = if (scrollableTrack > 0) {
                        (effectiveY / scrollableTrack).coerceIn(0f, 1f)
                    } else 0f

                    // Apply scroll immediately using pixel position
                    val targetScrollPx = (targetPosition * maxValue).toInt()
                    val delta = (targetScrollPx - scrollState.value).toFloat()
                    scrollState.dispatchRawDelta(delta)
                }

                // Drag ended
                isDragging = false
                hideJob?.cancel()
                hideJob = coroutineScope.launch {
                    delay(1500)
                    if (!isDragging) isVisible = false
                }
            }
        }
    } else Modifier

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(bottom = bottomPadding)
            .width(20.dp) // Reduced touch target width to avoid blocking taps on nearby content
            .onSizeChanged { trackHeightPx = it.height }
            .then(touchModifier),
        contentAlignment = Alignment.TopEnd,
    ) {
        // Render the thumb only when we have a valid track size and some visibility
        if (trackHeightPx > 0 && alpha > 0.01f) {
            val density = LocalDensity.current
            val thumbHeightPx = trackHeightPx * thumbHeightFraction
            val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * normalizedPosition

            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }
            val thumbOffsetDp = with(density) { thumbOffsetPx.toDp() }
            val cornerRadius = (thumbWidth.coerceAtMost(thumbHeightDp)) / 2f

            Box(
                modifier = Modifier
                    .offset(y = thumbOffsetDp)
                    .alpha(alpha)
                    .width(thumbWidth)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(cornerRadius),
                    )
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isDragging) 0.7f else 0.4f,
                        ),
                    ),
            )
        }
    }
}