package com.deniscerri.ytdl.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerNotificationManager
import com.deniscerri.ytdl.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WatchPlaybackService : Service() {
    private var manager: PlayerNotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = player
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startFg(NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setOngoing(true)
            .build())
        if (manager == null) {
            manager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
                .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                    override fun getCurrentContentTitle(player: Player): CharSequence = title
                    override fun createCurrentContentIntent(player: Player): android.app.PendingIntent? = null
                    override fun getCurrentContentText(player: Player): CharSequence? = author
                    override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): android.graphics.Bitmap? = null
                })
                .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                        startFg(notification)
                    }
                    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                        stopSelf()
                    }
                })
                .setSmallIconResourceId(R.drawable.ic_music)
                .build()
        }
        manager?.setPlayer(p)
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Watch playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        manager?.setPlayer(null)
        manager = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "watch_playback"
        private const val NOTIFICATION_ID = 7421
        @Volatile var player: Player? = null
        @Volatile var title: String = ""
        @Volatile var author: String = ""
    }
}
