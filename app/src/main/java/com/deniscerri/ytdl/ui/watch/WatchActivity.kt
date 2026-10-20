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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.VideoPlayerUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchActivity : BaseActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var suggestionsAdapter: WatchSuggestionsAdapter
    private lateinit var currentItem: ResultItem
    private var allResults: List<ResultItem> = emptyList()
    private var playbackJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        val item = readItem()
        if (item == null) {
            finish()
            return
        }
        currentItem = item

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]

        player = VideoPlayerUtil.buildPlayer(this)
        findViewById<PlayerView>(R.id.watch_player).player = player

        suggestionsAdapter = WatchSuggestionsAdapter { playItem(it) }
        findViewById<RecyclerView>(R.id.watch_suggestions).apply {
            layoutManager = LinearLayoutManager(this@WatchActivity)
            adapter = suggestionsAdapter
        }

        playItem(item)
        loadSuggestions()
    }

    @Suppress("DEPRECATION")
    private fun readItem(): ResultItem? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("result", ResultItem::class.java)
        } else {
            intent.getParcelableExtra("result")
        }
    }

    private fun playItem(item: ResultItem) {
        currentItem = item
        findViewById<TextView>(R.id.watch_title).text = item.title
        findViewById<TextView>(R.id.watch_author).text = item.author
        showSuggestions()
        startPlayback(item)
    }

    private fun loadSuggestions() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                resultViewModel.getAllIds().mapNotNull { resultViewModel.getByID(it) }
            }
            allResults = items
            showSuggestions()
        }
    }

    private fun showSuggestions() {
        suggestionsAdapter.submitList(allResults.filter { it.url != currentItem.url })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayback(item: ResultItem) {
        val loading = findViewById<ProgressBar>(R.id.watch_loading)
        playbackJob?.cancel()
        player.stop()
        player.clearMediaItems()
        loading.isVisible = true

        playbackJob = lifecycleScope.launch {
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
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(urls[0])))
                }
                player.prepare()
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isActive) loading.isVisible = false
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
