package com.deniscerri.ytdl.ui.tiktok

import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.extractors.TikTokPhotoPost
import com.deniscerri.ytdl.util.extractors.TikTokPhotoUtil
import com.squareup.picasso.Picasso
import java.io.File
import java.net.URL

class TikTokPhotosActivity : BaseActivity() {
    private var post: TikTokPhotoPost? = null
    private lateinit var selected: BooleanArray
    private lateinit var grid: RecyclerView
    private lateinit var downloadBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tiktok_photos)
        grid = findViewById(R.id.photos_grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        downloadBtn = findViewById(R.id.photos_download)
        val url = intent.getStringExtra("url") ?: return finish()
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { TikTokPhotoUtil.fetch(url) }.getOrNull() }
            if (p == null || p.images.isEmpty()) { Toast.makeText(this@TikTokPhotosActivity, R.string.tt_no_photos, Toast.LENGTH_LONG).show(); finish(); return@launch }
            show(p)
        }
        downloadBtn.setOnClickListener { startBg(false) }
        findViewById<Button>(R.id.photos_slideshow).setOnClickListener { startBg(true) }
    }

    private fun show(p: TikTokPhotoPost) {
        post = p
        selected = BooleanArray(p.images.size) { true }
        findViewById<TextView>(R.id.photos_title).text = "@" + p.author + " - " + p.title
        grid.adapter = PhotosAdapter(p.images)
        updateDownloadText()
        val toggle = findViewById<Button>(R.id.photos_toggle)
        toggle.setOnClickListener {
            val all = selected.all { it }
            selected.fill(!all)
            toggle.setText(if (all) R.string.tt_select_all else R.string.tt_deselect_all)
            grid.adapter?.notifyDataSetChanged(); updateDownloadText()
        }
    }

    private fun updateDownloadText() {
        val n = selected.count { it }
        downloadBtn.text = getString(R.string.tt_download_images, n)
        downloadBtn.isEnabled = n > 0
    }

    inner class PhotosAdapter(private val urls: List<String>) : RecyclerView.Adapter<PhotosAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.photo_img)
            val check: CheckBox = v.findViewById(R.id.photo_check)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tiktok_photo, parent, false))
        override fun getItemCount() = urls.size
        private fun loadImg(h: VH, i: Int, n: Int) {
            Picasso.get().load(urls[i]).into(h.img, object : com.squareup.picasso.Callback {
                override fun onSuccess() {}
                override fun onError(e: Exception?) { if (n < 3 && h.img.tag == i) h.img.postDelayed({ if (h.img.tag == i) loadImg(h, i, n + 1) }, 1500) }
            })
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            h.img.tag = i; loadImg(h, i, 0)
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = selected[i]
            h.check.setOnCheckedChangeListener { _, c -> selected[i] = c; updateDownloadText() }
            h.img.setOnClickListener { h.check.isChecked = !h.check.isChecked }
        }
    }

    private fun bytes(u: String): ByteArray {
        val c = URL(u).openConnection()
        c.connectTimeout = 30000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.getInputStream().use { it.readBytes() }
    }

    private fun startBg(video: Boolean) {
        if (post == null) return
        val i = android.content.Intent(this, com.deniscerri.ytdl.services.TikTokDownloadService::class.java)
        i.putExtra("url", intent.getStringExtra("url")).putExtra("sel", selected).putExtra("video", video)
        androidx.core.content.ContextCompat.startForegroundService(this, i)
        startActivity(android.content.Intent(this, com.deniscerri.ytdl.MainActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).putExtra("destination", "Queue"))
        finish()
    }

    private fun downloadSelected() {
        val p = post ?: return
        val dir = File(FileUtil.getDefaultApplicationPath(), "Photos"); dir.mkdirs()
        downloadBtn.isEnabled = false
        lifecycleScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) { p.images.forEachIndexed { i, u -> if (selected[i]) runCatching { File(dir, p.author + "_" + System.currentTimeMillis() + "_" + (i + 1) + ".jpg").writeBytes(bytes(u)); ok++ } } }
            android.media.MediaScannerConnection.scanFile(this@TikTokPhotosActivity, dir.listFiles()?.map { it.absolutePath }?.toTypedArray() ?: arrayOf(), null, null)
            Toast.makeText(this@TikTokPhotosActivity, "$ok -> " + dir.absolutePath, Toast.LENGTH_LONG).show()
            updateDownloadText()
        }
    }

    private fun makeSlideshow() {
        val p = post ?: return
        val sel = p.images.filterIndexed { i, _ -> selected[i] }
        if (sel.isEmpty()) return
        val btn = findViewById<Button>(R.id.photos_slideshow); btn.isEnabled = false
        val work = File(cacheDir, "tt_slide"); work.deleteRecursively(); work.mkdirs()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching {
                sel.forEachIndexed { i, u -> File(work, (i + 1).toString() + ".jpg").writeBytes(bytes(u)) }
                val fresh = TikTokPhotoUtil.fetch(intent.getStringExtra("url")!!)
                val audio = File(work, "a.mp3"); if (fresh != null && fresh.audioUrl.isNotEmpty()) runCatching { audio.writeBytes(bytes(fresh.audioUrl)) }
                val rm = com.deniscerri.ytdl.core.RuntimeManager.getInstance(); rm.assertInit()
                val ff = rm.ffmpegLocation; if (!ff.isAvailable) error("no ffmpeg")
                val outDir = File(FileUtil.getDefaultApplicationPath(), "Photos"); outDir.mkdirs()
                val out = File(outDir, p.author + "_" + System.currentTimeMillis() + ".mp4")
                val hasAudio = File(work, "a.mp3").let { it.exists() && it.length() > 0 }
                val cmd = mutableListOf(ff.executable.absolutePath, "-y", "-framerate", "1/3", "-i", File(work, "%d.jpg").absolutePath)
                if (hasAudio) { cmd.add("-i"); cmd.add(File(work, "a.mp3").absolutePath) }
                cmd.addAll(listOf("-vf", "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:-1:-1,format=yuv420p", "-r", "30", "-c:v", "libx264"))
                if (hasAudio) cmd.addAll(listOf("-c:a", "aac", "-shortest"))
                cmd.add(out.absolutePath)
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                rm.getEnvironmentForTerminal().forEach { (k, v) -> pb.environment()[k] = v }
                val proc = pb.start(); File(FileUtil.getDefaultApplicationPath(), "e.txt").writeText(proc.inputStream.bufferedReader().readText())
                proc.waitFor() == 0 && out.exists() && out.length() > 0
            }.onFailure { File(FileUtil.getDefaultApplicationPath(), "e.txt").writeText("$it") }.getOrDefault(false) }
            work.deleteRecursively(); btn.isEnabled = true
            android.media.MediaScannerConnection.scanFile(this@TikTokPhotosActivity, File(FileUtil.getDefaultApplicationPath(), "Photos").listFiles()?.map { it.absolutePath }?.toTypedArray() ?: arrayOf(), null, null)
            Toast.makeText(this@TikTokPhotosActivity, if (ok) R.string.tt_video_saved else R.string.tt_video_failed, Toast.LENGTH_LONG).show()
        }
    }
}
