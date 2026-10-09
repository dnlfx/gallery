package com.dnlfx.gallery.ui.grid

import androidx.annotation.StringRes
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType

/**
 * Narrows the one library grid without splitting it into albums. Everything is worked out from
 * what MediaStore already reports: the type, the folder on shared storage and the file name.
 */
enum class MediaFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    PHOTOS(R.string.filter_photos),
    VIDEOS(R.string.filter_videos),
    CAMERA(R.string.filter_camera),
    SCREENSHOTS(R.string.filter_screenshots),
    SCREEN_RECORDINGS(R.string.filter_screen_recordings),
    DOWNLOADS(R.string.filter_downloads),
    FAVORITES(R.string.filter_favorites),
    ;

    fun matches(item: MediaItem): Boolean =
        matches(item.type, item.relativePath, item.displayName, item.isFavorite)

    fun matches(type: MediaType, relativePath: String?, displayName: String?, isFavorite: Boolean): Boolean {
        val path = relativePath.orEmpty().lowercase()
        return when (this) {
            ALL -> true
            PHOTOS -> type == MediaType.IMAGE
            VIDEOS -> type == MediaType.VIDEO
            CAMERA -> path.startsWith("dcim/camera/")
            // Pictures/Screenshots/ on a Pixel; DCIM/Screenshots/ on some other phones.
            SCREENSHOTS -> path.hasFolder("screenshots")
            SCREEN_RECORDINGS -> type == MediaType.VIDEO && isScreenRecording(path, displayName.orEmpty().lowercase())
            DOWNLOADS -> path.startsWith("download/")
            FAVORITES -> isFavorite
        }
    }
}

/** The filters with at least one item, in chip order. [selected] stays so it can be turned off. */
fun availableFilters(items: List<MediaItem>, selected: MediaFilter): List<MediaFilter> =
    MediaFilter.entries.filter { filter ->
        filter == MediaFilter.ALL || filter == selected || items.any(filter::matches)
    }

// The Pixel screen recorder saves to Movies/ (newer builds use a "Screen recordings" folder in it).
private fun isScreenRecording(path: String, name: String): Boolean =
    path.hasFolder("screen recordings") || path.hasFolder("screenrecords") ||
        (path.startsWith("movies/") && (name.startsWith("screen_recording") || name.startsWith("screen-")))

private fun String.hasFolder(folder: String): Boolean = split('/').any { it == folder }
