package com.deryk.skarmetoo.legacy

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deryk.skarmetoo.data.ScreenshotEntry
import com.deryk.skarmetoo.ui.theme.SkarmetooTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarouselThumbnailTest {
  @get:Rule val composeRule = createComposeRule()

  private lateinit var imageFile: File
  private lateinit var initialEntry: ScreenshotEntry

  @Before
  fun createThumbnail() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    imageFile = File.createTempFile("carousel-thumbnail-", ".png", context.cacheDir)
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.BLUE)
    imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    initialEntry =
        ScreenshotEntry(
            id = -1L,
            imageUri = Uri.fromFile(imageFile).toString(),
            imageHash = "",
            summary = THUMBNAIL_DESCRIPTION,
        )
  }

  @After
  fun removeThumbnail() {
    imageFile.delete()
  }

  @Test
  fun loadedThumbnailRemainsVisibleWhenMetadataArrives() {
    val entry = mutableStateOf(initialEntry)
    composeRule.setContent {
      SkarmetooTheme {
        Box(Modifier.width(160.dp)) { ScreenshotGridItem(entry = entry.value, onClick = {}) }
      }
    }
    composeRule.waitUntil(10_000) { thumbnailIsVisible() }

    composeRule.runOnIdle { entry.value = entry.value.copy(id = 21L, imageHash = "saved-hash") }
    composeRule.waitForIdle()

    assertTrue("Loading saved metadata hid an already loaded thumbnail", thumbnailIsVisible())
  }

  @Test
  fun cachedThumbnailRemainsVisibleAfterReturningToCarousel() {
    val entry = mutableStateOf(initialEntry)
    val showCarousel = mutableStateOf(true)
    composeRule.setContent {
      SkarmetooTheme {
        if (showCarousel.value) {
          Box(Modifier.width(160.dp)) { ScreenshotGridItem(entry = entry.value, onClick = {}) }
        }
      }
    }
    composeRule.waitUntil(10_000) { thumbnailIsVisible() }
    composeRule.runOnIdle { showCarousel.value = false }
    composeRule.waitForIdle()
    composeRule.runOnIdle { showCarousel.value = true }
    composeRule.waitUntil(10_000) { thumbnailIsVisible() }

    composeRule.runOnIdle { entry.value = entry.value.copy(id = 21L, imageHash = "saved-hash") }
    composeRule.waitForIdle()

    assertTrue("A cached carousel thumbnail disappeared after returning", thumbnailIsVisible())
  }

  private fun thumbnailIsVisible(): Boolean {
    val node = composeRule.onNodeWithContentDescription(THUMBNAIL_DESCRIPTION)
    val size = node.fetchSemanticsNode().size
    if (size.width == 0 || size.height == 0) return false
    val pixels = node.captureToImage().toPixelMap()
    val center = pixels[pixels.width / 2, pixels.height / 2]
    return center.blue > 0.95f && center.red < 0.05f && center.green < 0.05f
  }

  companion object {
    private const val THUMBNAIL_DESCRIPTION = "Carousel thumbnail test image"
  }
}
