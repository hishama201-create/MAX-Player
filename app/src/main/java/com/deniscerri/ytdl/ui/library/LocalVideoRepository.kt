package com.deniscerri.ytdl.ui.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalVideo(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long = 0L,
    val mimeType: String = "",
    val path: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f
)

object LocalVideoRepository {

    suspend fun loadVideos(context: Context): List<LocalVideo> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        )
        val fullProjection = projection + arrayOf(MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT) + (if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.Video.Media.CAPTURE_FRAMERATE) else emptyArray<String>())
        val result = mutableListOf<LocalVideo>()
        context.contentResolver.query(collection, fullProjection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val pathCol = c.getColumnIndexOrThrow(if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val frameCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Video.Media.CAPTURE_FRAMERATE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result.add(LocalVideo(id, ContentUris.withAppendedId(collection, id), c.getString(nameCol) ?: "", c.getLong(durCol), c.getLong(sizeCol), c.getLong(dateCol), c.getString(mimeCol) ?: "", (c.getString(pathCol) ?: "").let { if (Build.VERSION.SDK_INT >= 29) it else it.substringBeforeLast('/', "") + "/" }, c.getInt(widthCol), c.getInt(heightCol), if (frameCol >= 0 && !c.isNull(frameCol)) c.getFloat(frameCol) else 0f))
            }
        }
        result
    }

    @Suppress("DEPRECATION")
    suspend fun loadThumbnail(context: Context, video: LocalVideo): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            }
        } catch (e: Exception) {
            null
        }
    }
}
