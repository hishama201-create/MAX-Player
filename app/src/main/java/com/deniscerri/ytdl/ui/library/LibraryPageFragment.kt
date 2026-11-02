package com.deniscerri.ytdl.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import kotlinx.coroutines.launch
import android.widget.ImageView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

class LibraryPageFragment : Fragment() {
    private var tab = 0
    private lateinit var list: RecyclerView
    private lateinit var placeholder: TextView
    private lateinit var grant: Button
    private lateinit var videoAdapter: LibraryVideoAdapter
    private lateinit var musicAdapter: LibraryMusicAdapter
    private lateinit var folderAdapter: LibraryFolderAdapter
    private var currentVideos: List<LocalVideo> = emptyList()
    private var currentSongs: List<LocalSong> = emptyList()
    private var lastGranted = false
    private var isGrid = false
    private lateinit var optionsBtn: View
    private var sortKey = "title"
    private var sortAsc = true
    private var viewMode = "files"
    private var allVideos: List<LocalVideo> = emptyList()
    private var openFolder: String? = null
    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openFolder = null; renderVideos() }
    }

    private fun permissionFor(): String {
        if (Build.VERSION.SDK_INT < 33) return Manifest.permission.READ_EXTERNAL_STORAGE
        return if (tab == 0) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        if (view == null) return@registerForActivityResult
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = requireArguments().getInt(ARG_TAB, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_library_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.library_list)
        optionsBtn = view.findViewById(R.id.library_options_btn)
        optionsBtn.setOnClickListener { showOptionsSheet() }
        placeholder = view.findViewById(R.id.library_placeholder)
        grant = view.findViewById(R.id.library_grant)
        videoAdapter = LibraryVideoAdapter(viewLifecycleOwner.lifecycleScope) { video ->
            val idx = currentVideos.indexOf(video); if (idx >= 0) LocalPlayerActivity.start(requireContext(), currentVideos.map { it.id }.toLongArray(), currentVideos.map { it.title }.toTypedArray(), idx, false)
        }
        musicAdapter = LibraryMusicAdapter(viewLifecycleOwner.lifecycleScope) { song ->
            val idx = currentSongs.indexOf(song); if (idx >= 0) LocalPlayerActivity.start(requireContext(), currentSongs.map { it.id }.toLongArray(), currentSongs.map { it.title }.toTypedArray(), idx, true, currentSongs.map { it.albumId }.toLongArray(), currentSongs.map { it.artist }.toTypedArray())
        }
        folderAdapter = LibraryFolderAdapter { folder -> openFolder = folder.path; renderVideos() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        isGrid = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).getBoolean("grid_" + tab, false)
        sortKey = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).getString("sort_key_" + tab, "title") ?: "title"
        sortAsc = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).getBoolean("sort_asc_" + tab, true)
        viewMode = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).getString("view_mode", "files") ?: "files"
        if (viewMode == "all") viewMode = "folders"
        applyLayout()
        grant.setOnClickListener { permissionLauncher.launch(permissionFor()) }
        refresh()
    }

    fun scrollToTop() {
        if (::list.isInitialized) list.scrollToPosition(0)
    }

    private fun reload() {
        if (tab == 0) { renderVideos() }
        else { currentSongs = sortedSongs(currentSongs); musicAdapter.submitList(currentSongs) }
    }

    private fun showOptionsSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val sheet = layoutInflater.inflate(R.layout.library_options_sheet, null)
        val primary = MaterialColors.getColor(sheet, R.attr.colorPrimary)
        val normal = MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorOnSurface)
        val gridIcon = sheet.findViewById<ImageView>(R.id.opt_layout_grid_icon)
        val gridText = sheet.findViewById<TextView>(R.id.opt_layout_grid_text)
        val listIcon = sheet.findViewById<ImageView>(R.id.opt_layout_list_icon)
        val listText = sheet.findViewById<TextView>(R.id.opt_layout_list_text)
        fun paint() {
            gridIcon.setColorFilter(if (isGrid) primary else normal)
            gridText.setTextColor(if (isGrid) primary else normal)
            listIcon.setColorFilter(if (isGrid) normal else primary)
            listText.setTextColor(if (isGrid) normal else primary)
        }
        fun choose(grid: Boolean) {
            isGrid = grid
            ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("grid_" + tab, grid).apply()
            applyLayout()
            paint()
        }
        sheet.findViewById<View>(R.id.opt_layout_grid).setOnClickListener { choose(true) }
        sheet.findViewById<View>(R.id.opt_layout_list).setOnClickListener { choose(false) }
        val sortIds = mapOf("title" to R.id.opt_sort_title, "size" to R.id.opt_sort_size, "duration" to R.id.opt_sort_duration, "date" to R.id.opt_sort_date, "type" to R.id.opt_sort_type, "path" to R.id.opt_sort_path, "resolution" to R.id.opt_sort_resolution, "framerate" to R.id.opt_sort_framerate, "status" to R.id.opt_sort_status, "watch_time" to R.id.opt_sort_watch_time)
        fun paintSort() {
            for ((k, id) in sortIds) {
                val c = if (sortKey == k) primary else normal
                val base = resources.getResourceEntryName(id)
                sheet.findViewById<ImageView>(resources.getIdentifier(base + "_icon", "id", ctx.packageName)).setColorFilter(c)
                sheet.findViewById<TextView>(resources.getIdentifier(base + "_text", "id", ctx.packageName)).setTextColor(c)
            }
            sheet.findViewById<TextView>(R.id.opt_order_asc).setTextColor(if (sortAsc) primary else normal)
            sheet.findViewById<TextView>(R.id.opt_order_desc).setTextColor(if (sortAsc) normal else primary)
        }
        fun saveSort() {
            ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).edit().putString("sort_key_" + tab, sortKey).putBoolean("sort_asc_" + tab, sortAsc).apply()
            reload()
            paintSort()
        }
        for ((k, id) in sortIds) sheet.findViewById<View>(id).setOnClickListener { sortKey = k; saveSort() }
        sheet.findViewById<View>(R.id.opt_order_asc).setOnClickListener { sortAsc = true; saveSort() }
        sheet.findViewById<View>(R.id.opt_order_desc).setOnClickListener { sortAsc = false; saveSort() }
        if (tab == 1) listOf(R.id.opt_sort_date, R.id.opt_sort_type, R.id.opt_sort_path, R.id.opt_sort_resolution, R.id.opt_sort_framerate, R.id.opt_sort_status, R.id.opt_sort_watch_time).forEach { sheet.findViewById<View>(it).visibility = View.GONE }
        val modeIds = mapOf("files" to R.id.opt_mode_files, "folders" to R.id.opt_mode_folders)
        fun paintMode() {
            for ((k, id) in modeIds) {
                val c = if (viewMode == k) primary else normal
                val base = resources.getResourceEntryName(id)
                sheet.findViewById<ImageView>(resources.getIdentifier(base + "_icon", "id", ctx.packageName)).setColorFilter(c)
                sheet.findViewById<TextView>(resources.getIdentifier(base + "_text", "id", ctx.packageName)).setTextColor(c)
            }
        }
        for ((k, id) in modeIds) sheet.findViewById<View>(id).setOnClickListener {
            viewMode = k
            ctx.getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).edit().putString("view_mode", k).apply()
            paintMode()
            openFolder = null
            if (tab == 0) renderVideos()
        }
        if (tab == 1) listOf(R.id.opt_mode_title, R.id.opt_mode_files, R.id.opt_mode_folders).forEach { sheet.findViewById<View>(it).visibility = View.GONE }
        paintMode()
        paintSort()
        paint()
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun folderName(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        return if (last.isEmpty()) "/" else last
    }

    private fun renderVideos() {
        val folder = openFolder
        backCallback.isEnabled = (folder != null)
        if (viewMode == "files" || folder != null) {
            val shown = if (folder == null) allVideos else allVideos.filter { it.path == folder }
            list.adapter = videoAdapter
            applyLayout()
            currentVideos = sortedVideos(shown)
            videoAdapter.submitList(currentVideos)
        } else {
            val groups = allVideos.groupBy { it.path }
            val folders = groups.map { (p, v) -> LibraryFolder(p, folderName(p), v.size) }.sortedBy { it.name.lowercase() }
            list.adapter = folderAdapter
            applyLayout()
            folderAdapter.submitList(folders)
        }
    }

    private fun applyLayout() {
        val ctx = requireContext()
        list.layoutManager = if (isGrid && !(tab == 0 && viewMode != "files" && openFolder == null)) GridLayoutManager(ctx, 2) else LinearLayoutManager(ctx)
        videoAdapter.isGrid = isGrid
        musicAdapter.isGrid = isGrid
        if (list.adapter != null) list.adapter = list.adapter
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && hasPermission() != lastGranted) refresh()
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return ContextCompat.checkSelfPermission(requireContext(), permissionFor()) == PackageManager.PERMISSION_GRANTED
    }

    private fun refresh() {
        lastGranted = hasPermission()
        if (!lastGranted) {
            showMessage(getString(if (tab == 0) R.string.library_permission_needed else R.string.library_permission_needed_music), true)
        } else if (tab == 0) {
            loadVideos()
        } else {
            loadSongs()
        }
    }

    private fun loadVideos() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val videos = LocalVideoRepository.loadVideos(ctx)
            if (videos.isEmpty()) {
                showMessage(getString(R.string.library_no_videos), false)
            } else {
                allVideos = videos
                showList()
                renderVideos()
            }
        }
    }

    private fun loadSongs() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = LocalMusicRepository.loadSongs(ctx)
            if (songs.isEmpty()) {
                showMessage(getString(R.string.library_no_music), false)
            } else {
                list.adapter = musicAdapter
                showList()
                currentSongs = sortedSongs(songs); musicAdapter.submitList(currentSongs)
            }
        }
    }

    private fun sortedVideos(items: List<LocalVideo>): List<LocalVideo> {
        val prefs = requireContext().getSharedPreferences("watch_positions", android.content.Context.MODE_PRIVATE)
        val sorted = when (sortKey) {
            "title" -> items.sortedBy { it.title.lowercase() }
            "size" -> items.sortedBy { it.size }
            "duration" -> items.sortedBy { it.durationMs }
            "date" -> items.sortedBy { it.dateAdded }
            "type" -> items.sortedBy { it.mimeType }
            "path" -> items.sortedBy { it.path.lowercase() }
            "resolution" -> items.sortedBy { it.width.toLong() * it.height }
            "framerate" -> items.sortedBy { it.frameRate }
            "watch_time" -> items.sortedBy { prefs.getLong("v_" + it.id, 0L) }
            "status" -> items.sortedBy { if (prefs.contains("v_" + it.id)) 0 else 1 }
            else -> items
        }
        return if (sortAsc) sorted else sorted.reversed()
    }

    private fun sortedSongs(items: List<LocalSong>): List<LocalSong> {
        val sorted = when (sortKey) {
            "title" -> items.sortedBy { it.title.lowercase() }
            "size" -> items.sortedBy { it.size }
            "duration" -> items.sortedBy { it.durationMs }
            else -> items
        }
        return if (sortAsc) sorted else sorted.reversed()
    }

    private fun showList() {
        (placeholder.parent as View).visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    private fun showMessage(text: String, showGrant: Boolean) {
        list.visibility = View.GONE
        (placeholder.parent as View).visibility = View.VISIBLE
        placeholder.text = text
        grant.visibility = if (showGrant) View.VISIBLE else View.GONE
    }

    companion object {
        private const val ARG_TAB = "tab"
        fun newInstance(tab: Int) = LibraryPageFragment().apply { arguments = Bundle().apply { putInt(ARG_TAB, tab) } }
    }
}
