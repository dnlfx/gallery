package com.dnlfx.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGestureMathTest {
    @Test
    fun tapZonesSplitTheScreenInThirds() {
        assertEquals(TapZone.BACK, tapZone(10f, 900f))
        assertEquals(TapZone.CENTER, tapZone(450f, 900f))
        assertEquals(TapZone.FORWARD, tapZone(890f, 900f))
        assertEquals(TapZone.CENTER, tapZone(10f, 0f))
    }

    @Test
    fun clampsSeeksIntoTheVideo() {
        assertEquals(0L, clampPosition(-5_000, 60_000))
        assertEquals(60_000L, clampPosition(70_000, 60_000))
        assertEquals(70_000L, clampPosition(70_000, 0))
    }

    @Test
    fun shortClipsSpanTheWholeScreen() {
        // A 30 second clip: sliding half the width moves 15 seconds.
        assertEquals(20_000L, scrubTarget(5_000, 450f, 900f, 30_000))
        assertEquals(0L, scrubTarget(5_000, -900f, 900f, 30_000))
    }

    @Test
    fun longVideosCapTheScrubSpan() {
        // A one hour video: sliding the full width moves two minutes, not the whole hour.
        assertEquals(130_000L, scrubTarget(10_000, 900f, 900f, 3_600_000))
    }

    @Test
    fun volumeFollowsVerticalSlides() {
        assertEquals(0.75f, volumeAfterDrag(0.5f, 500f, 2000f), 0.0001f)
        assertEquals(1f, volumeAfterDrag(0.5f, 5000f, 2000f), 0.0001f)
        assertEquals(0f, volumeAfterDrag(0.5f, -5000f, 2000f), 0.0001f)
    }

    @Test
    fun formatsSpeeds() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("0.25x", formatSpeed(0.25f))
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("3x", formatSpeed(3f))
    }

    @Test
    fun speedsRunFromQuarterToTriple() {
        assertEquals(0.25f, PlaybackSpeeds.first())
        assertEquals(3f, PlaybackSpeeds.last())
    }
}
