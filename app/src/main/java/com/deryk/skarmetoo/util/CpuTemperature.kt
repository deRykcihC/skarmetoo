package com.deryk.skarmetoo.util

import java.io.File
import kotlin.math.abs

object CpuTemperature {
  private const val THERMAL_ZONE_PATH = "/sys/class/thermal"
  private const val HWMON_PATH = "/sys/class/hwmon"

  fun readAverageCelsius(): Float? {
    val values = readThermalZoneTemperatures() + readHwmonTemperatures()

    return values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
  }

  private fun readThermalZoneTemperatures(): List<Float> =
      File(THERMAL_ZONE_PATH)
          .listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") }
          ?.mapNotNull(::readCpuTemperatureCelsius)
          .orEmpty()

  private fun readHwmonTemperatures(): List<Float> =
      File(HWMON_PATH)
          .listFiles { file -> file.isDirectory && file.name.startsWith("hwmon") }
          ?.flatMap { device ->
            val deviceType = runCatching { File(device, "name").readText().trim() }.getOrDefault("")
            device
                .listFiles { file -> file.isFile && file.name.matches(Regex("temp\\d+_input")) }
                ?.mapNotNull { input ->
                  val label =
                      runCatching {
                            File(input.parentFile, input.name.replace("_input", "_label"))
                                .readText()
                                .trim()
                          }
                          .getOrDefault("")
                  if (isCpuZone(deviceType) || isCpuZone(label)) readTemperature(input) else null
                }
                .orEmpty()
          }
          .orEmpty()

  private fun readCpuTemperatureCelsius(zone: File): Float? {
    return try {
      val type = File(zone, "type").readText().trim().lowercase()
      if (!isCpuZone(type)) return null

      readTemperature(File(zone, "temp"))
    } catch (_: Exception) {
      null
    }
  }

  private fun readTemperature(file: File): Float? {
    val rawTemperature =
        runCatching { file.readText().trim().toFloatOrNull() }.getOrNull() ?: return null
    val celsius =
        when {
          abs(rawTemperature) > 200_000f -> rawTemperature / 1_000_000f
          abs(rawTemperature) > 200f -> rawTemperature / 1_000f
          else -> rawTemperature
        }
    return celsius.takeIf { it in -40f..125f }
  }

  private fun isCpuZone(type: String): Boolean =
      CPU_ZONE_HINTS.any { hint -> type.contains(hint, ignoreCase = true) }

  private val CPU_ZONE_HINTS =
      listOf(
          "cpu",
          "cpuss",
          "package",
          "soc",
          "cluster",
          "little",
          "big",
          "prime",
          "silver",
          "gold",
          "ap_ntc")
}
