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
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
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

private enum class SuggestionFilter { ALL, RESULTS, CHANNEL, SIMILAR }

class WatchActivity : BaseActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var navController: NavController
    private lateinit var suggestionsAdapter: WatchSuggestionsAdapter
    private lateinit var currentItem: ResultItem
    private var allResults: List<ResultItem> = emptyList()
    private var channelResults: List<ResultItem> = emptyList()
    private var similarResults: List<ResultItem> = emptyList()
    private var filter = SuggestionFilter.ALL
    private var playbackJob: Job? = null
    private var relatedJob: Job? = null

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
        findViewById<PlayerView>(R.id.watch_player).player = player

        suggestionsAdapter = WatchSuggestionsAdapter { playItem(it) }
        findViewById<RecyclerView>(R.id.watch_suggestions).apply {
            layoutManager = LinearLayoutManager(this@WatchActivity)
            adapter = suggestionsAdapter
        }

        setupDownloadButtons()
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
            R.id.chip_similar to SuggestionFilter.SIMILAR
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

    private fun playItem(item: ResultItem) {
        currentItem = item
        findViewById<TextView>(R.id.watch_title).text = item.title
        findViewById<TextView>(R.id.watch_author).text = item.author
        channelResults = emptyList()
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
            showSuggestions()
        }
    }

    private fun loadRelated(item: ResultItem) {
        relatedJob?.cancel()
        if (!item.url.contains("youtube.com") && !item.url.contains("youtu.be")) return
        relatedJob = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    NewPipeUtil(applicationContext).getRelatedAndChannel(item.url)
                }
                if (item.url != currentItem.url) return@launch
                res.onSuccess { related ->
                    similarResults = related.first
                    channelResults = related.second
                    showSuggestions()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showSuggestions() {
        val list = when (filter) {
            SuggestionFilter.RESULTS -> allResults
            SuggestionFilter.CHANNEL -> channelResults
            SuggestionFilter.SIMILAR -> similarResults
            SuggestionFilter.ALL -> allResults + channelResults + similarResults
        }
        suggestionsAdapter.submitList(list.distinctBy { it.url }.filter { it.url != currentItem.url })
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
}
