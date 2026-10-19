package com.dnlfx.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // Switching sides starts a new sequence.
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.BACK, 2_600, 2_650))
        assertEquals(TapResult.SKIP_BACK, taps.onTap(TapZone.BACK, 2_700, 2_750))
    }

    @Test
    fun doubleTapInTheMiddlePlaysOrPauses() {
        val taps = TapSequence(doubleTapMillis = 300)
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.CENTER, 5_000, 5_050))
        assertEquals(TapResult.TOGGLE_PLAY, taps.onTap(TapZone.CENTER, 5_100, 5_150))
        // A third quick tap doesn't toggle again straight away.
        assertEquals(TapResult.PENDING, taps.onTap(TapZone.CENTER, 5_200, 5_250))
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
    fun framesLastAsLongAsTheFrameRateSays() {
        assertEquals(33_333L, frameDurationUs(30f))
        assertEquals(16_667L, frameDurationUs(60f))
        assertEquals(41_708L, frameDurationUs(23.976f))
        // A video that doesn't say counts as 30 frames a second.
        assertEquals(33_333L, frameDurationUs(-1f))
    }

    @Test
    fun aCarefulSlideStepsOneFrameAtATime() {
        // 6dp at an ordinary pace is one frame, and a 400dp slide is about 67 frames: two
        // seconds of a 30fps video, not the ten seconds the old scrub moved.
        assertEquals(1f, scrubFrames(6f, 100f), 0.0001f)
        assertEquals(-1f, scrubFrames(-6f, 100f), 0.0001f)
        assertEquals(66.67f, scrubFrames(400f, 800f), 0.01f)
    }

    @Test
    fun onlyAFastFlickCoversSeveralFramesPerStep() {
        assertEquals(1f, scrubGain(0f), 0.0001f)
        assertEquals(1f, scrubGain(1_200f), 0.0001f)
        assertEquals(2.5f, scrubGain(2_100f), 0.0001f)
        assertEquals(4f, scrubGain(3_000f), 0.0001f)
        assertEquals(4f, scrubGain(10_000f), 0.0001f)
    }

    @Test
    fun framesStayInsideTheVideo() {
        val frameUs = frameDurationUs(30f)
        // A 10 second 30fps video has frames 0 to 299.
        assertEquals(299L, lastFrame(10_000, frameUs))
        assertEquals(null, lastFrame(0, frameUs))
        assertEquals(0L, clampFrame(-3, 299))
        assertEquals(299L, clampFrame(400, 299))
        assertEquals(400L, clampFrame(400, null))
        // The frame on screen partway through it.
        assertEquals(30L, frameAt(1_010_000, frameUs))
    }

    @Test
    fun eachFrameSeekLandsOnItsOwnFrame() {
        val frameUs = frameDurationUs(30f)
        // An exact seek shows the first frame at or after the target, so aiming half a frame
        // early shows frame k, never the one before or after, even with slightly uneven timestamps.
        for (frame in 1L..600L) {
            val targetUs = frameSeekMillis(frame, frameUs) * 1_000L
            val frameTimeUs = frame * frameUs
            assertTrue(targetUs <= frameTimeUs - 10_000L)
            assertTrue(targetUs > (frame - 1) * frameUs + 10_000L)
        }
        assertEquals(0L, frameSeekMillis(0, frameUs))
        assertEquals(1_000L, frameStartMillis(30, frameUs))
    }

    @Test
    fun preciseTimesShowHundredths() {
        assertEquals("0:00.00", formatPreciseTime(0))
        assertEquals("0:12.36", formatPreciseTime(12_369))
        assertEquals("1:02:03.50", formatPreciseTime(3_723_500))
    }

    @Test
    fun scrubPreviewsStopJustShortOfTheEnd() {
        assertEquals(10_000L, scrubSeekPosition(10_000, 30_000))
        assertEquals(29_950L, scrubSeekPosition(30_000, 30_000))
        // Unknown length: nothing to stop short of.
        assertEquals(30_000L, scrubSeekPosition(30_000, -1))
    }

    @Test
    fun aJumpLandsOnTheFirstFrameAtOrJustPastItsTarget() {
        assertTrue(seekLanded(framePositionUs = 5_000_000, targetUs = 5_000_000))
        // The next frame of a 30fps video.
        assertTrue(seekLanded(framePositionUs = 5_033_333, targetUs = 5_000_000))
        // Frames still showing from before a jump forward or back don't count.
        assertFalse(seekLanded(framePositionUs = 2_000_000, targetUs = 5_000_000))
        assertFalse(seekLanded(framePositionUs = 9_000_000, targetUs = 5_000_000))
    }

    @Test
    fun pinchingZoomsAroundTheFingers() {
        // A 1080x608 video fitted in a 1080x2400 portrait screen.
        val video = VideoZoom.Size(1080f, 608f)
        val screen = VideoZoom.Size(1080f, 2400f)
        // Doubling in size around a point 200px right of centre keeps that point still.
        val zoomed = VideoZoom().transformed(2f, 200f, 0f, 0f, 0f, video, screen)
        assertEquals(2f, zoomed.scale, 0.0001f)
        assertEquals(-200f, zoomed.x, 0.0001f)
        // Taller than the screen it isn't, so it stays centred vertically.
        assertEquals(0f, zoomed.y, 0.0001f)
        // Can't zoom out past the fitted size or in past 5x.
        assertEquals(1f, zoomed.transformed(0.1f, 0f, 0f, 0f, 0f, video, screen).scale, 0.0001f)
        assertEquals(5f, zoomed.transformed(10f, 0f, 0f, 0f, 0f, video, screen).scale, 0.0001f)
    }

    @Test
    fun panningStopsAtTheVideosEdges() {
        val video = VideoZoom.Size(1080f, 608f)
        val screen = VideoZoom.Size(1080f, 2400f)
        val zoomed = VideoZoom(scale = 2f)
        // At 2x the video is 2160 wide, so it can move 540px each way, and not at all vertically.
        assertEquals(540f, zoomed.panned(5_000f, 300f, video, screen).x, 0.0001f)
        assertEquals(0f, zoomed.panned(5_000f, 300f, video, screen).y, 0.0001f)
        assertEquals(-540f, zoomed.panned(-5_000f, 0f, video, screen).x, 0.0001f)
    }

    @Test
    fun barelyZoomedSnapsBack() {
        assertEquals(VideoZoom(), VideoZoom(scale = 1.03f, x = 4f).settled())
        assertEquals(VideoZoom(scale = 1.5f), VideoZoom(scale = 1.5f).settled())
    }

    @Test
    fun aVideoRecordedSidewaysShowsWithItsSidesSwapped() {
        assertEquals(1920 to 1080, uprightSize(1920, 1080, 0))
        assertEquals(1080 to 1920, uprightSize(1920, 1080, 90))
        assertEquals(1920 to 1080, uprightSize(1920, 1080, 180))
        assertEquals(1080 to 1920, uprightSize(1920, 1080, 270))
        assertEquals(1080 to 1920, uprightSize(1920, 1080, -90))
    }

    @Test
    fun upAndDownSlidesDependOnWhichThirdTheyStartIn() {
        assertEquals(VerticalSlide.BRIGHTNESS, verticalSlide(tapZone(100f, 1080f)))
        assertEquals(VerticalSlide.CLOSE, verticalSlide(tapZone(540f, 1080f)))
        assertEquals(VerticalSlide.VOLUME, verticalSlide(tapZone(1000f, 1080f)))
    }

    @Test
    fun volumeAndBrightnessFollowVerticalSlides() {
        assertEquals(0.75f, levelAfterDrag(0.5f, 500f, 2000f), 0.0001f)
        assertEquals(1f, levelAfterDrag(0.5f, 5000f, 2000f), 0.0001f)
        assertEquals(0f, levelAfterDrag(0.5f, -5000f, 2000f), 0.0001f)
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
