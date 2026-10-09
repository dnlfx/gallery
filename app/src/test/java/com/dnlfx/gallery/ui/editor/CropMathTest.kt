package com.dnlfx.gallery.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    // A box from (100, 200) to (500, 800) on screen, grabbed within 40 pixels.
    private fun handleAt(x: Float, y: Float) = cropHandleAt(x, y, 100f, 200f, 500f, 800f, reach = 40f)

    @Test
    fun cornersEdgesAndInsideAreGrabbed() {
        assertEquals(CropHandle.TopLeft, handleAt(110f, 190f))
        assertEquals(CropHandle.BottomRight, handleAt(520f, 790f))
        assertEquals(CropHandle.Top, handleAt(300f, 230f))
        assertEquals(CropHandle.Right, handleAt(480f, 500f))
        assertEquals(CropHandle.Move, handleAt(300f, 500f))
        assertNull(handleAt(20f, 500f))
        assertNull(handleAt(300f, 900f))
    }

    @Test
    fun aNarrowBoxPicksTheCloserSide() {
        // 50 pixels wide: both sides are within reach of the middle.
        assertEquals(CropHandle.Left, cropHandleAt(110f, 500f, 100f, 200f, 150f, 800f, reach = 40f))
        assertEquals(CropHandle.Right, cropHandleAt(140f, 500f, 100f, 200f, 150f, 800f, reach = 40f))
    }

    @Test
    fun draggingACornerResizesTwoSides() {
        val crop = dragCrop(CropRect.Full, CropHandle.TopLeft, 0.1f, 0.2f, 0.05f, 0.05f)
        assertEquals(CropRect(0.1f, 0.2f, 1f, 1f), crop)
    }

    @Test
    fun edgesStopAtThePictureAndTheMinimumSize() {
        assertEquals(CropRect.Full, dragCrop(CropRect.Full, CropHandle.Left, -0.3f, 0f, 0.1f, 0.1f))
        val squeezed = dragCrop(CropRect(0.2f, 0.2f, 0.6f, 0.6f), CropHandle.Right, -0.5f, 0f, 0.1f, 0.1f)
        assertEquals(0.3f, squeezed.right, 1e-6f)
    }

    @Test
    fun movingSlidesAlongTheEdges() {
        val moved = dragCrop(CropRect(0.2f, 0.2f, 0.6f, 0.6f), CropHandle.Move, 0.6f, -0.1f, 0.1f, 0.1f)
        assertEquals(0.6f, moved.left, 1e-6f)
        assertEquals(1f, moved.right, 1e-6f)
        assertEquals(0.1f, moved.top, 1e-6f)
        assertEquals(0.5f, moved.bottom, 1e-6f)
    }

    @Test
    fun fullIsRecognized() {
        assertTrue(CropRect.Full.isFull)
        assertTrue(CropRect(0.001f, 0f, 1f, 0.999f).isFull)
        assertFalse(CropRect(0.1f, 0f, 1f, 1f).isFull)
    }

    @Test
    fun pixelsRoundAndStayInside() {
        assertEquals(CropPixels(400, 300, 3600, 2700), CropRect(0.1f, 0.1f, 0.9f, 0.9f).toPixels(4000, 3000))
        assertEquals(CropPixels(0, 0, 1, 1), CropRect(0f, 0f, 0f, 0f).toPixels(10, 10))
    }

    @Test
    fun videoCropsSnapToEvenSizes() {
        // 1001 x 999 pixels on a 1920 x 1080 video becomes 1002 x 1000.
        val snapped = CropRect(100 / 1920f, 50 / 1080f, 1101 / 1920f, 1049 / 1080f).snapToEvenPixels(1920, 1080)
        val pixels = snapped.toPixels(1920, 1080)
        assertEquals(0, pixels.width % 2)
        assertEquals(0, pixels.height % 2)
        assertEquals(1002, pixels.width)
        assertEquals(1000, pixels.height)
        // At the right edge it grows to the left instead.
        val atEdge = CropRect(1 / 1920f, 0f, 1f, 1f).snapToEvenPixels(1920, 1080).toPixels(1920, 1080)
        assertEquals(CropPixels(0, 0, 1920, 1080), atEdge)
    }

    @Test
    fun ndcFlipsTheVerticalAxis() {
        assertEquals(NdcCrop(-1f, 1f, -1f, 1f), CropRect.Full.toNdc())
        assertEquals(NdcCrop(-0.5f, 0.5f, -0.5f, 0.5f), CropRect(0.25f, 0.25f, 0.75f, 0.75f).toNdc())
        assertEquals(NdcCrop(-1f, 0f, 0f, 1f), CropRect(0f, 0f, 0.5f, 0.5f).toNdc())
    }
}
