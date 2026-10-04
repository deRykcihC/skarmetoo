package com.deryk.skarmetoo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.MainActivity

class AnalysisService : Service() {

  private var isForegroundStarted = false
  private var liveUpdateDismissed = false
  private var paused = false
  private var showControls = false
  private var totalImages = 0
  private var completedImages = 0
  private var modelName = ""
  private var modelIcon: Icon? = null
  // Match the app's rainbow easter egg, and keep the choice stable for this session.
  private val logoRes =
      if (kotlin.random.Random.nextFloat() < 0.069f) R.drawable.app_logo_rainbow
      else R.drawable.app_logo
  private val cardLogo by lazy {
    BitmapFactory.decodeResource(resources, logoRes, BitmapFactory.Options().apply { inSampleSize = 4 })
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    Log.d(TAG, "onStartCommand action=$action isForegroundStarted=$isForegroundStarted")

    when (action) {
      ACTION_DISMISS -> {
        // Keep processing, but respect dismissal for the rest of this service session.
        liveUpdateDismissed = true
      }
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
        totalImages = intent?.getIntExtra(EXTRA_TOTAL, totalImages) ?: totalImages
        completedImages = intent?.getIntExtra(EXTRA_COMPLETED, completedImages) ?: completedImages
        paused = intent?.getBooleanExtra(EXTRA_PAUSED, paused) ?: paused
        showControls = intent?.getBooleanExtra(EXTRA_SHOW_CONTROLS, showControls) ?: showControls
        val selectedModel = intent?.getStringExtra(EXTRA_MODEL) ?: modelName
        if (modelIcon == null || selectedModel != modelName) {
          modelName = selectedModel
          modelIcon = createModelIcon(selectedModel)
        }
        val notification = createNotification(totalImages, completedImages)

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

  private fun createNotification(total: Int, completed: Int): Notification {
    val intent =
        Intent(this, MainActivity::class.java).apply {
          action = "SHOW_GALLERY"
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val safeTotal = total.coerceAtLeast(1)
    val safeCompleted = completed.coerceIn(0, safeTotal)
    val progressText = getString(R.string.notification_processed, safeCompleted, safeTotal)
    val title =
        getString(if (paused) R.string.notification_analysis_paused else R.string.notification_title)
    val deleteIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, AnalysisService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val stopIntent =
        PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_STOP_PROCESSING).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (Build.VERSION.SDK_INT >= 36) {
      return Notification.Builder(this, CHANNEL_ID)
          .setContentTitle(title)
          .setContentText(progressText)
          .setSmallIcon(logoRes)
          .setLargeIcon(modelIcon)
          .setContentIntent(pendingIntent)
          .setDeleteIntent(deleteIntent)
          .setOngoing(true)
          .setOnlyAlertOnce(true)
          .setShowWhen(false)
          .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
          .setRequestPromotedOngoing(!liveUpdateDismissed && !paused)
          .setShortCriticalText("${safeCompleted * 100L / safeTotal}%")
          .setStyle(
              Notification.ProgressStyle()
                  .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_analysis_tracker))
                  .setProgressSegments(listOf(Notification.ProgressStyle.Segment(safeTotal)))
                  .setProgress(safeCompleted))
          .apply {
            if (showControls && !paused) {
              addAction(
                  Notification.Action.Builder(
                      Icon.createWithResource(this@AnalysisService, R.drawable.ic_analysis_stop),
                      getString(R.string.notification_stop_processing),
                      stopIntent)
                      .build())
            }
          }
          .build()
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(progressText)
        .setProgress(safeTotal, safeCompleted, false)
        .setSmallIcon(logoRes)
        .setLargeIcon(cardLogo)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .apply {
          if (showControls && !paused) {
            addAction(R.drawable.ic_analysis_stop, getString(R.string.notification_stop_processing), stopIntent)
          }
        }
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

  private fun createModelIcon(model: String): Icon {
    if (model == "DESKTOP") {
      return Icon.createWithResource(this, R.drawable.ic_model_desktop)
    }
    if (model == "AICORE") {
      return Icon.createWithResource(this, R.drawable.ic_model_gemini)
    }
    if (model == "GEMMA_3N" || model == "GEMMA_4") {
      return Icon.createWithResource(this, R.drawable.ic_model_gemma)
    }
    if (model == "LFM") {
      return Icon.createWithResource(this, R.drawable.ic_model_lfm).setTint(Color.WHITE)
    }
    // VLM and GGUF have no shared brand mark: use readable, transparent letter badges.
    val label = if (model == "GGUF") "GGUF" else "VLM"
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = 7f
    }
    canvas.drawRoundRect(5f, 20f, 123f, 108f, 16f, 16f, paint)
    paint.apply {
      style = Paint.Style.FILL
      textSize = if (label == "GGUF") 34f else 44f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
    canvas.drawText(label, 64f, 64f - (paint.ascent() + paint.descent()) / 2f, paint)
    return Icon.createWithBitmap(bitmap)
  }

  companion object {
    private const val TAG = "AnalysisService"
    const val CHANNEL_ID = "analysis_channel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_START = "START"
    const val ACTION_UPDATE = "UPDATE"
    const val ACTION_STOP = "STOP"
    private const val ACTION_DISMISS = "DISMISS"
    const val EXTRA_REMAINING = "REMAINING"
    const val EXTRA_TOTAL = "TOTAL"
    const val EXTRA_COMPLETED = "COMPLETED"
    const val EXTRA_PAUSED = "PAUSED"
    const val EXTRA_MODEL = "MODEL"
    const val EXTRA_SHOW_CONTROLS = "SHOW_CONTROLS"
    const val ACTION_STOP_PROCESSING = "com.deryk.skarmetoo.action.STOP_PROCESSING"
  }
}
