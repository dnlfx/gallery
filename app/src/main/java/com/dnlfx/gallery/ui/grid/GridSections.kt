package com.dnlfx.gallery.ui.grid

import java.util.Calendar
import java.util.TimeZone

/** A row of the grid: a month header spanning the width, or one photo or video. */
sealed interface GridEntry {
    /** [month] counts months since year 0 (year * 12 + month), [millis] is any moment in it. */
    data class Header(val month: Int, val millis: Long) : GridEntry

    data class Media(val mediaIndex: Int) : GridEntry
}

/** The library split into months, newest first, as the grid lays it out. */
class GridSections(val entries: List<GridEntry>, private val mediaToEntry: IntArray) {
    /** Where the item at [mediaIndex] in the library list sits among [entries]. */
    fun entryIndexOf(mediaIndex: Int): Int = mediaToEntry.getOrElse(mediaIndex) { -1 }

    companion object {
        val Empty = GridSections(emptyList(), IntArray(0))
    }
}

/**
 * Puts a month header before each run of items from the same month. [modifiedSeconds] are the
 * items' modified times in library order, the same dates the library is sorted by.
 */
fun buildGridSections(modifiedSeconds: List<Long>, zone: TimeZone = TimeZone.getDefault()): GridSections {
    val calendar = Calendar.getInstance(zone)
    val entries = ArrayList<GridEntry>(modifiedSeconds.size + modifiedSeconds.size / 20 + 1)
    val mediaToEntry = IntArray(modifiedSeconds.size)
    var currentMonth = Int.MIN_VALUE
    modifiedSeconds.forEachIndexed { index, seconds ->
        val millis = seconds * 1000
        calendar.timeInMillis = millis
        val month = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)
        if (month != currentMonth) {
            currentMonth = month
            entries += GridEntry.Header(month, millis)
        }
        mediaToEntry[index] = entries.size
        entries += GridEntry.Media(index)
    }
    return GridSections(entries, mediaToEntry)
}

/** The items in order with no month headers, for sorts that aren't by date. */
fun flatGridSections(count: Int): GridSections =
    GridSections(List(count) { GridEntry.Media(it) }, IntArray(count) { it })
