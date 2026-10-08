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
    val sizeBytes: Long,
)
