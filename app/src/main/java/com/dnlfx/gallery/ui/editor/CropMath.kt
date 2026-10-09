package com.dnlfx.gallery.ui.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A crop as fractions of the upright picture: 0 is the left or top edge, 1 the right or bottom.
 * Fractions keep it independent of how big the picture is on screen or in the file.
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when the crop still covers the whole picture, give or take a hair. */
    val isFull: Boolean
        get() = left <= EPSILON && top <= EPSILON && right >= 1f - EPSILON && bottom >= 1f - EPSILON

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
        private const val EPSILON = 0.002f
    }
}

/** The part of the crop box a finger grabbed. */
enum class CropHandle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TopLeft(left = true, top = true, right = false, bottom = false),
    Top(left = false, top = true, right = false, bottom = false),
    TopRight(left = false, top = true, right = true, bottom = false),
    Right(left = false, top = false, right = true, bottom = false),
    BottomRight(left = false, top = false, right = true, bottom = true),
    Bottom(left = false, top = false, right = false, bottom = true),
    BottomLeft(left = true, top = false, right = false, bottom = true),
    Left(left = true, top = false, right = false, bottom = false),

    /** Inside the box: drags the whole box. */
    Move(left = false, top = false, right = false, bottom = false),
}

/**
 * Which handle a touch at ([x], [y]) grabs, for a crop box shown at [left], [top], [right],
 * [bottom] on screen. Corners win over edges and edges over the inside, all within [reach] of the
 * touch, so a small box can still be resized. Null when the touch is nowhere near the box.
 */
fun cropHandleAt(
    x: Float,
    y: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    reach: Float,
): CropHandle? {
    val nearLeft = abs(x - left) <= reach
    val nearRight = abs(x - right) <= reach
    val nearTop = abs(y - top) <= reach
    val nearBottom = abs(y - bottom) <= reach
    // On a box narrower than two reaches, pick the closer side.
    val useLeft = nearLeft && (!nearRight || abs(x - left) <= abs(x - right))
    val useRight = nearRight && !useLeft
    val useTop = nearTop && (!nearBottom || abs(y - top) <= abs(y - bottom))
    val useBottom = nearBottom && !useTop
    val withinX = x >= left - reach && x <= right + reach
    val withinY = y >= top - reach && y <= bottom + reach
    if (!withinX || !withinY) return null
    return when {
        useLeft && useTop -> CropHandle.TopLeft
        useRight && useTop -> CropHandle.TopRight
        useRight && useBottom -> CropHandle.BottomRight
        useLeft && useBottom -> CropHandle.BottomLeft
        useTop -> CropHandle.Top
        useBottom -> CropHandle.Bottom
        useLeft -> CropHandle.Left
        useRight -> CropHandle.Right
        x > left && x < right && y > top && y < bottom -> CropHandle.Move
        else -> null
    }
}

/**
 * [crop] after dragging [handle] by [dx] and [dy] (fractions of the picture). Edges stop at the
 * picture's edges and never bring the box below [minWidth] by [minHeight]; a moved box slides
 * along the edges rather than shrinking.
 */
fun dragCrop(crop: CropRect, handle: CropHandle, dx: Float, dy: Float, minWidth: Float, minHeight: Float): CropRect {
    if (handle == CropHandle.Move) {
        val moveX = dx.coerceIn(-crop.left, 1f - crop.right)
        val moveY = dy.coerceIn(-crop.top, 1f - crop.bottom)
        return CropRect(crop.left + moveX, crop.top + moveY, crop.right + moveX, crop.bottom + moveY)
    }
    val minW = min(minWidth, crop.width)
    val minH = min(minHeight, crop.height)
    var (left, top, right, bottom) = crop
    if (handle.left) left = (left + dx).coerceIn(0f, right - minW)
    if (handle.right) right = (right + dx).coerceIn(left + minW, 1f)
    if (handle.top) top = (top + dy).coerceIn(0f, bottom - minH)
    if (handle.bottom) bottom = (bottom + dy).coerceIn(top + minH, 1f)
    return CropRect(left, top, right, bottom)
}

/** A rectangle in whole pixels, right and bottom exclusive. */
data class CropPixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** [crop] on a [width] by [height] picture, rounded to whole pixels and at least one pixel big. */
fun CropRect.toPixels(width: Int, height: Int): CropPixels {
    val l = (left * width).roundToInt().coerceIn(0, width - 1)
    val t = (top * height).roundToInt().coerceIn(0, height - 1)
    val r = (right * width).roundToInt().coerceIn(l + 1, width)
    val b = (bottom * height).roundToInt().coerceIn(t + 1, height)
    return CropPixels(l, t, r, b)
}

/**
 * [crop] on a [width] by [height] video, nudged to an even number of pixels each way, which is
 * what video encoders expect. Returned as fractions again, ready for [toNdc].
 */
fun CropRect.snapToEvenPixels(width: Int, height: Int): CropRect {
    if (width < 2 || height < 2) return this
    val pixels = toPixels(width, height)
    var (l, t, r, b) = pixels
    if ((r - l) % 2 != 0) if (r < width) r++ else l--
    if ((b - t) % 2 != 0) if (b < height) b++ else t--
    l = max(l, 0)
    t = max(t, 0)
    // A one-pixel-wide crop of an odd-sized video can still come out odd; drop the spare pixel.
    if ((r - l) % 2 != 0) r--
    if ((b - t) % 2 != 0) b--
    return CropRect(l / width.toFloat(), t / height.toFloat(), r / width.toFloat(), b / height.toFloat())
}

/** Media3's crop arguments: left, right, bottom and top, from -1 to 1 with y pointing up. */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float)

fun CropRect.toNdc(): NdcCrop = NdcCrop(
    left = left * 2f - 1f,
    right = right * 2f - 1f,
    bottom = 1f - bottom * 2f,
    top = 1f - top * 2f,
)
