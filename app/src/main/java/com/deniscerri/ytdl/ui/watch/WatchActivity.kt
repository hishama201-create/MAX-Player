package com.deniscerri.ytdl.ui.watch

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.VideoPlayerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchActivity : BaseActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        val item = readItem()
        if (item == null) {
            finish()
            return
        }

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]

        findViewById<TextView>(R.id.watch_title).text = item.title
        findViewById<TextView>(R.id.watch_author).text = item.author

        player = VideoPlayerUtil.buildPlayer(this)
        findViewById<PlayerView>(R.id.watch_player).player = player

        startPlayback(item)
    }

    @Suppress("DEPRECATION")
    private fun readItem(): ResultItem? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("result", ResultItem::class.java)
        } else {
            intent.getParcelableExtra("result")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayback(item: ResultItem) {
        val loading = findViewById<ProgressBar>(R.id.watch_loading)
        lifecycleScope.launch {
            try {
                val urls = withContext(Dispatchers.IO) {
                    if (item.urls.isEmpty()) {
                        resultViewModel.getStreamingUrlAndChapters(item.url).first
                    } else {
                        item.urls.split("\n")
                    }
                }
                if (urls.isEmpty()) throw Exception("No Data found!")

                if (urls.size == 2) {
                    val factory = DefaultMediaSourceFactory(this@WatchActivity)
                    val audioSource: MediaSource =
                        factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[0])))
                    val videoSource: MediaSource =
                        factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[1])))
                    player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    player.addMediaItem(MediaItem.fromUri(Uri.parse(urls[0])))
                }
                player.prepare()
                player.play()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                loading.isVisible = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }
}
