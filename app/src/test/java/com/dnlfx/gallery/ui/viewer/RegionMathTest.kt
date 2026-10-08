package com.dnlfx.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionMathTest {
    // A 4000 x 3000 photo fitted into a 1000 x 2000 portrait screen: 1000 x 750, centered.
    private fun viewport(scale: Float, offsetX: Float = 0f, offsetY: Float = 0f) =
        ZoomViewport(1000f, 2000f, 1000f, 750f, scale, offsetX, offsetY)

    @Test
    fun unzoomedShowsTheWholePhoto() {
        assertEquals(PixelRect(0, 0, 4000, 3000), visibleImageRect(viewport(1f), 4000, 3000))
    }

    @Test
    fun zoomingInOnTheCenterShowsTheMiddle() {
        // At 4x the screen shows the middle 250 x 500 layout pixels: 1000 x 2000 image pixels.
        assertEquals(PixelRect(1500, 500, 2500, 2500), visibleImageRect(viewport(4f), 4000, 3000))
    }

    @Test
    fun panningMovesTheVisiblePart() {
        // Panned right by 1000 screen pixels at 4x: the view moves 250 layout pixels to the left.
        assertEquals(PixelRect(500, 500, 1500, 2500), visibleImageRect(viewport(4f, offsetX = 1000f), 4000, 3000))
    }

    @Test
    fun nothingVisibleGivesNull() {
        assertNull(visibleImageRect(viewport(1f, offsetX = 5000f), 4000, 3000))
        assertNull(visibleImageRect(viewport(0f), 4000, 3000))
    }

    @Test
    fun sampleSizeStaysCloseToScreenResolution() {
        assertEquals(1, regionSampleSize(1000, 1000f))
        assertEquals(1, regionSampleSize(1400, 1000f))
        assertEquals(2, regionSampleSize(1600, 1000f))
        assertEquals(4, regionSampleSize(4000, 1000f))
        assertEquals(1, regionSampleSize(0, 1000f))
    }

    @Test
    fun uprightRectsMapToStoredPixels() {
        // Stored 4000 x 3000 (landscape), shown upright as 3000 x 4000 after a 90 degree turn.
        val topLeftCorner = PixelRect(0, 0, 100, 200)
        assertEquals(PixelRect(0, 2900, 200, 3000), uprightToStored(topLeftCorner, 90, 4000, 3000))
        assertEquals(PixelRect(3800, 0, 4000, 100), uprightToStored(topLeftCorner, 270, 4000, 3000))
        // A 180 degree turn keeps the shape: top left upright is bottom right stored.
        assertEquals(PixelRect(3900, 2800, 4000, 3000), uprightToStored(topLeftCorner, 180, 4000, 3000))
        assertEquals(topLeftCorner, uprightToStored(topLeftCorner, 0, 4000, 3000))
    }

    @Test
    fun aspectCheckAllowsRoundingOnly() {
        assertTrue(sameAspect(4000f, 3000f, 1000f, 750f))
        assertTrue(sameAspect(4000f, 3000f, 1000f, 751f))
        assertFalse(sameAspect(3000f, 4000f, 1000f, 750f))
        assertFalse(sameAspect(0f, 3000f, 1000f, 750f))
    }
}
