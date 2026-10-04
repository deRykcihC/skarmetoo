package com.deryk.skarmetoo.network

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.MainActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives the daily leaderboard update topic and shows it while the app is foregrounded. */
class LeaderboardMessagingService : FirebaseMessagingService() {
  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel(this)
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val isLeaderboardUpdate = message.data["type"] == "leaderboard_update"
    if (!isLeaderboardUpdate && message.notification == null) return

    val title =
        message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.leaderboard_notification_title)
    val body =
        message.notification?.body
            ?: message.data["body"]
            ?: getString(R.string.leaderboard_notification_body)

    showNotification(title, body)
  }

  @Suppress("DEPRECATION")
  override fun onNewToken(token: String) {
    super.onNewToken(token)
    val preferences =
        getSharedPreferences(BenchmarkLeaderboardClient.PREFERENCES_FILE, MODE_PRIVATE)
    if (!preferences.getBoolean(BenchmarkLeaderboardClient.PREF_LEADERBOARD_OPTED_IN, false)) {
      return
    }

    // FCM normally restores topic subscriptions, but explicitly resubscribe after token rotation
    // so an opted-in device cannot silently miss a future daily update.
    runCatching {
          FirebaseMessaging.getInstance()
              .subscribeToTopic(BenchmarkLeaderboardClient.LEADERBOARD_UPDATES_TOPIC)
        }
        .onFailure { error ->
          android.util.Log.w(BenchmarkLeaderboardClient.TAG, "Could not restore leaderboard topic", error)
        }
  }

  private fun showNotification(title: String, body: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      return
    }

    val openLeaderboardIntent =
        Intent(this, MainActivity::class.java).apply {
          action = BenchmarkLeaderboardClient.ACTION_SHOW_LEADERBOARD
          flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openLeaderboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val notification =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_leaderboard)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val NOTIFICATION_ID = 0x4c42

    fun ensureNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val channel =
          NotificationChannel(
              context.getString(R.string.leaderboard_notification_channel_id),
              context.getString(R.string.leaderboard_notification_channel_name),
              NotificationManager.IMPORTANCE_DEFAULT,
          )
      context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }

  private val channelId: String
    get() = getString(R.string.leaderboard_notification_channel_id)
}
