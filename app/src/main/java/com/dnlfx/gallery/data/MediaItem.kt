package com.dnlfx.gallery.data

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

/** One photo or video from MediaStore. Lightweight so the whole library fits in memory. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val mimeType: String?,
    val displayName: String?,
    /** Seconds since the epoch, as MediaStore stores it. */
    val dateModifiedSeconds: Long,
    val dateTakenMillis: Long?,
    val durationMillis: Long?,
    val width: Int,
    val height: Int,
    /** Clockwise rotation (0, 90, 180 or 270) the stored pixels need to display upright. */
    val orientationDegrees: Int,
    val sizeBytes: Long,
    /** Folder on shared storage, like "DCIM/Camera/", when known. */
    val relativePath: String? = null,
    /** Starred in Photos, Files or another app. Always false before Android 11. */
    val isFavorite: Boolean = false,
)
