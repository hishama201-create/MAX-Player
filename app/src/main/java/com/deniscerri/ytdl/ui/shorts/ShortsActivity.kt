package com.deniscerri.ytdl.ui.shorts

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.watch.FavoritesStore
import com.deniscerri.ytdl.util.VideoPlayerUtil
import com.deniscerri.ytdl.util.extractors.newpipe.NewPipeUtil
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ShortsActivity : BaseActivity() {
    private lateinit var pager: ViewPager2
    private lateinit var feedAdapter: FeedAdapter
    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var queryInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var activePlayerView: PlayerView? = null
    private var activeHolder: FeedHolder? = null
    private var playbackJob: Job? = null
    private var loadJob: Job? = null
    private var activeUrl: String? = null
    private var muted = false
    private var loadGeneration = 0

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        player = VideoPlayerUtil.buildPlayer(this).apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }

        buildScreen()
        feedAdapter = FeedAdapter()
        pager.adapter = feedAdapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                attachAndPlay(position)
            }
        })

        queryInput.setText("#shorts")
        loadFeed(queryInput.text.toString())
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
        }
        root.addView(pager, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = ColorDrawable(Color.argb(155, 0, 0, 0))
        }
        val back = MaterialButton(this).apply {
            text = "‹"
            textSize = 28f
            isAllCaps = false
            contentDescription = getString(R.string.shorts_back)
            minWidth = dp(44)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))

        queryInput = EditText(this).apply {
            hint = getString(R.string.shorts_search_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 45, 45, 45))
                cornerRadius = dp(24).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    startSearch()
                    true
                } else false
            }
        }
        topBar.addView(queryInput, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(6)
        })

        val searchButton = MaterialButton(this).apply {
            text = getString(R.string.search)
            isAllCaps = false
            minWidth = dp(72)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { startSearch() }
        }
        topBar.addView(searchButton, LinearLayout.LayoutParams(dp(82), dp(48)))
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
            text = getString(R.string.shorts_empty)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        setContentView(root)
    }

    private fun startSearch() {
        val text = queryInput.text.toString().trim()
        if (text.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(queryInput.windowToken, 0)
        loadFeed(text)
    }

    private fun loadFeed(input: String) {
        val query = input.trim()
        if (query.isEmpty()) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        playbackJob?.cancel()
        activeUrl = null
        player.stop()
        player.clearMediaItems()
        feedAdapter.submitItems(emptyList())
        pager.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.shorts_loading)
        progress.visibility = View.VISIBLE

        loadJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val extractor = NewPipeUtil(applicationContext)
                    val normalized = normalizeUrl(query)
                    when {
                        isYouTubeUrl(normalized) && isChannelUrl(normalized) ->
                            extractor.getShortsData(normalized).getOrThrow()
                        isYouTubeUrl(normalized) -> {
                            val direct = extractor.getVideoData(normalized).getOrThrow()
                            val related = runCatching {
                                extractor.getRelatedAndChannel(normalized).getOrThrow().first
                            }.getOrDefault(emptyList())
                            (direct + related.filter { durationSeconds(it.duration) in 1..180 })
                                .distinctBy { it.url }
                        }
                        else -> {
                            val searchQuery = if (normalized.contains("#shorts", ignoreCase = true)) {
                                normalized
                            } else {
                                normalized + " #shorts"
                            }
                            extractor.search(searchQuery).getOrThrow()
                                .filter { durationSeconds(it.duration) in 1..180 }
                        }
                    }
                }
                feedAdapter.submitItems(loaded)
                if (loaded.isEmpty()) {
                    status.text = getString(R.string.shorts_no_results)
                    status.visibility = View.VISIBLE
                    pager.visibility = View.GONE
                } else {
                    status.visibility = View.GONE
                    pager.visibility = View.VISIBLE
                    pager.setCurrentItem(0, false)
                    pager.post { attachAndPlay(0) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedAdapter.submitItems(emptyList())
                pager.visibility = View.GONE
                status.text = getString(R.string.shorts_load_failed)
                status.visibility = View.VISIBLE
            } finally {
                if (generation == loadGeneration) progress.visibility = View.GONE
            }
        }
    }

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("youtube.com/", true) || trimmed.startsWith("www.youtube.com/", true) ||
            trimmed.startsWith("m.youtube.com/", true) || trimmed.startsWith("youtu.be/", true)) {
            return "https://" + trimmed
        }
        return trimmed
    }

    private fun isYouTubeUrl(value: String): Boolean {
        val host = runCatching { Uri.parse(value).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    private fun isChannelUrl(value: String): Boolean {
        val path = runCatching { Uri.parse(value).path }.getOrNull()?.lowercase(Locale.ROOT) ?: return false
        return path.startsWith("/@") || path.startsWith("/channel/") ||
            path.startsWith("/c/") || path.startsWith("/user/")
    }

    private fun durationSeconds(value: String): Int {
        val parts = value.split(':').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return Int.MAX_VALUE
        return parts.fold(0L) { total, part -> total * 60 + part }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun attachAndPlay(position: Int) {
        if (!::feedAdapter.isInitialized || position !in feedAdapter.items.indices) return
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return
        val holder = recycler.findViewHolderForAdapterPosition(position) as? FeedHolder
        if (holder == null) {
            pager.postDelayed({ attachAndPlay(position) }, 100)
            return
        }
        if (activePlayerView !== holder.playerView) {
            activePlayerView?.player = null
            activePlayerView = holder.playerView
            activePlayerView?.player = player
        }
        activeHolder = holder
        updateHolderState(holder, feedAdapter.items[position])
        startPlayback(feedAdapter.items[position])
    }

    private fun updateHolderState(holder: FeedHolder, item: ResultItem) {
        holder.favoriteButton.text = getString(
            if (FavoritesStore.isFavorite(this, item.url)) R.string.shorts_saved else R.string.shorts_favorite
        )
        holder.muteButton.text = getString(if (muted) R.string.shorts_unmute else R.string.shorts_mute)
    }

    private fun startPlayback(item: ResultItem) {
        if (activeUrl == item.url) return
        activeUrl = item.url
        playbackJob?.cancel()
        player.stop()
        player.clearMediaItems()
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        playbackJob = lifecycleScope.launch {
            try {
                val urls = withContext(Dispatchers.IO) {
                    resultViewModel.getStreamingUrlAndChapters(item.url, 1080).first
                }.filter { it.isNotBlank() }
                if (urls.isEmpty()) throw IllegalStateException("No playable stream")
                if (urls.size >= 2) {
                    val factory = DefaultMediaSourceFactory(this@ShortsActivity)
                    val audioSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[0])))
                    val videoSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[1])))
                    player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(urls[0])))
                }
                player.prepare()
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeUrl == item.url) {
                    status.text = getString(R.string.shorts_playback_failed)
                    status.visibility = View.VISIBLE
                }
            } finally {
                if (activeUrl == item.url) progress.visibility = View.GONE
            }
        }
    }

    private fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun toggleMute(holder: FeedHolder) {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        holder.muteButton.text = getString(if (muted) R.string.shorts_unmute else R.string.shorts_mute)
    }

    private fun toggleFavorite(item: ResultItem, holder: FeedHolder) {
        val saved = FavoritesStore.toggle(this, item)
        holder.favoriteButton.text = getString(if (saved) R.string.shorts_saved else R.string.shorts_favorite)
    }

    private fun queueDownload(item: ResultItem) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val downloadItem = withContext(Dispatchers.IO) {
                    downloadViewModel.createDownloadItemFromResult(item, givenType = DownloadType.video)
                }
                val result = downloadViewModel.queueDownloads(listOf(downloadItem))
                Toast.makeText(this@ShortsActivity, result.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ShortsActivity, R.string.shorts_load_failed, Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun share(item: ResultItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TEXT, item.url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun openInYouTube(item: ResultItem) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
        }.onFailure {
            Toast.makeText(this, R.string.shorts_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildActionButton(label: Int): MaterialButton {
        return MaterialButton(this).apply {
            text = getString(label)
            isAllCaps = false
            textSize = 12f
            minWidth = dp(72)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.argb(185, 20, 20, 20))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        if (::player.isInitialized) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        playbackJob?.cancel()
        activePlayerView?.player = null
        activePlayerView = null
        activeHolder = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private inner class FeedAdapter : RecyclerView.Adapter<FeedHolder>() {
        val items = mutableListOf<ResultItem>()

        fun submitItems(newItems: List<ResultItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FeedHolder {
            val root = FrameLayout(parent.context).apply { setBackgroundColor(Color.BLACK) }
            val playerView = PlayerView(parent.context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            root.addView(playerView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            playerView.setOnClickListener { togglePlayback() }

            val info = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(100), dp(28))
                background = ColorDrawable(Color.argb(150, 0, 0, 0))
            }
            val title = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 3
            }
            val author = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, dp(5), 0, 0)
            }
            info.addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            info.addView(author, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            root.addView(info, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))

            val actions = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val favorite = buildActionButton(R.string.shorts_favorite)
            val mute = buildActionButton(R.string.shorts_mute)
            val download = buildActionButton(R.string.shorts_download)
            val shareButton = buildActionButton(R.string.shorts_share)
            val open = buildActionButton(R.string.shorts_open)
            listOf(favorite, mute, download, shareButton, open).forEach { button ->
                actions.addView(button, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(44)
                ).apply { bottomMargin = dp(7) })
            }
            root.addView(actions, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(8) })

            return FeedHolder(root, playerView, title, author, favorite, mute, download, shareButton, open)
        }

        override fun onBindViewHolder(holder: FeedHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title.ifBlank { item.url }
            holder.author.text = item.author
            updateHolderState(holder, item)
            holder.favoriteButton.setOnClickListener { toggleFavorite(item, holder) }
            holder.muteButton.setOnClickListener { toggleMute(holder) }
            holder.downloadButton.setOnClickListener { queueDownload(item) }
            holder.shareButton.setOnClickListener { share(item) }
            holder.openButton.setOnClickListener { openInYouTube(item) }
        }

        override fun onViewRecycled(holder: FeedHolder) {
            if (activePlayerView === holder.playerView) {
                holder.playerView.player = null
                activePlayerView = null
                activeHolder = null
            }
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class FeedHolder(
        itemView: View,
        val playerView: PlayerView,
        val title: TextView,
        val author: TextView,
        val favoriteButton: MaterialButton,
        val muteButton: MaterialButton,
        val downloadButton: MaterialButton,
        val shareButton: MaterialButton,
        val openButton: MaterialButton
    ) : RecyclerView.ViewHolder(itemView)
}
