package com.deryk.skarmetoo.util

import java.io.File
import kotlin.math.abs

object CpuTemperature {
  private const val THERMAL_ZONE_PATH = "/sys/class/thermal"

  fun readAverageCelsius(): Float? {
    val values =
        File(THERMAL_ZONE_PATH)
            .listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone -> readCpuTemperatureCelsius(zone) }
            .orEmpty()

    return values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
  }

  private fun readCpuTemperatureCelsius(zone: File): Float? {
    return try {
      val type = File(zone, "type").readText().trim().lowercase()
      if (!isCpuZone(type)) return null

      val rawTemperature = File(zone, "temp").readText().trim().toFloatOrNull() ?: return null
      val celsius = if (abs(rawTemperature) > 200f) rawTemperature / 1000f else rawTemperature
      if (celsius in -40f..125f) celsius else null
    } catch (_: Exception) {
      null
    }
  }

  private fun isCpuZone(type: String): Boolean =
      type.contains("cpu") || type.contains("package") || type.contains("soc")
}
