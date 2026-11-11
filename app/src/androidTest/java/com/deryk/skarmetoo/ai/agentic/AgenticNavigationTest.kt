package com.deryk.skarmetoo.ai.agentic

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgenticNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun newTabOpensItsOwnPage() {
    val galleryLabel = compose.activity.getString(R.string.gallery)
    val settingsLabel = compose.activity.getString(R.string.settings)
    compose.waitUntil(timeoutMillis = 60_000) {
      runCatching { compose.onAllNodesWithText("Chat").fetchSemanticsNodes().isNotEmpty() }
          .getOrDefault(false)
    }
    compose.onNodeWithText("LFM").assertDoesNotExist()
    compose.onNodeWithText("Agentic").assertDoesNotExist()
    compose.onNodeWithText("Chat").assertExists().performClick()
    compose.onNodeWithText("Ask your image library").assertIsDisplayed()
    compose.onNodeWithText("Your question").assertExists()
    compose.onAllNodesWithText("Chat").assertCountEquals(2)
    compose.onNodeWithText("LFM").assertDoesNotExist()
    compose.onNodeWithText("Agentic").assertDoesNotExist()
    compose.onNodeWithText(galleryLabel).assertExists()
    compose.onNodeWithText(settingsLabel).assertExists()
    compose.onNodeWithText("Index library").assertDoesNotExist()
    compose.onNode(hasText("Backend:", substring = true)).assertDoesNotExist()
    val input = compose.onNodeWithText("Your question").fetchSemanticsNode().boundsInRoot
    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
    assertTrue("The question input stays near the bottom", input.bottom > root.bottom * 0.6f)

    compose.onNodeWithText(settingsLabel).performClick()
    val selector = compose.onNode(hasText("Backend:", substring = true))
    selector.performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Index library").assertExists()
    val originalBackend =
        selector
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .text
            .substringAfter(": ")
            .split("/")
    selector.performClick()
    compose.onNodeWithText("LLM CPU / Vision GPU").performClick()
    compose.onNodeWithText("Backend: CPU/GPU").assertExists()
    compose.onAllNodesWithText("Chat").filter(hasClickAction()).onFirst().performClick()
    compose.onNodeWithText("Your question").assertIsDisplayed()
    compose.onNodeWithText("Index library").assertDoesNotExist()
    compose.onNodeWithText(settingsLabel).performClick()
    compose.onNodeWithText("Backend: CPU/GPU").performScrollTo().performClick()
    compose
        .onNodeWithText("LLM ${originalBackend[0]} / Vision ${originalBackend[1]}")
        .performClick()
    compose.onAllNodesWithText("Chat").filter(hasClickAction()).onFirst().performClick()
  }
}
