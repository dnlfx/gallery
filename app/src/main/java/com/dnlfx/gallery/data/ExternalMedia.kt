package com.dnlfx.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** Stands in for a library id on items opened from another app, which aren't in the library. */
const val EXTERNAL_ITEM_ID = -1L

/** The library id behind a MediaStore link such as content://media/external/images/media/42. */
fun mediaStoreId(uri: Uri): Long? {
    if (uri.authority != MediaStore.AUTHORITY) return null
    return runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 }
}

/**
 * Describes a photo or video another app handed over (Files, Messages, a download), reading
 * whatever the providing app reports about it. Anything it doesn't report is left blank.
 */
fun externalMediaItem(resolver: ContentResolver, uri: Uri, mimeTypeHint: String?): MediaItem? {
    val mimeType = mimeTypeHint?.takeIf { it.contains('/') && !it.endsWith("/*") }
        ?: runCatching { resolver.getType(uri) }.getOrNull()
        ?: mimeTypeHint
    val type = when {
        mimeType?.startsWith("video/") == true -> MediaType.VIDEO
        mimeType?.startsWith("image/") == true -> MediaType.IMAGE
        else -> return null
    }
    var item = MediaItem(
        id = EXTERNAL_ITEM_ID,
        uri = uri,
        type = type,
        mimeType = mimeType,
        displayName = uri.lastPathSegment,
        dateModifiedSeconds = 0L,
        dateTakenMillis = null,
        durationMillis = null,
        width = 0,
        height = 0,
        orientationDegrees = 0,
        sizeBytes = 0L,
    )
    // Some providers refuse a query, or one without a column list; the item still opens.
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) item = cursor.describe(item)
        }
    }
    return item
}

private fun Cursor.describe(item: MediaItem): MediaItem {
    fun long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
    fun string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    val modifiedSeconds = long(MediaStore.MediaColumns.DATE_MODIFIED)
        // Documents providers report milliseconds under their own column name.
        ?: long("last_modified")?.div(1000)
    return item.copy(
        displayName = string(OpenableColumns.DISPLAY_NAME) ?: item.displayName,
        sizeBytes = long(OpenableColumns.SIZE) ?: item.sizeBytes,
        dateModifiedSeconds = modifiedSeconds?.takeIf { it > 0 } ?: item.dateModifiedSeconds,
        dateTakenMillis = long(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 },
        durationMillis = if (item.type == MediaType.VIDEO) long(MediaStore.MediaColumns.DURATION) else null,
        width = long(MediaStore.MediaColumns.WIDTH)?.toInt() ?: 0,
        height = long(MediaStore.MediaColumns.HEIGHT)?.toInt() ?: 0,
        orientationDegrees = (((long(MediaStore.MediaColumns.ORIENTATION) ?: 0L) % 360 + 360) % 360).toInt(),
        relativePath = string(MediaStore.MediaColumns.RELATIVE_PATH),
    )
}
