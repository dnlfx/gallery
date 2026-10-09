package com.dnlfx.gallery.ui.grid

import androidx.annotation.StringRes
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import java.util.EnumSet

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

    // Runs for every item in the library on each refresh, so it compares in place rather than
    // building lowercased copies of each path and name.
    fun matches(type: MediaType, relativePath: String?, displayName: String?, isFavorite: Boolean): Boolean {
        val path = relativePath.orEmpty()
        return when (this) {
            ALL -> true
            PHOTOS -> type == MediaType.IMAGE
            VIDEOS -> type == MediaType.VIDEO
            CAMERA -> path.startsWith("dcim/camera/", ignoreCase = true)
            // Pictures/Screenshots/ on a Pixel; DCIM/Screenshots/ on some other phones.
            SCREENSHOTS -> path.hasFolder("screenshots")
            SCREEN_RECORDINGS -> type == MediaType.VIDEO && isScreenRecording(path, displayName.orEmpty())
            DOWNLOADS -> path.startsWith("download/", ignoreCase = true)
            FAVORITES -> isFavorite
        }
    }
}

/** The filters with at least one item, in chip order. [selected] stays so it can be turned off. */
fun availableFilters(items: List<MediaItem>, selected: MediaFilter): List<MediaFilter> =
    chipFilters(nonEmptyFilters(items), selected)

/** The filters that match at least one of [items]. Worked out once per library read. */
fun nonEmptyFilters(items: List<MediaItem>): Set<MediaFilter> =
    MediaFilter.entries.filterTo(EnumSet.noneOf(MediaFilter::class.java)) { filter ->
        filter == MediaFilter.ALL || items.any(filter::matches)
    }

/** The chips to show: All, the [nonEmpty] filters, and [selected] so it can be turned off. */
fun chipFilters(nonEmpty: Set<MediaFilter>, selected: MediaFilter): List<MediaFilter> =
    MediaFilter.entries.filter { it == MediaFilter.ALL || it == selected || it in nonEmpty }

// The Pixel screen recorder saves to Movies/ (newer builds use a "Screen recordings" folder in it).
private fun isScreenRecording(path: String, name: String): Boolean =
    path.hasFolder("screen recordings") || path.hasFolder("screenrecords") ||
        (path.startsWith("movies/", ignoreCase = true) && isScreenRecordingName(name))

private fun isScreenRecordingName(name: String): Boolean =
    name.startsWith("screen_recording", ignoreCase = true) || name.startsWith("screen-", ignoreCase = true)

/** True when one of the slash-separated parts of this path is [folder], ignoring case. */
private fun String.hasFolder(folder: String): Boolean {
    var start = 0
    while (start < length) {
        val end = indexOf('/', start).let { if (it < 0) length else it }
        if (end - start == folder.length && regionMatches(start, folder, 0, folder.length, ignoreCase = true)) {
            return true
        }
        start = end + 1
    }
    return false
}
