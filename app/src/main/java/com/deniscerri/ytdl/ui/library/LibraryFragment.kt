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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var placeholder: TextView
    private lateinit var grant: Button
    private lateinit var videoAdapter: LibraryVideoAdapter
    private lateinit var musicAdapter: LibraryMusicAdapter

    private fun permissionFor(tab: Int): String {
        if (Build.VERSION.SDK_INT < 33) return Manifest.permission.READ_EXTERNAL_STORAGE
        return if (tab == 0) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        if (view == null) return@registerForActivityResult
        refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabs = view.findViewById(R.id.library_tabs)
        list = view.findViewById(R.id.library_list)
        placeholder = view.findViewById(R.id.library_placeholder)
        grant = view.findViewById(R.id.library_grant)
        videoAdapter = LibraryVideoAdapter(viewLifecycleOwner.lifecycleScope) { video ->
            Toast.makeText(requireContext(), video.title, Toast.LENGTH_SHORT).show()
        }
        musicAdapter = LibraryMusicAdapter(viewLifecycleOwner.lifecycleScope) { song ->
            Toast.makeText(requireContext(), song.title, Toast.LENGTH_SHORT).show()
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        grant.setOnClickListener { permissionLauncher.launch(permissionFor(tabs.selectedTabPosition)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { refresh() }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        refresh()
    }

    private fun hasPermission(tab: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return ContextCompat.checkSelfPermission(requireContext(), permissionFor(tab)) == PackageManager.PERMISSION_GRANTED
    }

    private fun refresh() {
        val tab = tabs.selectedTabPosition
        if (!hasPermission(tab)) {
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
            if (tabs.selectedTabPosition != 0) return@launch
            if (videos.isEmpty()) {
                showMessage(getString(R.string.library_no_videos), false)
            } else {
                list.adapter = videoAdapter
                showList()
                videoAdapter.submitList(videos)
            }
        }
    }

    private fun loadSongs() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = LocalMusicRepository.loadSongs(ctx)
            if (tabs.selectedTabPosition != 1) return@launch
            if (songs.isEmpty()) {
                showMessage(getString(R.string.library_no_music), false)
            } else {
                list.adapter = musicAdapter
                showList()
                musicAdapter.submitList(songs)
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
}
