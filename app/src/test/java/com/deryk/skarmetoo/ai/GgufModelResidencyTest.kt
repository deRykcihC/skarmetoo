package com.deryk.skarmetoo.ai

import org.junit.Assert.*
import org.junit.Test

class GgufModelResidencyTest {
  @Test
  fun switchingBridgesReleasesPreviousAllocationBeforeLoadingNext() {
    val live = mutableSetOf<String>()
    val events = mutableListOf<String>()
    fun load(name: String): Boolean {
      assertTrue("No previous allocation may overlap initialization", live.isEmpty())
      live += name
      events += "load $name"
      return true
    }
    fun release(name: String) {
      assertTrue(live.remove(name))
      events += "release $name"
    }
    val residency =
        GgufModelResidency(
            { load("text") }, { load("vision") }, { release("text") }, { release("vision") })
    residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
    residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
    residency.ensure(LFM2_5_MODEL, GgufBridge.VISION)
    residency.ensure(LFM2_5_3B_MODEL, GgufBridge.VISION)
    residency.release()
    assertTrue(live.isEmpty())
    assertEquals(
        listOf(
            "load text",
            "release text",
            "load vision",
            "release vision",
            "load vision",
            "release vision"),
        events)
  }

  @Test
  fun unloadedModelsReloadOnTheNextRequest() {
    var loads = 0
    var releases = 0
    val residency =
        GgufModelResidency(
            {
              loads++
              true
            },
            { error("Unexpected vision load") },
            { releases++ },
            {})
    repeat(2) {
      residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
      residency.release()
    }
    residency.release()
    assertEquals(2, loads)
    assertEquals(2, releases)
  }

  @Test
  fun partialInitializationIsFreedBeforeRetry() {
    var allocation = false
    var loads = 0
    val residency =
        GgufModelResidency(
            {
              assertFalse(allocation)
              allocation = true
              ++loads > 1
            },
            { error("Unexpected vision load") },
            { allocation = false },
            {})
    assertThrows(IllegalStateException::class.java) {
      residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
    }
    assertFalse(allocation)
    residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
    assertTrue(allocation)
    residency.release()
    assertFalse(allocation)
  }

  @Test
  fun failedReleaseDoesNotAllowAnotherModelToLoad() {
    var refuseRelease = true
    var visionLoads = 0
    val residency =
        GgufModelResidency(
            { true },
            {
              visionLoads++
              true
            },
            { check(!refuseRelease) { "Release failed" } },
            {})
    residency.ensure(LFM2_5_3B_MODEL, GgufBridge.TEXT)
    assertThrows(IllegalStateException::class.java) {
      residency.ensure(LFM2_5_MODEL, GgufBridge.VISION)
    }
    assertEquals(0, visionLoads)
    refuseRelease = false
    residency.ensure(LFM2_5_MODEL, GgufBridge.VISION)
    assertEquals(1, visionLoads)
  }
}
