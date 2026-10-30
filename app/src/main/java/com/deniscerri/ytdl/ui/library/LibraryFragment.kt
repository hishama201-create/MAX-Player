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
    private lateinit var adapter: LibraryVideoAdapter

    private val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (view == null) return@registerForActivityResult
        if (granted) loadVideos() else showMessage(getString(R.string.library_permission_needed), true)
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
        adapter = LibraryVideoAdapter(viewLifecycleOwner.lifecycleScope) { video ->
            Toast.makeText(requireContext(), video.title, Toast.LENGTH_SHORT).show()
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        grant.setOnClickListener { permissionLauncher.launch(permission) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { refresh() }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        refresh()
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun refresh() {
        if (tabs.selectedTabPosition != 0) {
            showMessage(getString(R.string.library_placeholder), false)
        } else if (hasPermission()) {
            loadVideos()
        } else {
            showMessage(getString(R.string.library_permission_needed), true)
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
                (placeholder.parent as View).visibility = View.GONE
                list.visibility = View.VISIBLE
                adapter.submitList(videos)
            }
        }
    }

    private fun showMessage(text: String, showGrant: Boolean) {
        list.visibility = View.GONE
        (placeholder.parent as View).visibility = View.VISIBLE
        placeholder.text = text
        grant.visibility = if (showGrant) View.VISIBLE else View.GONE
    }
}
