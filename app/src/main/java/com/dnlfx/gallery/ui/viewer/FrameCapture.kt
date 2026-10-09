package com.dnlfx.gallery.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import com.dnlfx.gallery.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Saves the frame of [item] at [positionMillis] as a JPEG in Pictures/Gallery, where it shows up
 * in the grid like any other photo. The frame comes straight from the video file at its full
 * resolution, upright, so nothing on screen (controls, system bars, zoom) ends up in it.
 * Returns false if the frame couldn't be read or saved.
 */
suspend fun saveVideoFrame(context: Context, item: MediaItem, positionMillis: Long): Boolean =
    withContext(Dispatchers.IO) {
        val frame = readFrame(context, item, positionMillis) ?: return@withContext false
        try {
            writeToPictures(context, frameFileName(item.displayName, positionMillis), frame)
        } finally {
            frame.recycle()
        }
    }

private fun readFrame(context: Context, item: MediaItem, positionMillis: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, item.uri)
        // The exact frame, not the nearest keyframe, so it matches what was paused on.
        retriever.getFrameAtTime(positionMillis * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
    } catch (e: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}

private fun writeToPictures(context: Context, name: String, frame: Bitmap): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Gallery")
        // Hidden from other apps and the grid until the file is complete.
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    // Storage that's full or not ready refuses the new file; say so rather than closing the app.
    val uri = try {
        resolver.insert(collection, values)
    } catch (e: Exception) {
        null
    } ?: return false
    val written = try {
        resolver.openOutputStream(uri)?.use { frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } == true
    } catch (e: Exception) {
        false
    }
    if (!written) {
        resolver.delete(uri, null, null)
        return false
    }
    return try {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** "PXL_20261008_101500123_frame_01-23-456.jpg" for a frame at 1:23.456. */
internal fun frameFileName(videoName: String?, positionMillis: Long): String {
    val base = videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "video"
    val totalSeconds = positionMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    val millis = positionMillis % 1000
    val time = if (hours > 0) {
        String.format(Locale.ROOT, "%d-%02d-%02d-%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.ROOT, "%02d-%02d-%03d", minutes, seconds, millis)
    }
    return "${base}_frame_$time.jpg"
}

private const val JPEG_QUALITY = 95
