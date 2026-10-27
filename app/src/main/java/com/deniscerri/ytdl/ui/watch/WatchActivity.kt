package com.deniscerri.ytdl.ui.watch

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ChapterItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.VideoPlayerUtil
import com.deniscerri.ytdl.util.extractors.newpipe.NewPipeUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class SuggestionFilter { ALL, RESULTS, CHANNEL, SIMILAR, FAVORITES, HISTORY }

class WatchActivity : BaseActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var navController: NavController
    private lateinit var suggestionsAdapter: WatchSuggestionsAdapter
    private lateinit var currentItem: ResultItem
    private var chapters: List<ChapterItem> = emptyList()
    private var allResults: List<ResultItem> = emptyList()
    private var channelResults: List<ResultItem> = emptyList()
    private var similarResults: List<ResultItem> = emptyList()
    private var filter = SuggestionFilter.ALL
    private var playbackJob: Job? = null
    private var relatedJob: Job? = null
    private var resultsLoading = true
    private var relatedLoading = false
    private var lastListEmpty = false
    private var selectedHeight = 720

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)

        val item = readItem()
        if (item == null) {
            finish()
            return
        }
        currentItem = item

        current?.get()?.takeIf { it !== this }?.finish()
        current = java.lang.ref.WeakReference(this)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]

        val navHost = supportFragmentManager.findFragmentById(R.id.watch_nav_host) as NavHostFragment
        navController = navHost.navController

        player = VideoPlayerUtil.buildPlayer(this)
        player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        com.deniscerri.ytdl.services.WatchPlaybackService.player = player
        androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, com.deniscerri.ytdl.services.WatchPlaybackService::class.java))
        findViewById<PlayerView>(R.id.watch_player).player = player

        suggestionsAdapter = WatchSuggestionsAdapter { playItem(it) }
        findViewById<RecyclerView>(R.id.watch_suggestions).apply {
            layoutManager = LinearLayoutManager(this@WatchActivity)
            adapter = suggestionsAdapter
        }

        setupDownloadButtons()
        setupFavoriteButton()
        setupShareButtons()
        setupSleepTimer()
        findViewById<MaterialButton>(R.id.watch_quality).setOnClickListener {
            val heights = intArrayOf(360, 480, 720, 1080)
            val labels = heights.map { getString(R.string.watch_quality_p, it) }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.watch_quality)
                .setSingleChoiceItems(labels, heights.indexOf(selectedHeight)) { d, which ->
                    d.dismiss()
                    if (heights[which] != selectedHeight) {
                        selectedHeight = heights[which]
                        savePosition()
                        startPlayback(currentItem)
                    }
                }
                .show()
        }
        findViewById<TextView>(R.id.watch_description).setOnClickListener { v -> val t = v as TextView; t.maxLines = if (t.maxLines == 3) Int.MAX_VALUE else 3 }
        setupBackHandler()
        setupChips()
        setupFullscreen()
        playItem(item)
        loadSuggestions()
        applyFullscreen(isLandscape())
    }

    @Suppress("DEPRECATION")
    private fun readItem(): ResultItem? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("result", ResultItem::class.java)
        } else {
            intent.getParcelableExtra("result")
        }
    }

    private fun setupFavoriteButton() {
        findViewById<MaterialButton>(R.id.watch_favorite).setOnClickListener {
            val nowFav = FavoritesStore.toggle(this, currentItem)
            updateFavoriteIcon()
            Toast.makeText(this, if (nowFav) R.string.watch_fav_added else R.string.watch_fav_removed, Toast.LENGTH_SHORT).show()
            if (filter == SuggestionFilter.FAVORITES) showSuggestions()
        }
    }

    private fun updateFavoriteIcon() {
        val fav = FavoritesStore.isFavorite(this, currentItem.url)
        findViewById<MaterialButton>(R.id.watch_favorite).setIconResource(if (fav) R.drawable.ic_watch_fav_on else R.drawable.ic_watch_fav_off)
    }

    private fun setupDownloadButtons() {
        findViewById<MaterialButton>(R.id.watch_download_audio).apply {
            setOnClickListener { onDownloadClick(DownloadType.audio, false) }
            setOnLongClickListener {
                onDownloadClick(DownloadType.audio, true)
                true
            }
        }
        findViewById<MaterialButton>(R.id.watch_download_video).apply {
            setOnClickListener { onDownloadClick(DownloadType.video, false) }
            setOnLongClickListener {
                onDownloadClick(DownloadType.video, true)
                true
            }
        }
    }

    private fun setupChips() {
        mapOf(
            R.id.chip_all to SuggestionFilter.ALL,
            R.id.chip_from_results to SuggestionFilter.RESULTS,
            R.id.chip_from_channel to SuggestionFilter.CHANNEL,
            R.id.chip_similar to SuggestionFilter.SIMILAR,
            R.id.chip_favorites to SuggestionFilter.FAVORITES,
            R.id.chip_history to SuggestionFilter.HISTORY
        ).forEach { (id, value) ->
            findViewById<Chip>(id).setOnClickListener {
                filter = value
                showSuggestions()
                findViewById<RecyclerView>(R.id.watch_suggestions).scrollToPosition(0)
            }
        }
    }

    private fun onDownloadClick(type: DownloadType, longClick: Boolean) {
        if (longClick || sharedPreferences.getBoolean("download_card", true)) {
            showDownloadSheet(type)
        } else {
            val item = currentItem
            Toast.makeText(this, "${getString(R.string.downloading)} ${item.title}", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val downloadItem = withContext(Dispatchers.IO) {
                    downloadViewModel.createDownloadItemFromResult(
                        result = item,
                        givenType = type
                    )
                }
                downloadViewModel.queueDownloads(listOf(downloadItem))
            }
        }
    }

    private fun showDownloadSheet(type: DownloadType) {
        if (navController.currentDestination?.id != R.id.watchPlaceholderFragment) return
        val item = currentItem
        downloadCardViewModel.setResultItem(item)
        downloadCardViewModel.setDownloadItem(null)
        val bundle = Bundle()
        bundle.putSerializable("type", downloadViewModel.getDownloadType(type, item.url))
        navController.navigate(R.id.downloadBottomSheetDialog, bundle)
    }

    private fun savePosition() {
        if (!::player.isInitialized || !::currentItem.isInitialized) return
        if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) return
        val dur = player.duration
        if (dur == androidx.media3.common.C.TIME_UNSET) return
        val pos = player.currentPosition
        val edit = getSharedPreferences("watch_positions", MODE_PRIVATE).edit()
        if (pos < 10000 || dur - pos < 10000) edit.remove(currentItem.url) else edit.putLong(currentItem.url, pos)
        edit.apply()
    }

    private fun restorePosition(item: ResultItem) {
        val pos = getSharedPreferences("watch_positions", MODE_PRIVATE).getLong(item.url, 0L)
        if (pos > 0L) player.seekTo(pos)
    }

    private fun playItem(item: ResultItem) {
        savePosition()
        currentItem = item
        updateFavoriteIcon()
        com.deniscerri.ytdl.services.WatchPlaybackService.title = item.title.toString()
        com.deniscerri.ytdl.services.WatchPlaybackService.author = item.author.toString()
        findViewById<TextView>(R.id.watch_title).text = item.title
        findViewById<TextView>(R.id.watch_author).text = item.author
        channelResults = emptyList()
        updateMetaRow("", "")
        similarResults = emptyList()
        showSuggestions()
        startPlayback(item)
        loadRelated(item)
    }

    private fun loadSuggestions() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                resultViewModel.getAllIds().mapNotNull { resultViewModel.getByID(it) }
            }
            allResults = items
            resultsLoading = false
            showSuggestions()
        }
    }

    private fun loadRelated(item: ResultItem) {
        relatedJob?.cancel()
        relatedLoading = false
        if (!item.url.contains("youtube.com") && !item.url.contains("youtu.be")) {
            showSuggestions()
            return
        }
        relatedLoading = true
        showSuggestions()
        relatedJob = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    NewPipeUtil(applicationContext).getRelatedAndChannel(item.url)
                }
                if (item.url != currentItem.url) return@launch
                res.onSuccess { related ->
                    similarResults = related.first
                    channelResults = related.second
                    val m = related.third
                    val parts = mutableListOf<String>()
                    if (m.views >= 0) parts.add(getString(R.string.watch_views, java.text.NumberFormat.getInstance().format(m.views)))
                    if (m.dateMillis > 0) parts.add(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(m.dateMillis)))
                    updateMetaRow(parts.joinToString(getString(R.string.watch_meta_sep)), m.description)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isActive) {
                    relatedLoading = false
                    showSuggestions()
                }
            }
        }
    }

    private fun showSuggestions() {
        val list = when (filter) {
            SuggestionFilter.RESULTS -> allResults
            SuggestionFilter.CHANNEL -> channelResults
            SuggestionFilter.SIMILAR -> similarResults
            SuggestionFilter.ALL -> allResults + channelResults + similarResults
            SuggestionFilter.FAVORITES -> FavoritesStore.getAll(this)
            SuggestionFilter.HISTORY -> HistoryStore.getAll(this)
        }
        val shown = list.distinctBy { it.url }.filter { it.url != currentItem.url }
        suggestionsAdapter.submitList(shown)
        lastListEmpty = shown.isEmpty()
        updateEmptyState()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayback(item: ResultItem) {
        val loading = findViewById<ProgressBar>(R.id.watch_loading)
        playbackJob?.cancel()
        player.stop()
        player.clearMediaItems()
        loading.isVisible = true
        showChapters(emptyList())

        playbackJob = lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    if (item.urls.isEmpty() || selectedHeight != 720) {
                        resultViewModel.getStreamingUrlAndChapters(item.url, selectedHeight)
                    } else {
                        Pair(item.urls.split("\n"), item.chapters)
                    }
                }
                val urls = data.first
                showChapters(data.second ?: emptyList())
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
                restorePosition(item)
                player.prepare()
                player.play()
                HistoryStore.add(this@WatchActivity, item)
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
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized && com.deniscerri.ytdl.services.WatchPlaybackService.player === player) {
            com.deniscerri.ytdl.services.WatchPlaybackService.player = null
            stopService(android.content.Intent(this, com.deniscerri.ytdl.services.WatchPlaybackService::class.java))
        }
        if (::player.isInitialized) player.release()
        if (current?.get() === this) current = null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ::player.isInitialized && player.isPlaying
        ) {
            enterPip()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPip(): Boolean {
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            return enterPictureInPictureMode(params)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        setPipUi(isInPictureInPictureMode)
        if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            finish()
        }
    }

    private fun setPipUi(inPip: Boolean) {
        val others = listOf(
            R.id.watch_title,
            R.id.watch_author,
            R.id.watch_actions,
            R.id.watch_chips_scroll,
            R.id.watch_suggestions
        )
        others.forEach { findViewById<View>(it).isVisible = !inPip && !isLandscape() }
        findViewById<PlayerView>(R.id.watch_player).useController = !inPip
        updateEmptyState(inPip)
        updateChaptersRow()
        updateMetaRow()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    ::player.isInitialized && player.isPlaying && enterPip()
                ) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    companion object {
        private var current: java.lang.ref.WeakReference<WatchActivity>? = null

        fun isInPip(): Boolean {
            val activity = current?.get() ?: return false
            return activity.isInPictureInPictureMode
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupFullscreen() {
        findViewById<PlayerView>(R.id.watch_player).setFullscreenButtonClickListener {
            requestedOrientation = if (isLandscape()) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        val playerView = findViewById<PlayerView>(R.id.watch_player)
        val params = playerView.layoutParams as ConstraintLayout.LayoutParams
        if (fullscreen) {
            params.dimensionRatio = null
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        } else {
            params.dimensionRatio = "16:9"
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        playerView.layoutParams = params

        listOf(
            R.id.watch_title,
            R.id.watch_author,
            R.id.watch_actions,
            R.id.watch_chips_scroll,
            R.id.watch_suggestions
        ).forEach { findViewById<View>(it).isVisible = !fullscreen }
        updateEmptyState()
        updateChaptersRow()
        updateMetaRow()

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isInPictureInPictureMode) applyFullscreen(landscape)
        if (!landscape && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun isListLoading(): Boolean = when (filter) {
        SuggestionFilter.RESULTS -> resultsLoading
        SuggestionFilter.CHANNEL, SuggestionFilter.SIMILAR -> relatedLoading
        SuggestionFilter.ALL -> resultsLoading || relatedLoading
        SuggestionFilter.FAVORITES -> false
        SuggestionFilter.HISTORY -> false
    }

    private fun updateEmptyState(inPip: Boolean = isInPictureInPictureMode) {
        val emptyView = findViewById<TextView>(R.id.watch_empty)
        if (lastListEmpty && !inPip && !isLandscape()) {
            emptyView.setText(
                if (isListLoading()) R.string.watch_loading_list else R.string.watch_empty_list
            )
            emptyView.isVisible = true
        } else {
            emptyView.isVisible = false
        }
    }

    private fun showChapters(list: List<ChapterItem>) {
        chapters = list.filter { !(it.start_time == 0L && it.end_time == 0L) }
        val container = findViewById<LinearLayout>(R.id.watch_chapters_container)
        container.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        chapters.forEach { chapter ->
            val chip = Chip(this)
            chip.text = "${chapter.start_time.toInt().toStringDuration(Locale.US)}  ${chapter.title}"
            chip.setOnClickListener {
                if (::player.isInitialized) {
                    player.seekTo(chapter.start_time * 1000)
                    player.play()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = gap
            container.addView(chip, params)
        }
        updateChaptersRow()
        updateMetaRow()
    }

    private fun updateChaptersRow() {
        val chipsShown = findViewById<View>(R.id.watch_chips_scroll).isVisible
        findViewById<View>(R.id.watch_chapters_scroll).isVisible = chapters.isNotEmpty() && chipsShown
    }

    private fun updateMetaRow(stats: String? = null, desc: String? = null) {
        val meta = findViewById<TextView>(R.id.watch_meta)
        val description = findViewById<TextView>(R.id.watch_description)
        if (stats != null) meta.text = stats
        if (desc != null) description.text = desc
        val chipsShown = findViewById<View>(R.id.watch_chips_scroll).isVisible
        meta.isVisible = meta.text.isNotEmpty() && chipsShown
        description.isVisible = description.text.isNotEmpty() && chipsShown
    }

    private fun setupShareButtons() {
        findViewById<MaterialButton>(R.id.watch_share).setOnClickListener {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, currentItem.title)
                putExtra(android.content.Intent.EXTRA_TEXT, currentItem.url)
            }
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share)))
        }
        findViewById<MaterialButton>(R.id.watch_copy_link).setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(currentItem.title, currentItem.url))
            Toast.makeText(this, R.string.link_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    private var sleepJob: Job? = null

    private fun setupSleepTimer() {
        findViewById<MaterialButton>(R.id.watch_sleep_timer).setOnClickListener {
            val minutes = intArrayOf(0, 15, 30, 60)
            val labels = arrayOf(
                getString(R.string.watch_sleep_off),
                getString(R.string.watch_sleep_minutes, 15),
                getString(R.string.watch_sleep_minutes, 30),
                getString(R.string.watch_sleep_minutes, 60)
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.watch_sleep_timer)
                .setItems(labels) { _, which -> setSleepTimer(minutes[which]) }
                .show()
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) {
            Toast.makeText(this, R.string.watch_sleep_cancelled, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.watch_sleep_set, minutes), Toast.LENGTH_SHORT).show()
        sleepJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            if (::player.isInitialized) player.pause()
            sleepJob = null
        }
    }
}
