package com.deryk.skarmetoo.service

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
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.MainActivity

class DesktopTransferService : Service() {

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
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: ""
        val notification = createNotification(detail)
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

  private fun createNotification(detail: String): Notification {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          action = "SHOW_GALLERY"
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val text =
        when {
          detail == "COMPLETE" -> getString(R.string.desktop_transfer_complete)
          detail.isNotBlank() -> getString(R.string.desktop_transfer_running_detail, detail)
          else -> getString(R.string.desktop_transfer_running)
        }

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.desktop_transfer_title))
        .setContentText(text)
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
          NotificationChannel(CHANNEL_ID, "Desktop Transfer", NotificationManager.IMPORTANCE_LOW)
              .apply {
                description = "Keeps desktop image transfer alive in background"
                setSound(null, null)
                enableVibration(false)
              }
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(channel)
    }
  }

  companion object {
    private const val TAG = "DesktopTransferService"
    const val CHANNEL_ID = "desktop_transfer_channel"
    const val NOTIFICATION_ID = 1002
    const val ACTION_START = "START"
    const val ACTION_UPDATE = "UPDATE"
    const val ACTION_STOP = "STOP"
    const val EXTRA_DETAIL = "DETAIL"
  }
}
