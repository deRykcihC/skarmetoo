package com.deryk.skarmetoo.ui.guide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.deryk.skarmetoo.ui.screens.GalleryThumbnailRows
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveSearchGridDeviceTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun scrollingWhileMatchesAreInsertedAndReordered() {
    var images by mutableStateOf((1..160).toList())
    var progress by mutableIntStateOf(0)
    lateinit var scrollTo: (Int) -> Unit
    rule.setContent {
      val scroll = rememberScrollState()
      val scope = rememberCoroutineScope()
      val rowPx = with(LocalDensity.current) { 76.dp.toPx() }
      scrollTo = { offset ->
        scope.launch { scroll.scrollTo(offset.coerceAtMost(scroll.maxValue)) }
      }
      val first = (scroll.value / rowPx).toInt()
      MaterialTheme {
        Column(
            Modifier.fillMaxWidth().height(360.dp).verticalScroll(scroll).testTag("live-grid"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
              GalleryThumbnailRows(
                  rows = images.chunked(4),
                  renderedRows = (images.size + 3) / 4,
                  columns = 4,
                  loadStart = (first - 2).coerceAtLeast(0),
                  loadEnd = first + 9,
                  virtualize = true,
                  pinching = false,
                  placeholderHeight = 72.dp,
                  spacing = 4.dp,
                  itemKey = { it },
              ) { image, loaded ->
                Column(Modifier.weight(1f).height(72.dp)) {
                  Text("Image $image")
                  if (loaded) Text("Match ${image % 100}")
                  if ((image + progress) % 3 == 0) Text("Analyzing $progress")
                }
              }
            }
      }
    }
    repeat(160) { iteration ->
      rule.runOnIdle {
        val updated = images.toMutableList()
        updated.add((iteration * 17) % (updated.size + 1), 1000 + iteration)
        if (iteration % 3 == 0) updated.add(0, updated.removeAt(updated.lastIndex))
        images = updated
        progress = iteration
        scrollTo((iteration * 317) % 16000)
      }
      rule.waitForIdle()
    }
    rule.onNodeWithTag("live-grid").assertExists()
    rule.runOnIdle { assertEquals(320, images.size) }
  }
}
