package com.deniscerri.ytdl.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.media.MediaScannerConnection
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.extractors.TikTokPhotoUtil
import java.io.File
import java.net.URL

class TikTokDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val url = i?.getStringExtra("url")
        if (i == null || url == null) { stopSelf(); return START_NOT_STICKY }
        val sel = i.getBooleanArrayExtra("sel") ?: BooleanArray(0)
        val video = i.getBooleanExtra("video", false)
        createChannel()
        startFg(notif(getString(R.string.tt_downloading), false))
        scope.launch { work(url, sel, video); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notif(t: String, busy: Boolean): Notification {
        return NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_music)
            .setContentTitle(t).setOngoing(busy).setProgress(0, 0, busy).build()
    }
    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(77, n)
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "TikTok download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun bytes(u: String): ByteArray {
        val c = URL(u).openConnection()
        c.connectTimeout = 30000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.getInputStream().use { it.readBytes() }
    }

    private fun scan(d: File) {
        MediaScannerConnection.scanFile(this, d.listFiles()?.map { it.absolutePath }?.toTypedArray() ?: arrayOf(), null, null)
    }

    private fun done(r: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(78, notif(getString(r), false))
    }

    private fun work(url: String, sel: BooleanArray, video: Boolean) {
        val p = runCatching { TikTokPhotoUtil.fetch(url) }.getOrNull()
        if (p == null) { done(R.string.tt_video_failed); return }
        val dir = File(FileUtil.getDefaultApplicationPath(), "Photos"); dir.mkdirs()
        val idx = p.images.indices.filter { it < sel.size && sel[it] }
        if (!video) {
            var ok = 0
            idx.forEach { i -> runCatching { File(dir, p.author + "_" + System.currentTimeMillis() + "_" + (i + 1) + ".jpg").writeBytes(bytes(p.images[i])); ok++ } }
            scan(dir); done(if (ok > 0) R.string.tt_images_saved else R.string.tt_video_failed); return }
        val w = File(cacheDir, "tt_slide"); w.deleteRecursively(); w.mkdirs()
        val ok = runCatching {
            idx.forEachIndexed { n, i -> File(w, (n + 1).toString() + ".jpg").writeBytes(bytes(p.images[i])) }
            if (p.audioUrl.isNotEmpty()) runCatching { File(w, "a.mp3").writeBytes(bytes(p.audioUrl)) }
            val rm = com.deniscerri.ytdl.core.RuntimeManager.getInstance(); rm.assertInit()
            val ff = rm.ffmpegLocation; if (!ff.isAvailable) error("no ffmpeg")
            val out = File(dir, p.author + "_" + System.currentTimeMillis() + ".mp4")
            val au = File(w, "a.mp3"); val has = au.exists() && au.length() > 0
            val cmd = mutableListOf(ff.executable.absolutePath, "-y", "-framerate", "1/3", "-i", File(w, "%d.jpg").absolutePath)
            if (has) { cmd.add("-i"); cmd.add(au.absolutePath) }
            val vf = "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:-1:-1,format=yuv420p"
            cmd.addAll(listOf("-vf", vf, "-r", "30", "-c:v", "libx264"))
            if (has) cmd.addAll(listOf("-c:a", "aac", "-shortest"))
            cmd.add(out.absolutePath)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            rm.getEnvironmentForTerminal().forEach { (k, v) -> pb.environment()[k] = v }
            val pr = pb.start(); pr.inputStream.bufferedReader().readText()
            pr.waitFor() == 0 && out.exists() && out.length() > 0
        }.getOrDefault(false)
        w.deleteRecursively(); scan(dir)
        done(if (ok) R.string.tt_video_saved else R.string.tt_video_failed)
    }

    companion object { private const val CH = "tt_download" }
}
