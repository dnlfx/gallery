package com.dnlfx.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Reads every image and video on the device's shared storage as one flat list,
 * newest modified first. No grouping by folder or album.
 */
class MediaRepository(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val imagesUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videosUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Emits the full library now and again whenever MediaStore reports a change. A burst of
     * changes (a camera burst, a folder copied in) re-reads the library once, not once per file.
     */
    @OptIn(FlowPreview::class)
    fun observeMedia(): Flow<List<MediaItem>> =
        merge(flowOf(Unit), mediaStoreChanges().debounce(CHANGE_DEBOUNCE_MILLIS))
            .conflate()
            .map { queryAll() }
            .flowOn(Dispatchers.IO)

    private fun mediaStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(filesUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    fun queryAll(): List<MediaItem> {
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        ) + favoriteColumn()
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${BaseColumns._ID} DESC"

        val cursor = resolver.query(filesUri, projection, selection, selectionArgs, sortOrder)
            ?: return emptyList()
        return cursor.use { it.toMediaItems() }
    }

    private fun Cursor.toMediaItems(): List<MediaItem> {
        val idCol = getColumnIndexOrThrow(BaseColumns._ID)
        val typeCol = getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val mimeCol = getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val nameCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val modifiedCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val takenCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val durationCol = getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val widthCol = getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightCol = getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val orientationCol = getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        val sizeCol = getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val pathCol = getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val favoriteCol = favoriteColumn().firstOrNull()?.let(::getColumnIndex) ?: -1

        val items = ArrayList<MediaItem>(count)
        while (moveToNext()) {
            val id = getLong(idCol)
            val isVideo = getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(if (isVideo) videosUri else imagesUri, id),
                type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                mimeType = getStringOrNull(mimeCol),
                displayName = getStringOrNull(nameCol),
                dateModifiedSeconds = getLong(modifiedCol),
                dateTakenMillis = getLongOrNull(takenCol)?.takeIf { it > 0 },
                durationMillis = if (isVideo) getLongOrNull(durationCol) else null,
                width = getInt(widthCol),
                height = getInt(heightCol),
                orientationDegrees = ((getInt(orientationCol) % 360) + 360) % 360,
                sizeBytes = getLong(sizeCol),
                relativePath = getStringOrNull(pathCol),
                isFavorite = favoriteCol >= 0 && getInt(favoriteCol) == 1,
            )
        }
        return items
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 300L
    }

    /** The favorite flag MediaStore keeps from Android 11 on, or nothing before that. */
    private fun favoriteColumn(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.MediaColumns.IS_FAVORITE) else emptyArray()

    private fun Cursor.getStringOrNull(col: Int): String? = if (isNull(col)) null else getString(col)
    private fun Cursor.getLongOrNull(col: Int): Long? = if (isNull(col)) null else getLong(col)
}
