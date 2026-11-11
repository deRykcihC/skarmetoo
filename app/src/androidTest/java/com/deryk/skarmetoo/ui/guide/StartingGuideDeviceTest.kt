package com.deryk.skarmetoo.ui.guide

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StartingGuideDeviceTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun spotlightAllowsOnlyItsTargetAndCanBeSkipped() {
    var highlightedClicks = 0
    var outsideClicks = 0
    lateinit var guide: StartingGuideState
    rule.setContent {
      var route by remember { mutableStateOf("settings") }
      MaterialTheme {
        StartingGuideHost(route, { route = it }) { state ->
          guide = state
          Box(Modifier.fillMaxSize()) {
            Button(
                onClick = { highlightedClicks++ },
                modifier =
                    Modifier.padding(start = 24.dp, top = 64.dp)
                        .startingGuideTarget("folders")
                        .testTag("highlighted-control")) {
                  Text("Choose folders")
                }
            Button(
                onClick = { outsideClicks++ },
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(24.dp)
                        .testTag("outside-control")) {
                  Text("Other control")
                }
          }
          LaunchedEffect(Unit) { state.start() }
        }
      }
    }
    waitForGuide { guide }
    val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val card = rule.onNodeWithTag("starting-guide-card").fetchSemanticsNode().boundsInRoot
    assertTrue(card.left >= root.left && card.right <= root.right + 1)
    assertTrue(card.top >= root.top && card.bottom <= root.bottom + 1)
    rule.onNodeWithTag("highlighted-control").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(1, highlightedClicks) }
    rule.onNodeWithTag("outside-control").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(0, outsideClicks) }
    val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
    File(rule.activity.cacheDir, "starting-guide-trial.png").outputStream().use {
      screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    rule.onNodeWithTag("starting-guide-skip").performClick()
    rule.runOnIdle { assertFalse(guide.active) }
    rule.onNodeWithTag("outside-control").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(1, outsideClicks) }
  }

  @Test
  fun largeTextKeepsCardInsideTheScreenAndButtonsReachable() {
    lateinit var guide: StartingGuideState
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        MaterialTheme {
          StartingGuideHost("settings", {}) { state ->
            guide = state
            Box(Modifier.fillMaxSize()) {
              Text("Folder control", Modifier.padding(32.dp).startingGuideTarget("folders"))
            }
            LaunchedEffect(Unit) { state.start() }
          }
        }
      }
    }
    waitForGuide { guide }
    val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val card = rule.onNodeWithTag("starting-guide-card").fetchSemanticsNode().boundsInRoot
    assertTrue(card.left >= root.left && card.right <= root.right + 1)
    assertTrue(card.top >= root.top && card.bottom <= root.bottom + 1)
    rule.onNodeWithTag("starting-guide-next").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun missingTargetCanStillAdvanceAndFinish() {
    lateinit var guide: StartingGuideState
    rule.setContent {
      var route by remember { mutableStateOf("settings") }
      MaterialTheme {
        StartingGuideHost(route, { route = it }) { state ->
          guide = state
          LaunchedEffect(Unit) { state.start() }
        }
      }
    }
    repeat(6) {
      waitForGuide { guide }
      rule.onNodeWithTag("starting-guide-next").performScrollTo().performClick()
    }
    waitForGuide { guide }
    rule.runOnIdle { assertTrue(guide.active) }
    rule
        .onNodeWithText(rule.activity.getString(com.deryk.skarmetoo.R.string.guide_book_title))
        .assertIsDisplayed()
    rule.onNodeWithTag("starting-guide-next").performScrollTo().performClick()
    rule.runOnIdle { assertFalse(guide.active) }
  }

  @Test
  fun spotlightTravelsBetweenTargetsAndBlocksTapsUntilItArrives() {
    lateinit var guide: StartingGuideState
    var modelClicks = 0
    rule.setContent {
      MaterialTheme {
        StartingGuideHost("settings", {}) { state ->
          guide = state
          Box(Modifier.fillMaxSize()) {
            Button(
                onClick = {},
                modifier =
                    Modifier.padding(start = 24.dp, top = 64.dp).startingGuideTarget("folders")) {
                  Text("Folders")
                }
            Button(
                onClick = { modelClicks++ },
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 120.dp)
                        .startingGuideTarget("model")
                        .testTag("moving-model-control")) {
                  Text("Model")
                }
          }
          LaunchedEffect(Unit) { state.start() }
        }
      }
    }
    waitForGuide { guide }
    val from = rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
    val destination = rule.onNodeWithTag("moving-model-control").fetchSemanticsNode().boundsInRoot
    rule.mainClock.autoAdvance = false
    try {
      rule.runOnUiThread { guide.next() }
      rule.waitUntil(5_000) {
        rule.mainClock.advanceTimeBy(16)
        rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot.center.y >
            from.center.y + 1
      }
      val during = rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
      assertTrue(
          "Spotlight must move away from the previous control", during.center.y > from.center.y + 1)
      assertTrue(
          "Spotlight must glide instead of teleporting", during.center.y < destination.center.y - 1)
      rule.onNodeWithTag("moving-model-control").performTouchInput { click(center) }
      rule.runOnUiThread { assertEquals(0, modelClicks) }
    } finally {
      rule.mainClock.autoAdvance = true
    }
    waitForGuide { guide }
    rule.onNodeWithTag("moving-model-control").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(1, modelClicks) }
  }

  private fun waitForGuide(state: () -> StartingGuideState) {
    rule.waitUntil(10_000) { runCatching { !state().isTransitioning }.getOrDefault(false) }
    rule.waitForIdle()
  }

  @Test
  fun pageAndSpotlightMoveTogetherWithoutATopLeftDetour() {
    lateinit var guide: StartingGuideState
    lateinit var scroll: ScrollState
    rule.setContent {
      MaterialTheme {
        StartingGuideHost("settings", {}) { state ->
          guide = state
          val scrollState = rememberScrollState()
          scroll = scrollState
          Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Button(
                onClick = {},
                modifier =
                    Modifier.padding(start = 24.dp, top = 64.dp).startingGuideTarget("folders")) {
                  Text("Folders")
                }
            Spacer(Modifier.height(1600.dp))
            Button(
                onClick = {},
                modifier =
                    Modifier.padding(24.dp)
                        .startingGuideTarget("model")
                        .testTag("offscreen-model-control")) {
                  Text("Model")
                }
          }
          LaunchedEffect(Unit) { state.start() }
        }
      }
    }
    waitForGuide { guide }
    val previous = rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
    rule.mainClock.autoAdvance = false
    try {
      rule.runOnUiThread { guide.next() }
      rule.waitUntil(5_000) {
        rule.mainClock.advanceTimeByFrame()
        val light = rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
        scroll.isScrollInProgress && scroll.value > 0 && light.center.y > previous.center.y + 1
      }
      val duringScroll =
          rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
      assertTrue("The page must still be scrolling when the light moves", scroll.isScrollInProgress)
      assertTrue(
          "The spotlight must move alongside the scrolling page",
          duringScroll.center.y > previous.center.y + 1)
      assertTrue(
          "A real offscreen destination must not become a tiny origin highlight",
          duringScroll.width > 16f && duringScroll.height > 16f)
    } finally {
      rule.mainClock.autoAdvance = true
    }
    waitForGuide { guide }
    rule.onNodeWithTag("offscreen-model-control").assertIsDisplayed()
    val destination =
        rule.onNodeWithTag("offscreen-model-control").fetchSemanticsNode().boundsInRoot
    val arrived = rule.onNodeWithTag("starting-guide-spotlight").fetchSemanticsNode().boundsInRoot
    assertTrue(kotlin.math.abs(arrived.center.y - destination.center.y) < 2f)
    assertTrue(kotlin.math.abs(arrived.center.x - destination.center.x) < 2f)
  }
}
