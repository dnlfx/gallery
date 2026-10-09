package com.dnlfx.gallery.ui.grid

import android.content.Context
import androidx.annotation.StringRes
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem

/** What the grid is ordered by. Dates read newest first, names A to Z, sizes largest first. */
enum class SortField(
    @StringRes val label: Int,
    @StringRes val naturalOrder: Int,
    @StringRes val reversedOrder: Int,
) {
    MODIFIED(R.string.sort_modified, R.string.sort_newest_first, R.string.sort_oldest_first),
    TAKEN(R.string.sort_taken, R.string.sort_newest_first, R.string.sort_oldest_first),
    NAME(R.string.sort_name, R.string.sort_a_to_z, R.string.sort_z_to_a),
    SIZE(R.string.sort_size, R.string.sort_largest_first, R.string.sort_smallest_first),
    ;

    /** Dates group the grid into months; names and sizes don't. */
    val hasMonths: Boolean get() = this == MODIFIED || this == TAKEN
}

/** [reversed] flips [field]'s natural order: oldest first, Z to A, or smallest first. */
data class MediaSort(val field: SortField = SortField.MODIFIED, val reversed: Boolean = false) {

    fun sorted(items: List<MediaItem>): List<MediaItem> {
        // The library arrives newest modified first, which is already the default.
        if (this == Default) return items
        val natural: Comparator<MediaItem> = when (field) {
            SortField.MODIFIED -> compareByDescending<MediaItem> { it.dateModifiedSeconds }
            SortField.TAKEN -> compareByDescending { it.takenOrModifiedMillis }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
            SortField.SIZE -> compareByDescending { it.sizeBytes }
        }
        // Ties keep a fixed order, newest added first, so items don't swap places on a refresh.
        val withTies = natural.thenByDescending { it.id }
        return items.sortedWith(if (reversed) withTies.reversed() else withTies)
    }

    /** The date a month header groups [item] under, in seconds, when this sort has months. */
    fun monthSeconds(item: MediaItem): Long = when (field) {
        SortField.TAKEN -> item.takenOrModifiedMillis / 1000
        else -> item.dateModifiedSeconds
    }

    companion object {
        val Default = MediaSort()

        private const val PREFS = "grid"
        private const val KEY_FIELD = "sort"
        private const val KEY_REVERSED = "sort_reversed"

        /** The sort last picked on this device. */
        fun load(context: Context): MediaSort {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val field = SortField.entries.firstOrNull { it.name == prefs.getString(KEY_FIELD, null) }
                ?: return Default
            return MediaSort(field, prefs.getBoolean(KEY_REVERSED, false))
        }

        fun save(context: Context, sort: MediaSort) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FIELD, sort.field.name)
                .putBoolean(KEY_REVERSED, sort.reversed)
                .apply()
        }
    }
}

/** When the photo was taken, or when it was last changed if the camera didn't say. */
private val MediaItem.takenOrModifiedMillis: Long
    get() = dateTakenMillis ?: (dateModifiedSeconds * 1000)
