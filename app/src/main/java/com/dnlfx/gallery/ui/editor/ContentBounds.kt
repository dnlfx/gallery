package com.dnlfx.gallery.ui.editor

import kotlin.math.abs
import kotlin.math.max

/**
 * Best-effort search for the main picture in a frame: the part left after black bars and flat
 * app chrome (status bar, navigation bar, toolbars, page background) are taken off its edges.
 *
 * Each row (then each column) counts as background when most of it is one color, which is true
 * of bars and of UI that's mostly background with some text or icons, and rarely of a photo or
 * video. The largest run of non-background rows is the picture. An edge is only trimmed when the
 * picture starts sharply there, so a clear sky at the top of a photo, which also looks like a flat
 * band but fades into the scene, is left alone.
 *
 * Works on small frames (a few hundred pixels across); the result is in fractions, so it applies
 * to the full-size picture.
 */
class ContentBounds internal constructor(
    val left: Side,
    val top: Side,
    val right: Side,
    val bottom: Side,
    /** The frame is all background, like a black frame between scenes: it says nothing. */
    val blank: Boolean,
) {
    /** Where one edge of the picture is, as a fraction, and whether that's a confident answer. */
    data class Side(val position: Float, val confident: Boolean)

    fun toCrop(): CropRect = CropRect(left.position, top.position, right.position, bottom.position)

    companion object {
        /** Bounds for one frame, given as ARGB [pixels] row by row. */
        fun of(pixels: IntArray, width: Int, height: Int): ContentBounds {
            require(pixels.size >= width * height)
            return Detector(pixels, width, height).detect()
        }

        /**
         * One crop for several frames of the same video: each edge goes as far out as any frame
         * confidently puts it, so a dark scene or a frame with a flat edge doesn't cut into the
         * picture. Frames with nothing to go on are skipped. Null when no frame says anything.
         */
        fun combine(frames: List<ContentBounds>): CropRect? {
            val useful = frames.filterNot { it.blank }
            if (useful.isEmpty()) return null
            fun outermost(side: (ContentBounds) -> Side, fallback: Float, pick: (Float, Float) -> Float): Float =
                useful.map(side).filter { it.confident }.map { it.position }.reduceOrNull(pick) ?: fallback
            val crop = CropRect(
                left = outermost({ it.left }, 0f, ::minOf),
                top = outermost({ it.top }, 0f, ::minOf),
                right = outermost({ it.right }, 1f, ::maxOf),
                bottom = outermost({ it.bottom }, 1f, ::maxOf),
            )
            return crop.takeIf { it.width > 0f && it.height > 0f }
        }
    }
}

private class Detector(private val pixels: IntArray, private val width: Int, private val height: Int) {
    // Current guess, in pixels, right and bottom exclusive, and how sure each edge is.
    private var left = 0
    private var top = 0
    private var right = width
    private var bottom = height
    private var leftSure = true
    private var topSure = true
    private var rightSure = true
    private var bottomSure = true
    private var blank = false
    private var firstPass = true

    private val histogram = IntArray(BINS)

    fun detect(): ContentBounds {
        // A second pass catches chrome that only shows once the first has narrowed things down,
        // like a side panel next to a video that only starts below a toolbar.
        repeat(2) {
            if (!blank) trimRows()
            if (!blank) trimColumns()
            firstPass = false
        }
        if (blank) {
            val start = ContentBounds.Side(0f, confident = false)
            val end = ContentBounds.Side(1f, confident = false)
            return ContentBounds(start, start, end, end, blank = true)
        }
        return ContentBounds(
            ContentBounds.Side(left / width.toFloat(), leftSure),
            ContentBounds.Side(top / height.toFloat(), topSure),
            ContentBounds.Side(right / width.toFloat(), rightSure),
            ContentBounds.Side(bottom / height.toFloat(), bottomSure),
            blank = false,
        )
    }

    private fun trimRows() {
        val span = Span(top, bottom)
        val result = trimAxis(
            span = span,
            wholeFrame = firstPass,
            lineColor = { y -> dominantColor(left + y * width, 1, right - left) },
            edgeContrast = { y, color -> contrast(left + y * width, 1, right - left, color) },
        ) ?: return
        top = result.start
        bottom = result.end
        topSure = result.startSure ?: topSure
        bottomSure = result.endSure ?: bottomSure
    }

    private fun trimColumns() {
        val span = Span(left, right)
        val result = trimAxis(
            span = span,
            wholeFrame = false,
            lineColor = { x -> dominantColor(top * width + x, width, bottom - top) },
            edgeContrast = { x, color -> contrast(top * width + x, width, bottom - top, color) },
        ) ?: return
        left = result.start
        right = result.end
        leftSure = result.startSure ?: leftSure
        rightSure = result.endSure ?: rightSure
    }

    private class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /** New edges along one axis; a null certainty means that edge wasn't looked at. */
    private class AxisResult(val start: Int, val end: Int, val startSure: Boolean?, val endSure: Boolean?)

