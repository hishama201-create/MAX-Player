package com.deniscerri.ytdl.ui.shorts

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.watch.FavoritesStore
import com.deniscerri.ytdl.util.VideoPlayerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortsActivity : BaseActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var pager: ViewPager2
    private lateinit var adapter: ShortsAdapter
    private var playJob: Job? = null
    private var currentPos = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shorts)
        window.navigationBarColor = android.graphics.Color.BLACK
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]

        player = VideoPlayerUtil.buildPlayer(this)
        player.repeatMode = ExoPlayer.REPEAT_MODE_ONE

        adapter = ShortsAdapter(
            onLike = { item -> toggleLike(item) },
            onShare = { item -> shareItem(item) },
            onDownload = { item -> downloadItem(item) }
        )
        pager = findViewById(R.id.shorts_pager)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                playAt(position)
            }
        })

        loadShorts()
    }

    private fun loadShorts() {
        val loading = findViewById<ProgressBar>(R.id.shorts_loading)
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            if (ShortsChannelsStore.getAll(this@ShortsActivity).isEmpty()) {
                ShortsChannelsStore.add(this@ShortsActivity, "https://www.youtube.com/@RawaaAlTanzeel/shorts")
            }
            val channels = ShortsChannelsStore.getAll(this@ShortsActivity)
            val items = withContext(Dispatchers.IO) {
                channels.flatMap { resultViewModel.getChannelShorts(it, 20) }.shuffled()
            }
            loading.visibility = View.GONE
            if (items.isEmpty()) {
                Toast.makeText(this@ShortsActivity, R.string.shorts, Toast.LENGTH_SHORT).show()
                return@launch
            }
            adapter.likedUrls = FavoritesStore.getAll(this@ShortsActivity).map { it.url }.toSet()
            adapter.submitList(items) { playAt(0) }
        }
    }

    private fun playAt(position: Int) {
        if (position < 0 || position >= adapter.itemCount) return
        if (position == currentPos && player.isPlaying) return
        currentPos = position
        val item = adapter.currentList[position]
        playJob?.cancel()
        player.stop()
        player.clearMediaItems()
        playJob = lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) { resultViewModel.getStreamingUrlAndChapters(item.url, 480).first }
            val url = urls.firstOrNull { it.isNotBlank() } ?: return@launch
            val holder = (pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView).findViewHolderForAdapterPosition(position) as? ShortsAdapter.ViewHolder
            holder?.player?.player = player
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(urls.last())))
            player.prepare()
            player.play()
        }
    }

    private fun toggleLike(item: ResultItem) {
        val nowFav = FavoritesStore.toggle(this, item)
        adapter.likedUrls = FavoritesStore.getAll(this).map { it.url }.toSet()
        Toast.makeText(this, if (nowFav) R.string.watch_fav_added else R.string.watch_fav_removed, Toast.LENGTH_SHORT).show()
    }

    private fun shareItem(item: ResultItem) {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, item.title)
            putExtra(android.content.Intent.EXTRA_TEXT, item.url)
        }
        startActivity(android.content.Intent.createChooser(send, getString(R.string.share)))
    }

    private fun downloadItem(item: ResultItem) {
        Toast.makeText(this, "${getString(R.string.downloading)} ${item.title}", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val downloadItem = withContext(Dispatchers.IO) {
                downloadViewModel.createDownloadItemFromResult(result = item, givenType = DownloadType.video)
            }
            downloadViewModel.queueDownloads(listOf(downloadItem))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playJob?.cancel()
        if (::player.isInitialized) player.release()
    }
}
