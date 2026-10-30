package com.deniscerri.ytdl.ui.library

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.services.WatchPlaybackService
import com.deniscerri.ytdl.util.VideoPlayerUtil

class LocalPlayerActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_player)
        val ids = intent.getLongArrayExtra(EXTRA_IDS)
        val titles = intent.getStringArrayExtra(EXTRA_TITLES)
        val isAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val start = intent.getIntExtra(EXTRA_POSITION, 0)
        if (ids == null || titles == null || ids.isEmpty() || ids.size != titles.size) {
            finish()
            return
        }
        val base = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val items = ids.indices.map { i ->
            MediaItem.Builder()
                .setUri(ContentUris.withAppendedId(base, ids[i]))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles[i]).build())
                .build()
        }
        player = VideoPlayerUtil.buildPlayer(this)
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        player.setMediaItems(items, start.coerceIn(0, items.size - 1), 0L)
        player.prepare()
        player.playWhenReady = true
        WatchPlaybackService.player = player
        WatchPlaybackService.title = titles[start.coerceIn(0, titles.size - 1)]
        WatchPlaybackService.author = ""
        ContextCompat.startForegroundService(this, Intent(this, WatchPlaybackService::class.java))
        findViewById<PlayerView>(R.id.local_player).player = player
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) {
            if (WatchPlaybackService.player === player) {
                WatchPlaybackService.player = null
                stopService(Intent(this, WatchPlaybackService::class.java))
            }
            player.release()
        }
    }

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_POSITION = "position"

        fun start(context: Context, ids: LongArray, titles: Array<String>, position: Int, audio: Boolean) {
            context.startActivity(Intent(context, LocalPlayerActivity::class.java)
                .putExtra(EXTRA_IDS, ids)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_POSITION, position)
                .putExtra(EXTRA_AUDIO, audio))
        }
    }
}
