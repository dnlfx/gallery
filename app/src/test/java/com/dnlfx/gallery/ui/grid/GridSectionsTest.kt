package com.dnlfx.gallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class GridSectionsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-05, 2026-10-01, 2026-09-30 23:59:59, 2025-09-15 (UTC).
    private val oct5 = 1_791_158_400L
    private val oct1 = 1_790_812_800L
    private val sep30 = 1_790_812_799L
    private val sep2025 = 1_757_894_400L

    @Test
    fun headerBeforeEachMonth() {
        val sections = buildGridSections(listOf(oct5, oct1, sep30, sep2025), utc)
        assertEquals(
            listOf(
                GridEntry.Header(2026 * 12 + 9, oct5 * 1000),
                GridEntry.Media(0),
                GridEntry.Media(1),
                GridEntry.Header(2026 * 12 + 8, sep30 * 1000),
                GridEntry.Media(2),
                GridEntry.Header(2025 * 12 + 8, sep2025 * 1000),
                GridEntry.Media(3),
            ),
            sections.entries,
        )
        assertEquals(1, sections.entryIndexOf(0))
        assertEquals(4, sections.entryIndexOf(2))
        assertEquals(6, sections.entryIndexOf(3))
        assertEquals(-1, sections.entryIndexOf(4))
    }

    @Test
    fun monthsFollowTheTimeZone() {
        // 23:59:59 UTC on 30 September is already October an hour east.
        val sections = buildGridSections(listOf(oct1, sep30), TimeZone.getTimeZone("GMT+01:00"))
        assertEquals(3, sections.entries.size)
    }

    @Test
    fun emptyLibrary() {
        assertEquals(0, buildGridSections(emptyList(), utc).entries.size)
    }

    @Test
    fun flatSectionsHaveNoHeaders() {
        val sections = flatGridSections(3)
        assertEquals(listOf(GridEntry.Media(0), GridEntry.Media(1), GridEntry.Media(2)), sections.entries)
        assertEquals(2, sections.entryIndexOf(2))
    }
}
