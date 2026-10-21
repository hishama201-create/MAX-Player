package com.deniscerri.ytdl.ui.watch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.util.Extensions.loadThumbnail

class WatchSuggestionsAdapter(
    private val onItemClick: (ResultItem) -> Unit
) : ListAdapter<ResultItem, WatchSuggestionsAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.suggestion_thumb)
        val title: TextView = itemView.findViewById(R.id.suggestion_title)
        val author: TextView = itemView.findViewById(R.id.suggestion_author)
        val duration: TextView = itemView.findViewById(R.id.suggestion_duration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watch_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.thumb.loadThumbnail(false, item.thumb)
        holder.title.text = item.title.ifBlank { item.url }
        holder.author.text = item.author
        if (item.duration.isNotEmpty() && item.duration != "-1") {
            holder.duration.text = item.duration
            holder.duration.visibility = View.VISIBLE
        } else {
            holder.duration.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    class DiffCallback : DiffUtil.ItemCallback<ResultItem>() {
        override fun areItemsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
            return oldItem == newItem
        }
    }
}
