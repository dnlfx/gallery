package com.dnlfx.gallery.ui.viewer

import kotlin.math.max

/**
 * How a pinched video sits on screen: [scale] times its fitted size, shifted by [x] and [y]
 * pixels from the centre. The video is always centred in its container when not zoomed.
 */
data class VideoZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > 1f

    /**
     * Zooms by [zoom] around a focus point given relative to the container's centre, then moves
     * by [panX], [panY], keeping the point under the fingers in place as they pinch. The video
     * can't shrink below its fitted size or be dragged past its own edges.
     */
    fun transformed(
        zoom: Float,
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        content: Size,
        container: Size,
    ): VideoZoom {
        val newScale = (scale * zoom).coerceIn(1f, MAX_VIDEO_ZOOM)
        val applied = newScale / scale
        // A point at (focus - offset) / scale in the video stays under the focus.
        val newX = focusX * (1f - applied) + x * applied + panX
        val newY = focusY * (1f - applied) + y * applied + panY
        return VideoZoom(newScale, newX, newY).clampedTo(content, container)
    }

    /** Shifts by [panX], [panY] while zoomed, within the video's edges. */
    fun panned(panX: Float, panY: Float, content: Size, container: Size): VideoZoom =
        copy(x = x + panX, y = y + panY).clampedTo(content, container)

    /** Snaps back to the fitted size when a pinch ends barely zoomed in. */
    fun settled(): VideoZoom = if (scale < SNAP_BACK_SCALE) VideoZoom() else this

    private fun clampedTo(content: Size, container: Size): VideoZoom {
        val maxX = max(0f, (content.width * scale - container.width) / 2f)
        val maxY = max(0f, (content.height * scale - container.height) / 2f)
        return copy(x = x.coerceIn(-maxX, maxX), y = y.coerceIn(-maxY, maxY))
    }

    /** A width and height in pixels; kept free of Compose so the math is easy to test. */
    data class Size(val width: Float, val height: Float)
}

private const val MAX_VIDEO_ZOOM = 5f
private const val SNAP_BACK_SCALE = 1.05f
