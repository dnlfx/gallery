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
    fun singleTapsWaitAndDoubleTapsSkip() {
        val taps = TapSequence(doubleTapMillis = 300)
        // A lone tap on the side only toggles the controls.
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.FORWARD, 1_000, 1_050))
        // A second tap there in time skips, and each further one skips again.
        assertEquals(TapResult.SKIP_FORWARD, taps.onTap(TapZone.FORWARD, 1_200, 1_250))
        assertEquals(TapResult.SKIP_FORWARD, taps.onTap(TapZone.FORWARD, 1_450, 1_500))
        // Too slow: a fresh single tap.
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.FORWARD, 2_500, 2_550))
        // The other side, or the middle, never skips on a second tap.
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.BACK, 2_600, 2_650))
        assertEquals(TapResult.SKIP_BACK, taps.onTap(TapZone.BACK, 2_700, 2_750))
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.CENTER, 5_000, 5_050))
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.CENTER, 5_100, 5_150))
    }

    @Test
    fun aSlideBreaksTheTapSequence() {
        val taps = TapSequence(doubleTapMillis = 300)
        taps.onTap(TapZone.BACK, 1_000, 1_050)
        taps.reset()
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.BACK, 1_200, 1_250))
    }

    @Test
    fun clampsSeeksIntoTheVideo() {
        assertEquals(0L, clampPosition(-5_000, 60_000))
        assertEquals(60_000L, clampPosition(70_000, 60_000))
        assertEquals(70_000L, clampPosition(70_000, 0))
    }

    @Test
    fun shortClipsTakeTwoScreenWidthsToCross() {
        // A 20 second clip at a careful pace: 400dp, about one portrait screen width, moves 10 seconds.
        assertEquals(25f, scrubMillisPerDp(20_000), 0.001f)
        assertEquals(15_000f, scrubStep(5_000f, 400f, 100f, 20_000), 0.5f)
    }

    @Test
    fun longVideosCapTheScrubRate() {
        // A one hour video: a careful 400dp slide moves 100 seconds, not a quarter of the hour.
        assertEquals(250f, scrubMillisPerDp(3_600_000), 0.001f)
        assertEquals(110_000f, scrubStep(10_000f, 400f, 100f, 3_600_000), 0.5f)
        // Unknown length scrubs at the long-video rate.
        assertEquals(250f, scrubMillisPerDp(0), 0.001f)
    }

    @Test
    fun fasterSlidesCoverMoreGround() {
        assertEquals(1f, scrubGain(0f), 0.0001f)
        assertEquals(1f, scrubGain(400f), 0.0001f)
        assertEquals(2f, scrubGain(1_450f), 0.0001f)
        assertEquals(3f, scrubGain(2_500f), 0.0001f)
        assertEquals(3f, scrubGain(10_000f), 0.0001f)
    }

    @Test
    fun scrubStepsStayInsideTheVideo() {
        assertEquals(0f, scrubStep(1_000f, -500f, 100f, 20_000), 0.0001f)
        assertEquals(20_000f, scrubStep(19_000f, 500f, 100f, 20_000), 0.0001f)
        // Sliding back from the end responds straight away instead of unwinding an overshoot.
        assertEquals(19_750f, scrubStep(20_000f, -10f, 100f, 20_000), 0.5f)
    }

    @Test
    fun shortClipsPreviewExactFrames() {
        assertEquals(true, scrubsExactly(20_000))
        assertEquals(true, scrubsExactly(180_000))
        assertEquals(false, scrubsExactly(600_000))
        assertEquals(false, scrubsExactly(0))
        assertEquals(false, scrubsExactly(-9_223_372_036_854_775_807L))
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
