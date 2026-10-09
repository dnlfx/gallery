package com.dnlfx.gallery.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ContentBoundsTest {
    private class Picture(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)

        fun fill(left: Int, top: Int, right: Int, bottom: Int, color: (x: Int, y: Int) -> Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color(x, y) or OPAQUE
        }

        fun bounds() = ContentBounds.of(pixels, width, height)
    }

    private val random = Random(7)

    /** A busy, photo-like color: blotches of random color with some grain. */
    private fun scene(x: Int, y: Int): Int {
        val block = Random((x / 6) * 7919 + (y / 6) * 104729)
        val r = (block.nextInt(40, 220) + random.nextInt(-10, 10)).coerceIn(0, 255)
        val g = (block.nextInt(40, 220) + random.nextInt(-10, 10)).coerceIn(0, 255)
        val b = (block.nextInt(40, 220) + random.nextInt(-10, 10)).coerceIn(0, 255)
        return rgb(r, g, b)
    }

    /** Near black, like the compression noise in a video's black bars. */
    private fun bar(): Int = random.nextInt(0, 10).let { rgb(it, it, it) }

    private fun assertNear(expected: Float, actual: Float) =
        assertTrue("expected $expected, was $actual", abs(expected - actual) < 0.02f)

    private fun assertCrop(expected: CropRect, actual: CropRect?) {
        requireNotNull(actual)
        assertNear(expected.left, actual.left)
        assertNear(expected.top, actual.top)
        assertNear(expected.right, actual.right)
        assertNear(expected.bottom, actual.bottom)
    }

    @Test
    fun aFullPictureKeepsEverything() {
        val picture = Picture(160, 120).apply { fill(0, 0, 160, 120, ::scene) }
        val bounds = picture.bounds()
        assertFalse(bounds.blank)
        assertCrop(CropRect.Full, bounds.toCrop())
        assertTrue(bounds.top.confident && bounds.bottom.confident)
    }

    @Test
    fun letterboxBarsAreTrimmed() {
        // A 2.39:1 film in a 16:9 frame: black above and below.
        val picture = Picture(320, 180).apply {
            fill(0, 0, 320, 180) { _, _ -> bar() }
            fill(0, 23, 320, 157, ::scene)
        }
        assertCrop(CropRect(0f, 23 / 180f, 1f, 157 / 180f), picture.bounds().toCrop())
    }

    @Test
    fun pillarboxBarsAreTrimmed() {
        // A portrait video shown in a landscape frame.
        val picture = Picture(320, 180).apply {
            fill(0, 0, 320, 180) { _, _ -> bar() }
            fill(110, 0, 210, 180, ::scene)
        }
        assertCrop(CropRect(110 / 320f, 0f, 210 / 320f, 1f), picture.bounds().toCrop())
    }

    @Test
    fun screenshotChromeIsTrimmed() {
        // A phone screenshot of a video player: status bar with icons, a toolbar with a title,
        // the video, a white page with lines of text, and the navigation bar with its pill.
        val white = rgb(250, 250, 250)
        val text = rgb(30, 30, 30)
        val picture = Picture(200, 400).apply {
            fill(0, 0, 200, 16) { x, _ -> if (x in 8..20 || x in 170..190) rgb(255, 255, 255) else rgb(20, 20, 20) }
            fill(0, 16, 200, 48) { x, y -> if (y in 26..38 && x in 40..110 && x % 3 != 0) text else white }
            fill(0, 48, 200, 160, ::scene)
            fill(0, 160, 200, 380) { x, y -> if (y % 14 in 4..9 && x in 12..150 && x % 4 != 0) text else white }
            fill(0, 380, 200, 400) { x, y -> if (y in 388..391 && x in 80..120) text else white }
        }
        assertCrop(CropRect(0f, 48 / 400f, 1f, 160 / 400f), picture.bounds().toCrop())
    }

    @Test
    fun clearSkyAtTheTopOfAPhotoStays() {
        // Blue sky fading down to a mountain whose peak rises in the middle.
        val picture = Picture(300, 200).apply {
            fill(0, 0, 300, 200) { x, y ->
                val ridge = 60 + abs(x - 150) * 2 / 5
                if (y < ridge) rgb(90 + y / 4, 150 + y / 5, 230) else scene(x, y)
            }
        }
        val bounds = picture.bounds()
        assertNear(0f, bounds.top.position)
        assertFalse(bounds.top.confident)
    }

    @Test
    fun aBlackFrameIsBlank() {
        val picture = Picture(160, 90).apply { fill(0, 0, 160, 90) { _, _ -> bar() } }
        assertTrue(picture.bounds().blank)
    }

    @Test
    fun framesCombineToTheWidestConfidentEdges() {
        // In one frame the picture is bright up to the bars; in the next it's a dark scene whose
        // edges can't be told from the bars, and in the last the action sits lower down.
        val bright = Picture(320, 180).apply {
            fill(0, 0, 320, 180) { _, _ -> bar() }
            fill(0, 23, 320, 157, ::scene)
        }
        val dark = Picture(320, 180).apply {
            fill(0, 0, 320, 180) { _, _ -> bar() }
            fill(0, 23, 320, 157) { x, y -> if (y in 70..110) scene(x, y) else rgb(12, 12, 14) }
        }
        val black = Picture(320, 180).apply { fill(0, 0, 320, 180) { _, _ -> bar() } }
        val combined = ContentBounds.combine(listOf(bright.bounds(), dark.bounds(), black.bounds()))
        assertCrop(CropRect(0f, 23 / 180f, 1f, 157 / 180f), combined)
    }

    @Test
    fun nothingToCombineGivesNull() {
        val black = Picture(160, 90).apply { fill(0, 0, 160, 90) { _, _ -> bar() } }
        assertNull(ContentBounds.combine(listOf(black.bounds())))
        assertNull(ContentBounds.combine(emptyList()))
    }

    @Test
    fun aFullFrameStaysFull() {
        assertEquals(CropRect.Full, ContentBounds.combine(listOf(Picture(64, 64).apply { fill(0, 0, 64, 64, ::scene) }.bounds())))
    }

    private companion object {
        const val OPAQUE = 0xFF shl 24
        fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    }
}
