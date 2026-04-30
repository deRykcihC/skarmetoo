package com.deryk.skarmetoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AnalysisService : Service() {

  private var isForegroundStarted = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    Log.d(TAG, "onStartCommand action=$action isForegroundStarted=$isForegroundStarted")

    when (action) {
      ACTION_STOP -> {
        isForegroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
      }
      else -> {
        // Both START and UPDATE: always ensure we are in foreground first
        val remaining = intent?.getIntExtra(EXTRA_REMAINING, 0) ?: 0
        val notification = createNotification(remaining)

        if (!isForegroundStarted) {
          isForegroundStarted = true
          try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              startForeground(
                  NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
              startForeground(NOTIFICATION_ID, notification)
            }
          } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            isForegroundStarted = false
            stopSelf()
          }
        } else {
          // Already foreground, just update the notification
          val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          nm.notify(NOTIFICATION_ID, notification)
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    isForegroundStarted = false
    super.onDestroy()
  }

  private fun createNotification(remaining: Int): Notification {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          action = "SHOW_GALLERY"
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_remaining, remaining))
        .setSmallIcon(R.drawable.app_logo)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(CHANNEL_ID, "Image Analysis", NotificationManager.IMPORTANCE_LOW)
              .apply {
                description = "Background image analysis progress"
                setSound(null, null)
                enableVibration(false)
              }
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(channel)
    }
  }

  companion object {
    private const val TAG = "AnalysisService"
    const val CHANNEL_ID = "analysis_channel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_START = "START"
    const val ACTION_UPDATE = "UPDATE"
    const val ACTION_STOP = "STOP"
    const val EXTRA_REMAINING = "REMAINING"
  }
}
