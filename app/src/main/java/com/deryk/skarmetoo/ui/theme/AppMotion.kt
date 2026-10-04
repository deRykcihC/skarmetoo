package com.deryk.skarmetoo.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

/** Shared Material 3 Expressive motion for custom UI, including coroutine-driven gestures. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object AppMotion {
  internal val scheme = MotionScheme.expressive()

  fun <T> spatial(): FiniteAnimationSpec<T> = scheme.defaultSpatialSpec()

  fun <T> fastSpatial(): FiniteAnimationSpec<T> = scheme.fastSpatialSpec()

  // Effects springs do not overshoot: opacity and bounded progress must stay in range.
  fun <T> effects(): FiniteAnimationSpec<T> = scheme.defaultEffectsSpec()

  fun <T> fastEffects(): FiniteAnimationSpec<T> = scheme.fastEffectsSpec()

  fun <T> slowEffects(): FiniteAnimationSpec<T> = scheme.slowEffectsSpec()

  private val timedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

  /** Fixed timing for scripted demonstrations and synchronized scroll/progress resets. */
  fun <T> timed(durationMillis: Int): TweenSpec<T> =
      tween(durationMillis = durationMillis, easing = timedEasing)

  /** Constant-speed closing motion for collapsible sections, without spring overshoot. */
  fun <T> linear(durationMillis: Int): TweenSpec<T> =
      tween(durationMillis = durationMillis, easing = LinearEasing)

  /** Repeating pulses need a duration-based spec, rather than a settling spring. */
  fun <T> pulse(durationMillis: Int): TweenSpec<T> =
      tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
}
