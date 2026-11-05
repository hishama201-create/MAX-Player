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
                Toast.makeText(this@ShortsActivity, R.string.shorts_empty, Toast.LENGTH_SHORT).show()
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
            var holder: ShortsAdapter.ViewHolder? = null
            for (i in 0 until 10) { holder = (pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView).findViewHolderForAdapterPosition(position) as? ShortsAdapter.ViewHolder; if (holder != null) break; kotlinx.coroutines.delay(50) }
            holder?.player?.player = player
            if (urls.size == 2) { val f = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this@ShortsActivity); player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(f.createMediaSource(MediaItem.fromUri(android.net.Uri.parse(urls[1]))), f.createMediaSource(MediaItem.fromUri(android.net.Uri.parse(urls[0]))))) } else player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(urls.last())))
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= 26 && ::player.isInitialized) {
            enterPictureInPictureMode(buildPipParams())
        }
    }

    private fun buildPipParams(): android.app.PictureInPictureParams {
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        val prevIntent = android.app.PendingIntent.getBroadcast(this, 1, android.content.Intent(PIP_ACTION).setPackage(packageName).putExtra("dir", -1), flags)
        val nextIntent = android.app.PendingIntent.getBroadcast(this, 2, android.content.Intent(PIP_ACTION).setPackage(packageName).putExtra("dir", 1), flags)
        val prev = android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pip_prev), "prev", "prev", prevIntent)
        val next = android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pip_next), "next", "next", nextIntent)
        return android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(9, 16)).setActions(listOf(prev, next)).build()
    }

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val dir = intent?.getIntExtra("dir", 0) ?: 0
            val target = pager.currentItem + dir
            if (target in 0 until adapter.itemCount) pager.currentItem = target
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val rv = pager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
        val holder = rv.findViewHolderForAdapterPosition(pager.currentItem) as? ShortsAdapter.ViewHolder
        val vis = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        holder?.let { listOf(it.like, it.share, it.download, it.author, it.title, it.progress).forEach { v -> v.visibility = vis } }
        if (isInPictureInPictureMode) {
            androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver, android.content.IntentFilter(PIP_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { unregisterReceiver(pipReceiver) }
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) finish()
        }
    }

    companion object {
        private const val PIP_ACTION = "com.deniscerri.ytdl.SHORTS_PIP"
    }
}