    /**
     * Finds the largest run of non-background lines between [span]'s ends. [lineColor] gives a
     * line's background color, or null when it's busy; [edgeContrast] how much of a line stands
     * out from a given color.
     */
    private fun trimAxis(
        span: Span,
        wholeFrame: Boolean,
        lineColor: (Int) -> Int?,
        edgeContrast: (Int, Int) -> Float,
    ): AxisResult? {
        if (span.length < MIN_LINES) return null
        val colors = Array(span.length) { lineColor(span.start + it) }
        val busy = BooleanArray(span.length) { colors[it] == null }

        // Stretches of busy lines, grouped where only short flat gaps separate them (a uniform
        // band across a photo shouldn't split it). The group spanning the most lines wins.
        val stretches = mutableListOf<IntRange>()
        var stretchStart = -1
        for (i in 0..span.length) {
            val isBusy = i < span.length && busy[i]
            if (isBusy && stretchStart < 0) stretchStart = i
            if (!isBusy && stretchStart >= 0) {
                stretches += stretchStart until i
                stretchStart = -1
            }
        }
        if (stretches.isEmpty()) {
            // Only meaningful for the very first look at the whole frame; a narrowed-down part, or
            // columns of a pattern like vertical stripes, can be flat line by line by chance.
            if (wholeFrame) blank = true
            return null
        }
        val maxGap = max(2, (span.length * GAP_SHARE).toInt())
        val groups = mutableListOf(mutableListOf(stretches.first()))
        for (stretch in stretches.drop(1)) {
            val group = groups.last()
            if (stretch.first - group.last().last - 1 <= maxGap) group += stretch else groups += mutableListOf(stretch)
        }
        val best = groups.maxBy { it.last().last - it.first().first }
        // A small stretch at either end of the group that's set apart from the rest by a sharp
        // edge is chrome next to the picture, like a toolbar title just above a video.
        fun sharpAt(line: Int, backgroundLine: Int): Boolean {
            val background = colors[backgroundLine] ?: return false
            return edgeContrast(span.start + line, background) >= SHARP_EDGE_SHARE
        }
        while (best.size > 1) {
            val total = best.last().last - best.first().first + 1
            val first = best.first()
            val last = best.last()
            when {
                first.count() < total * SIDE_STRETCH_SHARE && sharpAt(best[1].first, best[1].first - 1) -> best.removeAt(0)
                last.count() < total * SIDE_STRETCH_SHARE && sharpAt(best[best.size - 2].last, best[best.size - 2].last + 1) ->
                    best.removeAt(best.size - 1)
                else -> break
            }
        }
        val bestStart = best.first().first
        val bestEnd = best.last().last + 1
        // Too small to be the main picture: more likely a stray icon or line of text.
        if (bestEnd - bestStart < span.length * MIN_PICTURE_SHARE) {
            return AxisResult(span.start, span.end, startSure = false, endSure = false)
        }

        var start = span.start
        var startSure: Boolean? = null
        if (bestStart > 0) {
            val background = colors[bestStart - 1]!!
            val sharp = (0 until EDGE_LINES).maxOf { edgeContrast(span.start + (bestStart + it).coerceAtMost(span.length - 1), background) }
            if (sharp >= SHARP_EDGE_SHARE) {
                start = span.start + bestStart
                startSure = true
            } else {
                startSure = false
            }
        }
        var end = span.end
        var endSure: Boolean? = null
        if (bestEnd < span.length) {
            val background = colors[bestEnd]!!
            val sharp = (0 until EDGE_LINES).maxOf { edgeContrast(span.start + (bestEnd - 1 - it).coerceAtLeast(0), background) }
            if (sharp >= SHARP_EDGE_SHARE) {
                end = span.start + bestEnd
                endSure = true
            } else {
                endSure = false
            }
        }
        return AxisResult(start, end, startSure, endSure)
    }

    /**
     * The color most of a line is (as RGB), or null when no color covers enough of it to count as
     * background. The line is [count] pixels from [offset], [step] apart.
     */
    private fun dominantColor(offset: Int, step: Int, count: Int): Int? {
        if (count <= 0) return null
        histogram.fill(0)
        var best = 0
        var bestCount = 0
        for (i in 0 until count) {
            val bin = bin(pixels[offset + i * step])
            val n = ++histogram[bin]
            if (n > bestCount) {
                bestCount = n
                best = bin
            }
        }
        // The middle of the busiest bin, then everything near it, which also counts noise and
        // gradients that straddle two bins.
        val center = binCenter(best)
        var near = 0
        for (i in 0 until count) {
            if (distance(pixels[offset + i * step], center) <= TOLERANCE) near++
        }
        return if (near >= count * BACKGROUND_SHARE) center else null
    }

    /** Share of a line that's clearly a different color from [color]. */
    private fun contrast(offset: Int, step: Int, count: Int, color: Int): Float {
        if (count <= 0) return 0f
        var different = 0
        for (i in 0 until count) {
            if (distance(pixels[offset + i * step], color) > EDGE_DIFFERENCE) different++
        }
        return different / count.toFloat()
    }

    private fun bin(argb: Int): Int =
        ((argb shr 20) and 0xF shl 8) or ((argb shr 12) and 0xF shl 4) or ((argb shr 4) and 0xF)

    private fun binCenter(bin: Int): Int {
        val r = (bin shr 8 and 0xF) * 16 + 8
        val g = (bin shr 4 and 0xF) * 16 + 8
        val b = (bin and 0xF) * 16 + 8
        return (r shl 16) or (g shl 8) or b
    }

    private fun distance(a: Int, b: Int): Int = max(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        max(abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    companion object {
        private const val BINS = 4096

        /** How far (per color channel) a pixel can be from a background color and still be part of it. */
        const val TOLERANCE = 24

        /** How much of a line has to be one color for the line to count as background. */
        const val BACKGROUND_SHARE = 0.8f

        /** Flat stretches up to this share of the frame inside the picture don't split it. */
        const val GAP_SHARE = 0.04f

        /** A stretch at the end of the picture smaller than this share of it may be chrome. */
        const val SIDE_STRETCH_SHARE = 0.25f

        /** The picture is at least this share of the frame each way. */
        const val MIN_PICTURE_SHARE = 0.15f

        /** A picture edge: at least this much of the first lines differ clearly from the background. */
        const val SHARP_EDGE_SHARE = 0.3f
        const val EDGE_DIFFERENCE = 48
        const val EDGE_LINES = 3

        /** Frames thinner than this aren't worth looking at. */
        const val MIN_LINES = 8
    }
}
