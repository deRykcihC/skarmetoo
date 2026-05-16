package com.deryk.skarmetoo.ui.theme

/**
 * Returns a gentle UI scale multiplier based on the device's density bucket.
 *
 * Compose already makes `dp` and `sp` density-aware, so this is intentionally subtle:
 * it only nudges the whole app a bit on very low/high density devices.
 */
fun uiScaleForDensityDpi(densityDpi: Int): Float {
  return when {
    densityDpi <= 160 -> 0.92f
    densityDpi <= 240 -> 0.96f
    densityDpi <= 320 -> 1.0f
    densityDpi <= 480 -> 1.06f
    densityDpi <= 640 -> 1.12f
    else -> 1.16f
  }
}
