package com.deryk.skarmetoo.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.theme.AppMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class GuideStep(
    val target: String,
    val route: String,
    val title: Int,
    val description: Int
)

private val steps =
    listOf(
        GuideStep("folders", "settings", R.string.guide_folders_title, R.string.guide_folders_body),
        GuideStep("model", "settings", R.string.guide_model_title, R.string.guide_model_body),
        GuideStep(
            "progress", "settings", R.string.guide_progress_title, R.string.guide_progress_body),
        GuideStep(
            "embedding", "settings", R.string.guide_embedding_title, R.string.guide_embedding_body),
        GuideStep(
            "efficientnet",
            "settings",
            R.string.guide_efficientnet_title,
            R.string.guide_efficientnet_body),
        GuideStep("search", "gallery", R.string.guide_search_title, R.string.guide_search_body),
        GuideStep("guidebook", "settings", R.string.guide_book_title, R.string.guide_book_body))

internal data class GuideTarget(
    val bounds: Rect,
    val unclippedBounds: Rect,
    val size: IntSize,
    val requester: BringIntoViewRequester
)

@Stable
class StartingGuideState internal constructor(private val index: MutableIntState) {
  internal val targets = mutableStateMapOf<String, GuideTarget>()
  var isTransitioning by mutableStateOf(false)
    internal set

  val active
    get() = index.intValue in steps.indices

  internal val stepIndex
    get() = index.intValue

  fun start() {
    isTransitioning = true
    index.intValue = 0
  }

  fun stop() {
    index.intValue = -1
    isTransitioning = false
  }

  fun next() {
    if (!active || isTransitioning) return
    isTransitioning = true
    if (index.intValue == steps.lastIndex) stop() else index.intValue++
  }

  fun back() {
    if (!active || isTransitioning) return
    isTransitioning = true
    index.intValue = (index.intValue - 1).coerceAtLeast(0)
  }
}

val LocalStartingGuide = staticCompositionLocalOf<StartingGuideState?> { null }

