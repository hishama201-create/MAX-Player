package com.deniscerri.ytdl.ui.shorts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.ResultItem

class ShortsAdapter(
    private val onLike: (ResultItem) -> Unit,
    private val onShare: (ResultItem) -> Unit,
    private val onDownload: (ResultItem) -> Unit
) : ListAdapter<ResultItem, ShortsAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val player: PlayerView = itemView.findViewById(R.id.short_player)
        val like: ImageView = itemView.findViewById(R.id.short_like)
        val share: ImageView = itemView.findViewById(R.id.short_share)
        val download: ImageView = itemView.findViewById(R.id.short_download)
        val author: TextView = itemView.findViewById(R.id.short_author)
        val title: TextView = itemView.findViewById(R.id.short_title)
        val progress: ProgressBar = itemView.findViewById(R.id.short_progress)
    }

    var likedUrls: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_short, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.author.text = item.author
        holder.title.text = item.title
        holder.like.setImageResource(if (likedUrls.contains(item.url)) R.drawable.ic_watch_fav_on else R.drawable.ic_watch_fav_off)
        holder.like.setOnClickListener { onLike(item) }
        holder.share.setOnClickListener { onShare(item) }
        holder.download.setOnClickListener { onDownload(item) }
    }

    class DiffCallback : DiffUtil.ItemCallback<ResultItem>() {
        override fun areItemsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean = oldItem.url == newItem.url

        override fun areContentsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean = oldItem == newItem
    }
}
