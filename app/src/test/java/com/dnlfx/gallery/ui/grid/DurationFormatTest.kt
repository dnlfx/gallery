package com.dnlfx.gallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun formatsShortClips() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("0:08", formatDuration(7_600))
    }

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1_000L))
        assertEquals("1:02:03", formatDuration((3600 + 2 * 60 + 3) * 1_000L))
    }

    @Test
    fun clampsNegative() {
        assertEquals("0:00", formatDuration(-5))
    }
}
