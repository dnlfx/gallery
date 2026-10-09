package com.dnlfx.gallery.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EditTransformTest {
    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals("x", x, actual.first, 1e-3f)
        assertEquals("y", y, actual.second, 1e-3f)
    }

    private fun assertCrop(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, 1e-4f)
        assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.right, actual.right, 1e-4f)
        assertEquals(expected.bottom, actual.bottom, 1e-4f)
    }

    @Test
    fun aQuarterTurnPutsTheTopLeftCornerTopRight() {
        // A 400 x 300 photo turned clockwise is 300 x 400.
        val map = Transform(quarterTurns = 1).pictureToFrame(400f, 300f)
        assertPoint(300f, 0f, map.map(0f, 0f))
        assertPoint(0f, 400f, map.map(400f, 300f))
    }

    @Test
    fun flipsMirrorThePicture() {
        assertPoint(400f, 0f, Transform(flipHorizontal = true).pictureToFrame(400f, 300f).map(0f, 0f))
        assertPoint(0f, 300f, Transform(flipVertical = true).pictureToFrame(400f, 300f).map(0f, 0f))
    }

    @Test
    fun mirroringOnScreenMatchesMirroringTheFrame() {
        // However the picture is turned, mirroring it on screen moves each point to the other side.
        for (turns in 0..3) {
            val before = Transform(quarterTurns = turns, straighten = 10f)
            val after = before.mirroredHorizontally()
            val (width, height) = before.frameSize(400f, 300f)
            val (x, y) = before.pictureToFrame(400f, 300f).map(50f, 80f)
            assertPoint(width - x, y, after.pictureToFrame(400f, 300f).map(50f, 80f))
            val vertical = before.mirroredVertically()
            assertPoint(x, height - y, vertical.pictureToFrame(400f, 300f).map(50f, 80f))
        }
    }

    @Test
    fun straighteningZoomsSoNoCornerShows() {
        assertEquals(1f, straightenZoom(400f, 300f, 0f), 0f)
        // A square tilted 45 degrees needs to grow by the square root of 2.
        assertEquals(sqrt(2f), straightenZoom(100f, 100f, 45f), 1e-4f)
        val zoom = straightenZoom(400f, 300f, 10f)
        val map = Transform(straighten = 10f).pictureToFrame(400f, 300f)
        // Every frame corner maps back inside the picture.
        val back = map.inverse()
        for ((x, y) in listOf(0f to 0f, 400f to 0f, 0f to 300f, 400f to 300f)) {
            val (px, py) = back.map(x, y)
            assertTrue("$px", px >= -1e-3f && px <= 400.001f)
            assertTrue("$py", py >= -1e-3f && py <= 300.001f)
        }
        assertTrue(zoom > 1f)
    }

    @Test
    fun inverseUndoesTheMap() {
        val map = Transform(quarterTurns = 3, flipHorizontal = true, straighten = -7f).pictureToFrame(640f, 480f)
        val (x, y) = map.map(123f, 45f)
        assertPoint(123f, 45f, map.inverse().map(x, y))
    }

    @Test
    fun videoMatrixWorksInDeviceCoordinates() {
        // A 1920 x 1080 video turned clockwise: the right edge's middle ends up at the bottom.
        val matrix = Transform(quarterTurns = 1).videoMatrix(1920f, 1080f)
        assertPoint(0f, -1f, matrix.map(1f, 0f))
        assertPoint(1f, 1f, matrix.map(-1f, 1f))
        assertPoint(0f, 0f, Transform().videoMatrix(1920f, 1080f).map(0f, 0f))
        assertPoint(-1f, 1f, Transform(flipHorizontal = true).videoMatrix(1920f, 1080f).map(1f, 1f))
    }

    @Test
    fun cropsFollowTheFrame() {
        val crop = CropRect(0.1f, 0.2f, 0.5f, 0.6f)
        assertCrop(CropRect(0.4f, 0.1f, 0.8f, 0.5f), crop.rotatedClockwise())
        assertCrop(CropRect(0.5f, 0.2f, 0.9f, 0.6f), crop.mirroredHorizontally())
        assertCrop(CropRect(0.1f, 0.4f, 0.5f, 0.8f), crop.mirroredVertically())
        // Auto fit finds a crop on the original; turned, it lands where the frame shows it.
        assertCrop(crop.rotatedClockwise(), Transform(quarterTurns = 1).mapCrop(crop, 4f / 3f))
    }

    @Test
    fun neutralAdjustmentsChangeNothing() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertArrayEquals(identity, Adjustments().colorMatrix(), 1e-5f)
        assertTrue(Adjustments().isNeutral)
    }

    @Test
    fun adjustmentsMoveColorsTheRightWay() {
        fun apply(m: FloatArray, r: Float, g: Float, b: Float) = Triple(
            m[0] * r + m[1] * g + m[2] * b + m[4],
            m[5] * r + m[6] * g + m[7] * b + m[9],
            m[10] * r + m[11] * g + m[12] * b + m[14],
        )
        val gray = 128f
        assertTrue(apply(Adjustments(brightness = 1f).colorMatrix(), gray, gray, gray).first > gray)
        // More contrast pushes light colors lighter and dark ones darker.
        val contrast = Adjustments(contrast = 1f).colorMatrix()
        assertTrue(apply(contrast, 200f, 200f, 200f).first > 200f)
        assertTrue(apply(contrast, 50f, 50f, 50f).first < 50f)
        // No saturation turns red gray.
        val (r, g, b) = apply(Adjustments(saturation = -1f).colorMatrix(), 255f, 0f, 0f)
        assertEquals(r, g, 1e-3f)
        assertEquals(g, b, 1e-3f)
        val (warmR, _, warmB) = apply(Adjustments(warmth = 1f).colorMatrix(), gray, gray, gray)
        assertTrue(warmR > gray && warmB < gray)
    }

    @Test
    fun ratiosStandOnEndForPortrait() {
        assertNull(lockedRatio(AspectChoice.Free, portrait = false, frameAspect = 1.5f))
        assertEquals(16f / 9f, lockedRatio(AspectChoice.SixteenNine, portrait = false, frameAspect = 1.5f)!!, 1e-5f)
        assertEquals(9f / 16f, lockedRatio(AspectChoice.SixteenNine, portrait = true, frameAspect = 1.5f)!!, 1e-5f)
        assertEquals(0.75f, lockedRatio(AspectChoice.Original, portrait = true, frameAspect = 0.75f)!!, 1e-5f)
        assertEquals(1f, lockedRatio(AspectChoice.Square, portrait = true, frameAspect = 0.75f)!!, 1e-5f)
    }
}
