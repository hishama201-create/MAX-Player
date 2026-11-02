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
    private var currentVideos: List<LocalVideo> = emptyList()
    private var currentSongs: List<LocalSong> = emptyList()
    private var lastGranted = false
    private var isGrid = false
    private lateinit var optionsBtn: View

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
        isGrid = requireContext().getSharedPreferences("library_prefs", android.content.Context.MODE_PRIVATE).getBoolean("grid_" + tab, false)
        applyLayout()
        grant.setOnClickListener { permissionLauncher.launch(permissionFor()) }
        refresh()
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
        paint()
        dialog.setContentView(sheet)
        dialog.show()
    }

    private fun applyLayout() {
        val ctx = requireContext()
        list.layoutManager = if (isGrid) GridLayoutManager(ctx, 2) else LinearLayoutManager(ctx)
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
                list.adapter = videoAdapter
                showList()
                currentVideos = videos; videoAdapter.submitList(videos)
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
                currentSongs = songs; musicAdapter.submitList(songs)
            }
        }
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
