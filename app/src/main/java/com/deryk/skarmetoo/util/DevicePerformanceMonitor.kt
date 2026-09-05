package com.deryk.skarmetoo.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File

data class DeviceResourceUsage(
    val cpuPercent: Float?,
    val ramPercent: Float?,
)

/** Lightweight device CPU and RAM sampler used while image analysis is active. */
class DevicePerformanceMonitor(context: Context) {
  private val activityManager =
      context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  private var previousCpuTimes: CpuTimes? = null
  private var previousProcessCpuMillis = Process.getElapsedCpuTime()
  private var previousWallTimeMillis = SystemClock.elapsedRealtime()

  @Synchronized
  fun reset() {
    previousCpuTimes = readCpuTimes()
    previousProcessCpuMillis = Process.getElapsedCpuTime()
    previousWallTimeMillis = SystemClock.elapsedRealtime()
  }

  @Synchronized
  fun sample(): DeviceResourceUsage {
    val cpuPercent =
        runCatching { readDeviceCpuPercent() ?: readProcessCpuPercent() }
            .getOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 100f)
    val ramPercent =
        runCatching {
              val manager = activityManager ?: return@runCatching null
              val memoryInfo = ActivityManager.MemoryInfo()
              manager.getMemoryInfo(memoryInfo)
              if (memoryInfo.totalMem <= 0L) return@runCatching null
              ((memoryInfo.totalMem - memoryInfo.availMem).toDouble() / memoryInfo.totalMem * 100.0)
                  .toFloat()
            }
            .getOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0f, 100f)
    return DeviceResourceUsage(
        cpuPercent = cpuPercent,
        ramPercent = ramPercent,
    )
  }

  private fun readDeviceCpuPercent(): Float? {
    val current = readCpuTimes() ?: return null
    val previous = previousCpuTimes
    previousCpuTimes = current
    if (previous == null) return null

    val totalDelta = current.total - previous.total
    val idleDelta = current.idle - previous.idle
    if (totalDelta <= 0L) return null
    return ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0).toFloat()
  }

  private fun readProcessCpuPercent(): Float? {
    val currentCpuMillis = Process.getElapsedCpuTime()
    val currentWallMillis = SystemClock.elapsedRealtime()
    val cpuDelta = currentCpuMillis - previousProcessCpuMillis
    val wallDelta = currentWallMillis - previousWallTimeMillis
    previousProcessCpuMillis = currentCpuMillis
    previousWallTimeMillis = currentWallMillis
    if (wallDelta <= 0L || cpuDelta < 0L) return null

    val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return (cpuDelta.toDouble() / wallDelta / processorCount * 100.0).toFloat()
  }

  private fun readCpuTimes(): CpuTimes? =
      runCatching {
            val values =
                File("/proc/stat")
                    .useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.drop(1)
                    ?.map { it.toLong() } ?: return null
            if (values.size < 4) return null
            CpuTimes(
                total = values.sum(),
                idle = values[3] + values.getOrElse(4) { 0L },
            )
          }
          .getOrNull()

  private data class CpuTimes(val total: Long, val idle: Long)
}
