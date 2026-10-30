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

class LibraryMusicAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (LocalSong) -> Unit
) : ListAdapter<LocalSong, LibraryMusicAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val art: ImageView = itemView.findViewById(R.id.lib_music_art)
        val title: TextView = itemView.findViewById(R.id.lib_music_title)
        val artist: TextView = itemView.findViewById(R.id.lib_music_artist)
        val size: TextView = itemView.findViewById(R.id.lib_music_size)
        val duration: TextView = itemView.findViewById(R.id.lib_music_duration)
        var artJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_music, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.artist.text = item.artist
        holder.artist.visibility = if (item.artist.isEmpty()) View.GONE else View.VISIBLE
        holder.size.text = FileUtil.convertFileSize(item.size)
        holder.duration.text = (item.durationMs / 1000).toInt().toStringDuration(Locale.US)
        holder.art.setImageResource(R.drawable.ic_music)
        val pad = (12 * holder.itemView.resources.displayMetrics.density).toInt(); holder.art.setPadding(pad, pad, pad, pad)
        holder.artJob?.cancel()
        holder.artJob = scope.launch {
            val bmp = LocalMusicRepository.loadArt(holder.itemView.context, item)
            if (bmp != null) { holder.art.setPadding(0, 0, 0, 0); holder.art.setImageBitmap(bmp) }
        }
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.artJob?.cancel()
        holder.artJob = null
        super.onViewRecycled(holder)
    }

    class DiffCallback : DiffUtil.ItemCallback<LocalSong>() {
        override fun areItemsTheSame(oldItem: LocalSong, newItem: LocalSong): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LocalSong, newItem: LocalSong): Boolean = oldItem == newItem
    }
}
