package com.dnlfx.gallery.ui.viewer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle in whole pixels, right and bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where a zoomed photo sits: the container it fills, the size of the fitted (unzoomed) image in
 * it, and the zoom applied around the container's center.
 */
data class ZoomViewport(
    val containerWidth: Float,
    val containerHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * The part of the photo on screen, in the photo's own (upright) pixels, or null if none of it is.
 * [imageWidth] and [imageHeight] are the full-resolution size the rectangle is measured in.
 */
fun visibleImageRect(viewport: ZoomViewport, imageWidth: Int, imageHeight: Int): PixelRect? = with(viewport) {
    if (scale <= 0f || fittedWidth <= 0f || fittedHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
    val centerX = containerWidth / 2f
    val centerY = containerHeight / 2f
    // Screen point s shows layout point p = center + (s - center - offset) / scale.
    val left = centerX + (0f - centerX - offsetX) / scale
    val right = centerX + (containerWidth - centerX - offsetX) / scale
    val top = centerY + (0f - centerY - offsetY) / scale
    val bottom = centerY + (containerHeight - centerY - offsetY) / scale

    val fittedLeft = (containerWidth - fittedWidth) / 2f
    val fittedTop = (containerHeight - fittedHeight) / 2f
    val toImageX = imageWidth / fittedWidth
    val toImageY = imageHeight / fittedHeight
    val rect = PixelRect(
        left = floor((max(left, fittedLeft) - fittedLeft) * toImageX).toInt().coerceIn(0, imageWidth),
        top = floor((max(top, fittedTop) - fittedTop) * toImageY).toInt().coerceIn(0, imageHeight),
        right = ceil((min(right, fittedLeft + fittedWidth) - fittedLeft) * toImageX).toInt().coerceIn(0, imageWidth),
        bottom = ceil((min(bottom, fittedTop + fittedHeight) - fittedTop) * toImageY).toInt().coerceIn(0, imageHeight),
    )
    return if (rect.width > 0 && rect.height > 0) rect else null
}

/**
 * Power-of-two subsampling for decoding [imagePixels] of the photo to fill [screenPixels] on
 * screen. Allows the result to be up to a quarter softer than the screen, which keeps the bitmap
 * well under the size of two screens.
 */
fun regionSampleSize(imagePixels: Int, screenPixels: Float): Int {
    if (imagePixels <= 0 || screenPixels <= 0f) return 1
    val limit = imagePixels / screenPixels * 4f / 3f
    var sample = 1
    while (sample * 2 <= limit) sample *= 2
    return sample
}

/**
 * Maps [rect], measured on the upright photo, to the stored pixel grid the decoder reads, for a
 * photo whose stored pixels are [storedWidth] by [storedHeight] and turn [rotation] degrees
 * clockwise to display upright.
 */
fun uprightToStored(rect: PixelRect, rotation: Int, storedWidth: Int, storedHeight: Int): PixelRect {
    val w = storedWidth
    val h = storedHeight
    return when (rotation) {
        90 -> PixelRect(rect.top, h - rect.right, rect.bottom, h - rect.left)
        180 -> PixelRect(w - rect.right, h - rect.bottom, w - rect.left, h - rect.top)
        270 -> PixelRect(w - rect.bottom, rect.left, w - rect.top, rect.right)
        else -> rect
    }
}

/** True when two width-over-height ratios are the same shape, give or take rounding. */
fun sameAspect(widthA: Float, heightA: Float, widthB: Float, heightB: Float): Boolean {
    if (widthA <= 0f || heightA <= 0f || widthB <= 0f || heightB <= 0f) return false
    val a = widthA / heightA
    val b = widthB / heightB
    return abs(a - b) / b < 0.02f
}
