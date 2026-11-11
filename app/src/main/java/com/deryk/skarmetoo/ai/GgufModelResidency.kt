package com.deryk.skarmetoo.ai

enum class GgufBridge {
  TEXT,
  VISION
}

data class GgufResidentModel(val model: GgufModelInfo, val bridge: GgufBridge)

/** All calls are serialized by GgufLlmManager's inference mutex. */
internal class GgufModelResidency(
    private val loadText: (GgufModelInfo) -> Boolean,
    private val loadVision: (GgufModelInfo) -> Boolean,
    private val releaseText: () -> Unit,
    private val releaseVision: () -> Unit,
    private val onChanged: (GgufResidentModel?) -> Unit = {},
) {
  private var allocation: GgufResidentModel? = null
  private var ready = false

  fun ensure(model: GgufModelInfo, bridge: GgufBridge) {
    val requested = GgufResidentModel(model, bridge)
    if (allocation == requested && ready) return
    release()
    // Track partial initialization too, so a failed loader is released before another load.
    allocation = requested
    try {
      check(if (bridge == GgufBridge.TEXT) loadText(model) else loadVision(model)) {
        "Could not load ${model.displayName}. Free memory and retry."
      }
      ready = true
      onChanged(requested)
    } catch (failure: Throwable) {
      try {
        release()
      } catch (cleanup: Throwable) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
  }

  fun release() {
    val current = allocation ?: return
    // A failed release must prevent a new allocation from overlapping this one.
    ready = false
    if (current.bridge == GgufBridge.TEXT) releaseText() else releaseVision()
    allocation = null
    onChanged(null)
  }
}
