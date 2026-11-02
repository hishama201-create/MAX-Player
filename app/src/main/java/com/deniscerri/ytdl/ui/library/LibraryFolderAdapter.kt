package com.deniscerri.ytdl.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R

data class LibraryFolder(val path: String, val name: String, val count: Int)

class LibraryFolderAdapter(
    private val onItemClick: (LibraryFolder) -> Unit
) : ListAdapter<LibraryFolder, LibraryFolderAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.lib_folder_name)
        val count: TextView = itemView.findViewById(R.id.lib_folder_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        holder.count.text = item.count.toString()
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    class DiffCallback : DiffUtil.ItemCallback<LibraryFolder>() {
        override fun areItemsTheSame(oldItem: LibraryFolder, newItem: LibraryFolder): Boolean = oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: LibraryFolder, newItem: LibraryFolder): Boolean = oldItem == newItem
    }
}
