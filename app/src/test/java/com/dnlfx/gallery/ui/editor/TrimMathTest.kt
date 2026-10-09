package com.dnlfx.gallery.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimMathTest {
    @Test
    fun handlesKeepAMinimumLength() {
        val range = TrimRange(0L, 10_000L)
        assertEquals(TrimRange(9_500L, 10_000L), range.withStart(9_900L, 500L))
        assertEquals(TrimRange(0L, 500L), range.withEnd(100L, 10_000L, 500L))
        assertEquals(TrimRange(0L, 10_000L), range.withStart(-50L, 500L).withEnd(12_000L, 10_000L, 500L))
    }

    @Test
    fun wholeMeansNothingCut() {
        assertTrue(TrimRange(0L, 10_000L).isWhole(10_000L))
        assertFalse(TrimRange(1L, 10_000L).isWhole(10_000L))
        assertFalse(TrimRange(0L, 9_000L).isWhole(10_000L))
    }

    @Test
    fun theNearestHandleInReachIsGrabbed() {
        assertEquals(TrimHandle.Start, trimHandleAt(105f, startX = 100f, endX = 500f, reach = 30f))
        assertEquals(TrimHandle.End, trimHandleAt(520f, startX = 100f, endX = 500f, reach = 30f))
        assertNull(trimHandleAt(300f, startX = 100f, endX = 500f, reach = 30f))
    }

    @Test
    fun closeHandlesSplitBySide() {
        assertEquals(TrimHandle.Start, trimHandleAt(95f, startX = 100f, endX = 110f, reach = 30f))
        assertEquals(TrimHandle.End, trimHandleAt(115f, startX = 100f, endX = 110f, reach = 30f))
    }

    @Test
    fun shortVideosCanBeKeptWhole() {
        assertEquals(500L, minTrimLength(60_000L))
        assertEquals(300L, minTrimLength(300L))
    }

    @Test
    fun trimTimesShowTenths() {
        assertEquals("0:00.0", formatTrimTime(0L))
        assertEquals("0:03.2", formatTrimTime(3_240L))
        assertEquals("1:00.0", formatTrimTime(59_960L))
        assertEquals("1:02:03.5", formatTrimTime(3_723_500L))
    }
}