/** Keeps the overlay in the same coordinate root as the app, including its navigation controls. */
@Composable
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun StartingGuideHost(
    route: String?,
    onNavigate: (String) -> Unit,
    content: @Composable (StartingGuideState) -> Unit
) {
  val index = rememberSaveable { mutableIntStateOf(-1) }
  val state = remember { StartingGuideState(index) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var presentedIndex by remember { mutableIntStateOf(0) }
  var revealedIndex by remember { mutableIntStateOf(-1) }
  var revealedBounds by remember { mutableStateOf<Rect?>(null) }
  var trackingIndex by remember { mutableIntStateOf(-1) }
  val navigate by rememberUpdatedState(onNavigate)
  val currentRoute by rememberUpdatedState(route)
  val density = LocalDensity.current
  val navigationReserve = with(density) { 96.dp.toPx() }
  val step = steps.getOrNull(state.stepIndex)
  val target = step?.let { state.targets[it.target] }
  LaunchedEffect(step?.target, viewportSize, density.fontScale, density.density) {
    if (step == null) return@LaunchedEffect
    state.isTransitioning = true
    revealedIndex = -1
    trackingIndex = -1
    // Keep the old copy while it fades, so the new explanation never flashes in the old location.
    delay(160)
    presentedIndex = state.stepIndex
    if (currentRoute != step.route) {
      navigate(step.route)
      withTimeoutOrNull(1800) {
        snapshotFlow { currentRoute.takeIf { it == step.route } }.filterNotNull().first()
      }
      // Allow the existing navigation transition to attach the incoming screen's scroll containers.
      delay(260)
    }
    val destination =
        withTimeoutOrNull(1800) {
          snapshotFlow { state.targets[step.target].takeIf { currentRoute == step.route } }
              .filterNotNull()
              .first()
        }
    var settledBounds: Rect? = null
    if (destination != null) {
      // Release the light and the background scroll together, using genuine unclipped geometry.
      trackingIndex = state.stepIndex
      val reserve = navigationReserve.coerceAtMost(viewportSize.height * 0.25f)
      val endReserve = if (viewportSize.width > viewportSize.height) reserve else 0f
      val scrolled =
          try {
            withTimeoutOrNull(3500) {
              destination.requester.bringIntoView(
                  Rect(
                      0f,
                      -reserve / 2,
                      destination.size.width + endReserve,
                      destination.size.height + reserve))
              true
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: IllegalStateException) {
            // A destination can be detached during recreation; keep Back/Skip usable as a fallback.
            android.util.Log.w("StartingGuide", "Guide target was detached during scrolling", e)
            false
          }
      if (scrolled == true) {
        settledBounds =
            withTimeoutOrNull(1500) {
              snapshotFlow {
                    state.targets[step.target]?.bounds?.takeIf { it.width > 0 && it.height > 0 }
                  }
                  .debounce(80)
                  .filterNotNull()
                  .first()
            }
      }
    }
    // The overlay finishes its spotlight movement before revealing the new explanation.
    revealedBounds = settledBounds
    revealedIndex = state.stepIndex
  }
  BackHandler(state.active) { state.stop() }
  CompositionLocalProvider(LocalStartingGuide provides state) {
    Box(Modifier.fillMaxSize().onSizeChanged { viewportSize = it }) {
      content(state)
      if (step != null)
          StartingGuideOverlay(
              state,
              presentedIndex,
              revealedIndex,
              if (route == step.route &&
                  revealedIndex == state.stepIndex &&
                  revealedBounds != null) {
                if (state.isTransitioning) revealedBounds else target?.bounds
              } else null,
              if (route == step.route && trackingIndex == state.stepIndex) target?.unclippedBounds
              else null)
    }
  }
}

/**
 * Registration follows layout and scroll changes; no device dimensions or fixed screen positions.
 */
fun Modifier.startingGuideTarget(id: String): Modifier = composed {
  val guide = LocalStartingGuide.current
  if (guide == null) return@composed this
  val requester = remember { BringIntoViewRequester() }
  DisposableEffect(guide, id) {
    onDispose { if (guide.targets[id]?.requester === requester) guide.targets.remove(id) }
  }
  this.bringIntoViewRequester(requester).onGloballyPositioned { coordinates ->
    val target =
        GuideTarget(
            coordinates.boundsInRoot(),
            Rect(
                coordinates.localToRoot(Offset.Zero),
                coordinates.localToRoot(
                    Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))),
            coordinates.size,
            requester)
    if (guide.targets[id] != target) guide.targets[id] = target
  }
}

@Composable
private fun StartingGuideOverlay(
    state: StartingGuideState,
    presentedIndex: Int,
    revealedIndex: Int,
    targetBounds: Rect?,
    movingTargetBounds: Rect?,
) {
  val step = steps[presentedIndex]
  val density = LocalDensity.current
  var rootOrigin by remember { mutableStateOf(Offset.Zero) }
  var cardSize by remember { mutableStateOf(IntSize.Zero) }
  val spotlight = remember {
    Animatable(Rect.Zero, Rect.VectorConverter, Rect(0.5f, 0.5f, 0.5f, 0.5f))
  }
  val motionScope = rememberCoroutineScope()
  var hasSpotlight by remember { mutableStateOf(false) }
  val cardAlpha by
      animateFloatAsState(
          if (state.isTransitioning) 0f else 1f, AppMotion.timed(140), label = "guideCardFade")
  val title = stringResource(step.title)
  val padding = with(density) { 12.dp.toPx() }
  val focusPadding = with(density) { 6.dp.toPx() }
  val radius = with(density) { 16.dp.toPx() }
  BoxWithConstraints(
      Modifier.fillMaxSize()
          .onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
          .testTag("starting-guide-overlay")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
        val safe =
            with(density) {
              GuideBox(
                  padding + safeInsets.calculateLeftPadding(layoutDirection).toPx(),
                  padding + safeInsets.calculateTopPadding().toPx(),
                  (width -
                          2 * padding -
                          safeInsets.calculateLeftPadding(layoutDirection).toPx() -
                          safeInsets.calculateRightPadding(layoutDirection).toPx())
                      .coerceAtLeast(0f),
                  (height -
                          2 * padding -
                          safeInsets.calculateTopPadding().toPx() -
                          safeInsets.calculateBottomPadding().toPx())
                      .coerceAtLeast(0f))
            }
        val destinationFocus =
            guideFocusBounds(targetBounds, rootOrigin, focusPadding, Rect(0f, 0f, width, height))
        val reserve = with(density) { 96.dp.toPx() }.coerceAtMost(height * 0.25f)
        val motionViewport =
            Rect(
                safe.left,
                safe.top,
                minOf(safe.right, width - if (width > height) reserve else 0f)
                    .coerceAtLeast(safe.left),
                minOf(safe.bottom, height - if (width <= height) reserve else 0f)
                    .coerceAtLeast(safe.top))
        val movingFocus =
            guideMovingFocusBounds(movingTargetBounds, rootOrigin, focusPadding, motionViewport)
        val animationDestination =
            if (revealedIndex == state.stepIndex) destinationFocus else movingFocus
        val allowInitialFocus = hasSpotlight || revealedIndex == state.stepIndex
        LaunchedEffect(animationDestination, allowInitialFocus) {
          if (animationDestination != null && allowInitialFocus) {
            if (!hasSpotlight) {
              spotlight.snapTo(animationDestination)
              hasSpotlight = true
            } else if (spotlight.value != animationDestination) {
              // A new animateTo interrupts the previous one with its current velocity intact.
              // This follows scrolling targets smoothly instead of restarting a tween each frame.
              motionScope.launch {
                spotlight.animateTo(
                    animationDestination, spring(dampingRatio = 1f, stiffness = 500f))
              }
            }
          }
        }
        LaunchedEffect(revealedIndex, destinationFocus) {
          if (revealedIndex != state.stepIndex) return@LaunchedEffect
          if (destinationFocus != null) {
            snapshotFlow {
                  hasSpotlight && !spotlight.isRunning && spotlight.value == destinationFocus
                }
                .filter { it }
                .first()
          }
          state.isTransitioning = false
        }
        val focus = spotlight.value.takeIf { hasSpotlight }
        val holeAlpha by
            animateFloatAsState(
                if (hasSpotlight && (revealedIndex != state.stepIndex || destinationFocus != null))
                    1f
                else 0f,
                AppMotion.timed(180),
                label = "guideSpotlightFade")
        val placementFocus = if (revealedIndex == state.stepIndex) destinationFocus else focus
        val focusBox = placementFocus?.let { GuideBox(it.left, it.top, it.width, it.height) }
        val card =
            placeGuideCard(
                safe, focusBox, cardSize.width.toFloat(), cardSize.height.toFloat(), padding)
        val cardOffset by
            animateOffsetAsState(
                Offset(card.left, card.top), AppMotion.timed(360), label = "guideCardPosition")
        Canvas(Modifier.fillMaxSize()) {
          val path =
              Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                focus?.let { addRoundRect(RoundRect(it, CornerRadius(radius))) }
              }
          drawPath(path, Color.Black.copy(alpha = 0.68f))
          focus?.let {
            drawRoundRect(
                Color.Black.copy(alpha = 0.68f * (1f - holeAlpha)),
                it.topLeft,
                it.size,
                CornerRadius(radius))
            drawRoundRect(
                Color.White.copy(alpha = holeAlpha),
                it.topLeft,
                it.size,
                CornerRadius(radius),
                style = Stroke(2.dp.toPx()))
          }
        }
        focus?.let { bounds ->
          Box(
              Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                  .size(
                      with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                  .testTag("starting-guide-spotlight"))
        }
        // Only the dimmed regions intercept touches; the real highlighted control remains usable.
        // Moving light must not make unrelated controls briefly tappable along its path.
        val interactiveFocus =
            destinationFocus.takeUnless { state.isTransitioning || spotlight.isRunning }
        val blocked =
            if (interactiveFocus == null) listOf(Rect(0f, 0f, width, height))
            else
                listOf(
                    Rect(0f, 0f, width, interactiveFocus.top),
                    Rect(0f, interactiveFocus.bottom, width, height),
                    Rect(0f, interactiveFocus.top, interactiveFocus.left, interactiveFocus.bottom),
                    Rect(
                        interactiveFocus.right,
                        interactiveFocus.top,
                        width,
                        interactiveFocus.bottom))
        blocked
            .filter { it.width > 0 && it.height > 0 }
            .forEach { rect ->
              Box(
                  Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                      .size(
                          with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                      .clickable(
                          interactionSource =
                              remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                              },
                          indication = null) {})
            }
        Surface(
            modifier =
                Modifier.offset { IntOffset(cardOffset.x.roundToInt(), cardOffset.y.roundToInt()) }
                    .graphicsLayer {
                      alpha = cardAlpha
                      translationY = (1f - cardAlpha) * 8.dp.toPx()
                    }
                    .width(with(density) { minOf(safe.width, 360.dp.toPx()).toDp() })
                    .heightIn(max = with(density) { (safe.height * 0.65f).toDp() })
                    .onSizeChanged { cardSize = it }
                    .semantics {
                      paneTitle = title
                      liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("starting-guide-card"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp) {
              Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Box(Modifier.fillMaxWidth()) {
                  Row(
                      Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                      Row(
                          Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                          horizontalArrangement = Arrangement.spacedBy(6.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text(
                            stringResource(R.string.guide_step, presentedIndex + 1, steps.size),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = state::stop,
                        enabled = !state.isTransitioning,
                        modifier = Modifier.testTag("starting-guide-skip"),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                      Text(stringResource(R.string.guide_skip), maxLines = 1)
                    }
                  }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (presentedIndex + 1).toFloat() / steps.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(step.description), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                  if (presentedIndex > 0)
                      TextButton(onClick = state::back, enabled = !state.isTransitioning) {
                        Text(stringResource(R.string.guide_back))
                      }
                  Button(
                      onClick = state::next,
                      enabled = !state.isTransitioning,
                      modifier = Modifier.testTag("starting-guide-next")) {
                        Text(
                            stringResource(
                                if (presentedIndex == steps.lastIndex) R.string.guide_finish
                                else R.string.guide_next))
                      }
                }
              }
            }
      }
}
