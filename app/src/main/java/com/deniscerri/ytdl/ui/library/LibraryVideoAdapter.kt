package com.deniscerri.ytdl.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.FileUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class LibraryVideoAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (LocalVideo) -> Unit
) : ListAdapter<LocalVideo, LibraryVideoAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.lib_video_thumb)
        val title: TextView = itemView.findViewById(R.id.lib_video_title)
        val size: TextView = itemView.findViewById(R.id.lib_video_size)
        val duration: TextView = itemView.findViewById(R.id.lib_video_duration)
        var thumbJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.size.text = FileUtil.convertFileSize(item.size)
        holder.duration.text = (item.durationMs / 1000).toInt().toStringDuration(Locale.US)
        holder.thumb.setImageDrawable(null)
        holder.thumbJob?.cancel()
        holder.thumbJob = scope.launch {
            val bmp = LocalVideoRepository.loadThumbnail(holder.itemView.context, item)
            if (bmp != null) holder.thumb.setImageBitmap(bmp)
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbJob?.cancel()
        holder.thumbJob = null
        super.onViewRecycled(holder)
    }

    class DiffCallback : DiffUtil.ItemCallback<LocalVideo>() {
        override fun areItemsTheSame(oldItem: LocalVideo, newItem: LocalVideo): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LocalVideo, newItem: LocalVideo): Boolean = oldItem == newItem
    }
}
