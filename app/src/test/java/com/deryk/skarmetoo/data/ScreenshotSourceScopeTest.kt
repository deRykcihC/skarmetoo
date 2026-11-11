package com.deryk.skarmetoo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotSourceScopeTest {
  private val root = "content://com.android.externalstorage.documents/tree/primary%3APictures"

  @Test
  fun revokedFolderIsDetectedWhileAnotherReadableFolderIsKept() {
    val other = "content://com.android.externalstorage.documents/tree/primary%3AReceipts"
    assertEquals(setOf(root), ScreenshotSourceScope.revokedTrees(setOf(root, other), setOf(other)))
    assertEquals(
        setOf(root, other), ScreenshotSourceScope.revokedTrees(setOf(root, other), emptySet()))
    assertTrue(ScreenshotSourceScope.revokedTrees(setOf("$root%2FReceipts"), setOf(root)).isEmpty())
  }

  @Test
  fun opaqueProviderDocumentsBelongToTheirOriginalTreeGrant() {
    val tree = "content://cloud.documents/tree/folder-123"
    assertTrue(ScreenshotSourceScope.isWithinTree("$tree/document/image-789", tree))
    assertFalse(
        ScreenshotSourceScope.isWithinTree(
            "$tree/document/image-789", "content://cloud.documents/tree/folder-456"))
  }

  @Test
  fun includesNestedImagesAndOtherGrantsToTheSameDocument() {
    assertTrue(
        ScreenshotSourceScope.isWithinTree(
            "$root/document/primary%3APictures%2FReceipts%2Fone.jpg", root))
    assertTrue(
        ScreenshotSourceScope.isWithinTree(
            "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3APictures%2Fone.jpg",
            root))
  }

  @Test
  fun excludesSimilarFolderNamesAndOtherProviders() {
    assertFalse(
        ScreenshotSourceScope.isWithinTree("$root/document/primary%3APicturesOld%2Fone.jpg", root))
    assertFalse(
        ScreenshotSourceScope.isWithinTree(
            "content://another.provider/document/primary%3APictures%2Fone.jpg", root))
    assertFalse(ScreenshotSourceScope.isWithinTree("content://media/external/images/media/1", root))
  }

  @Test
  fun volumeRootIncludesChildrenButNotAnotherVolume() {
    val volume = "content://com.android.externalstorage.documents/tree/primary%3A"
    assertTrue(
        ScreenshotSourceScope.isWithinTree("$root/document/primary%3APictures%2Fone.jpg", volume))
    assertFalse(
        ScreenshotSourceScope.isWithinTree("$root/document/1234%3APictures%2Fone.jpg", volume))
  }

  @Test
  fun folderGrantCanBeCoveredByAParentGrant() {
    assertTrue(ScreenshotSourceScope.isWithinTree("$root%2FReceipts", root))
    assertFalse(ScreenshotSourceScope.isWithinTree(root, "$root%2FReceipts"))
  }

  @Test
  fun plusSignsRemainLiteralAndMalformedUrisDoNotMatch() {
    val plusRoot = "content://com.android.externalstorage.documents/tree/primary%3AA+B"
    assertTrue(
        ScreenshotSourceScope.isWithinTree(
            "$plusRoot/document/primary%3AA%2BB%2Fone.jpg", plusRoot))
    assertFalse(
        ScreenshotSourceScope.isWithinTree(
            "$plusRoot/document/primary%3AA%20B%2Fone.jpg", plusRoot))
    assertFalse(ScreenshotSourceScope.isWithinTree("invalid uri", root))
  }
}
