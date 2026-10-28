package com.deniscerri.ytdl.ui.watch

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.extractors.LrcLibUtil
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchLyricsSheet(
    private val activity: AppCompatActivity,
    private val player: ExoPlayer,
    private val title: String,
    private val author: String
) {
    private val adapter = LineAdapter { index -> seekTo(index) }
    private var lines: List<LrcLibUtil.LrcLine> = emptyList()

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(activity)
        root.minimumHeight = (420 * activity.resources.displayMetrics.density).toInt()
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        val message = TextView(activity)
        message.gravity = Gravity.CENTER
        message.visibility = View.GONE
        val progress = ProgressBar(activity)
        root.addView(list, FrameLayout.LayoutParams(match, match))
        root.addView(message, FrameLayout.LayoutParams(match, match))
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(root)
        val seconds = (player.duration / 1000).toInt().coerceAtLeast(0)
        val job = activity.lifecycleScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { LrcLibUtil.find(title, author, "", seconds) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            progress.visibility = View.GONE
            val synced = found?.synced ?: emptyList()
            if (found == null || (synced.isEmpty() && found.plain.isBlank())) {
                message.text = activity.getString(R.string.lyrics_not_found)
                message.visibility = View.VISIBLE
                list.visibility = View.GONE
                return@launch
            }
            lines = synced
            adapter.submit(if (synced.isNotEmpty()) synced.map { it.text } else found.plain.lines(), synced.isNotEmpty())
            while (isActive && synced.isNotEmpty()) {
                val idx = synced.indexOfLast { it.timeMs <= player.currentPosition }
                if (idx != adapter.current) {
                    adapter.setCurrent(idx)
                    if (idx >= 0) list.smoothScrollToPosition(idx)
                }
                delay(300)
            }
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
    }

    private fun seekTo(index: Int) {
        if (index !in lines.indices) return
        player.seekTo(maxOf(0L, lines[index].timeMs))
        player.play()
    }

    private class LineAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<LineAdapter.Holder>() {
        private var items: List<String> = emptyList()
        private var highlight = false
        var current = -1
            private set

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<String>, highlightCurrent: Boolean) {
            items = newItems
            highlight = highlightCurrent
            current = -1
            notifyDataSetChanged()
        }

        fun setCurrent(index: Int) {
            val old = current
            current = index
            if (old >= 0) notifyItemChanged(old)
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.lyrics_line_item, parent, false) as TextView
            return Holder(tv).also { h ->
                tv.setOnClickListener {
                    val pos = h.bindingAdapterPosition
                    if (highlight && pos != RecyclerView.NO_POSITION) onClick(pos)
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = items[position]
            holder.text.alpha = if (!highlight || position == current) 1f else 0.4f
        }

        override fun getItemCount() = items.size
    }
}
