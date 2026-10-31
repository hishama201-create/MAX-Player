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
        if (WatchPlaybackService.detached) { val old = WatchPlaybackService.player; WatchPlaybackService.detached = false; WatchPlaybackService.player = null; stopService(Intent(this, WatchPlaybackService::class.java)); old?.release() }
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
        setupSeekGesture(findViewById(R.id.local_player), findViewById(R.id.seek_overlay))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val loc = java.util.Locale.getDefault()
        return if (h > 0) String.format(loc, "%d:%02d:%02d", h, m, sec) else String.format(loc, "%02d:%02d", m, sec)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSeekGesture(view: PlayerView, overlay: android.widget.TextView) {
        val dp = resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var seeking = false
        var startPos = 0L
        var target = 0L
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; seeking = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!seeking && kotlin.math.abs(dx) > 24 * dp && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                        seeking = true
                        startPos = player.currentPosition
                        view.hideController()
                    }
                    if (seeking) {
                        val dur = player.duration
                        val t = startPos + (dx / dp * 150).toLong()
                        target = if (dur > 0) t.coerceIn(0L, dur) else t.coerceAtLeast(0L)
                        val diff = target - startPos
                        overlay.text = fmtTime(target) + "\n[" + (if (diff >= 0) "+" else "-") + fmtTime(kotlin.math.abs(diff)) + "]"
                        overlay.visibility = android.view.View.VISIBLE
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (seeking) {
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) player.seekTo(target)
                        overlay.visibility = android.view.View.GONE
                        seeking = false
                        return@setOnTouchListener true
                    }
                }
            }
            seeking
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) {
            if (isFinishing && player.isPlaying && WatchPlaybackService.player === player) { WatchPlaybackService.detached = true; return }
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
