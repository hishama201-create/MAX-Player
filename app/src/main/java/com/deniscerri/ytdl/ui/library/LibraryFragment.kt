package com.deniscerri.ytdl.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class LibraryFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = view.findViewById<TabLayout>(R.id.library_tabs)
        val pager = view.findViewById<ViewPager2>(R.id.library_pager)
        pager.adapter = LibraryPagerAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)
        pager.isSaveFromParentEnabled = false
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = getString(if (position == 0) R.string.video else R.string.music)
        }.attach()
    }
}
