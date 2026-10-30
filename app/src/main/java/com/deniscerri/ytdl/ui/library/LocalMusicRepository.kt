package com.deniscerri.ytdl.ui.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalSong(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val albumId: Long,
    val durationMs: Long,
    val size: Long
)

object LocalMusicRepository {

    suspend fun loadSongs(context: Context): List<LocalSong> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val result = mutableListOf<LocalSong>()
        context.contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: ""
                result.add(LocalSong(id, ContentUris.withAppendedId(collection, id), c.getString(titleCol) ?: "", artist, c.getLong(albumCol), c.getLong(durCol), c.getLong(sizeCol)))
            }
        }
        result
    }

    suspend fun loadArt(context: Context, song: LocalSong): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(song.uri, Size(256, 256), null)
            } else {
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), song.albumId)
                context.contentResolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
