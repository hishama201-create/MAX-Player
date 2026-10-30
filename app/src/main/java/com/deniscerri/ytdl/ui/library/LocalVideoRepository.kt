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
    val size: Long
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
            MediaStore.Video.Media.SIZE
        )
        val result = mutableListOf<LocalVideo>()
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result.add(LocalVideo(id, ContentUris.withAppendedId(collection, id), c.getString(nameCol) ?: "", c.getLong(durCol), c.getLong(sizeCol)))
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
