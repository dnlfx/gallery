package com.dnlfx.gallery.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.thumbnail.MediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_DECODE_PX = 4096
private const val TILE_SETTLE_MILLIS = 120L

/**
 * A photo that fits the screen and can be pinch-zoomed, panned and double-tapped. At normal size
 * single-finger drags are left alone so the surrounding pager can swipe to the next item.
 *
 * The photo is first shown at about twice the screen size. Zoomed in past that, the part on
 * screen is decoded again from the original at full resolution and drawn over it.
 */
@Composable
fun ZoomableImage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var intrinsic by remember { mutableStateOf(Size.Unspecified) }
        var fullLoaded by remember { mutableStateOf(false) }
        // Animated images keep playing instead of getting still full-resolution tiles.
        var tilesAllowed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Leaving a page resets its zoom, like the Photos app.
        LaunchedEffect(isCurrentPage) {
            if (!isCurrentPage) {
                scale = 1f
                offset = Offset.Zero
            }
        }

        fun fitted(): Size {
            val source = when {
                intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f -> intrinsic
                item.width > 0 && item.height > 0 -> Size(item.width.toFloat(), item.height.toFloat())
                else -> return container
            }
            val ratio = min(container.width / source.width, container.height / source.height)
            return Size(source.width * ratio, source.height * ratio)
        }

        fun clamp(candidate: Offset, atScale: Float): Offset {
            val content = fitted()
            val maxX = max(0f, (content.width * atScale - container.width) / 2f)
            val maxY = max(0f, (content.height * atScale - container.height) / 2f)
            return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
        }

        // Keeps the content point under [focus] (relative to the center) in place while scaling.
        fun offsetFor(newScale: Float, focus: Offset, pan: Offset = Offset.Zero): Offset =
            focus - (focus - offset) * (newScale / scale) + pan

        val context = LocalContext.current

        // Only the page on screen keeps the original open for tiles.
        val regionSource by produceState<RegionSource?>(null, item.uri, isCurrentPage, tilesAllowed) {
            if (!isCurrentPage || !tilesAllowed) return@produceState
            val source = withContext(Dispatchers.IO) {
                RegionSource.open(context.contentResolver, item.uri, item.orientationDegrees)
            }
            value = source
            try {
                awaitCancellation()
            } finally {
                value = null
                // Waits for a decode in progress, so keep it off the main thread.
                source?.let { Dispatchers.IO.asExecutor().execute(it::close) }
            }
        }
        var tile by remember { mutableStateOf<RegionTile?>(null) }
        LaunchedEffect(regionSource, container) {
            tile = null
            val source = regionSource ?: return@LaunchedEffect
            snapshotFlow {
                val content = fitted()
                ZoomViewport(
                    containerWidth = container.width,
                    containerHeight = container.height,
                    fittedWidth = content.width,
                    fittedHeight = content.height,
                    scale = scale,
                    offsetX = offset.x,
                    offsetY = offset.y,
                )
            }.collectLatest { viewport ->
                // Wait for the gesture to pause; the screen-sized image covers in the meantime.
                delay(TILE_SETTLE_MILLIS)
                tile = regionTileFor(source, viewport, baseWidth = intrinsic.width)
            }
        }

        val request = remember(item.uri, container) {
            ImageRequest.Builder(context)
                .data(item.uri)
                // Decode at up to twice the screen size so zooming in stays sharp.
                .size(
                    Dimension.Pixels(min(MAX_DECODE_PX, (container.width * 2).toInt().coerceAtLeast(1))),
                    Dimension.Pixels(min(MAX_DECODE_PX, (container.height * 2).toInt().coerceAtLeast(1))),
                )
                .precision(Precision.INEXACT)
                .build()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(item.id, container) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            val focus = tap - Offset(container.width / 2f, container.height / 2f)
                            val startScale = scale
                            val startOffset = offset
                            val endScale = if (scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                            val endOffset = if (endScale == 1f) {
                                Offset.Zero
                            } else {
                                clamp(focus - focus * (endScale / startScale), endScale)
                            }
                            scope.launch {
                                animate(0f, 1f) { t, _ ->
                                    scale = startScale + (endScale - startScale) * t
                                    offset = startOffset + (endOffset - startOffset) * t
                                }
                            }
                        },
                    )
                }
                .pointerInput(item.id, container) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            val pinching = pressed >= 2
                            if (!pinching && scale <= 1f) continue

                            val zoom = if (pinching) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val focus = if (centroid.isSpecified) {
                                centroid - Offset(container.width / 2f, container.height / 2f)
                            } else {
                                Offset.Zero
                            }
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val newOffset = clamp(offsetFor(newScale, focus, pan), newScale)

                            // Pinned against a side while panning sideways: let the pager take over.
                            val atEdge = !pinching && newOffset.x == offset.x && abs(pan.x) > abs(pan.y)
                            scale = newScale
                            offset = if (newScale == 1f) Offset.Zero else newOffset
                            if (!atEdge) event.changes.forEach { it.consume() }
                        } while (true)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            if (!fullLoaded) {
                AsyncImage(
                    model = MediaThumbnail(item.uri, item.dateModifiedSeconds),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AsyncImage(
                model = request,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    intrinsic = state.painter.intrinsicSize
                    fullLoaded = true
                    tilesAllowed = state.result.image is BitmapImage
                },
                modifier = Modifier.fillMaxSize(),
            )
            tile?.let { current ->
                Canvas(Modifier.fillMaxSize()) {
                    val content = fitted()
                    val toLayoutX = content.width / current.imageWidth
                    val toLayoutY = content.height / current.imageHeight
                    translate(
                        left = (size.width - content.width) / 2f + current.rect.left * toLayoutX,
                        top = (size.height - content.height) / 2f + current.rect.top * toLayoutY,
                    ) {
                        scale(
                            scaleX = current.rect.width * toLayoutX / current.bitmap.width,
                            scaleY = current.rect.height * toLayoutY / current.bitmap.height,
                            pivot = Offset.Zero,
                        ) {
                            drawImage(current.bitmap)
                        }
                    }
                }
            }
        }
    }
}

/** A full-resolution piece of the photo: [rect] of an [imageWidth] by [imageHeight] original. */
private class RegionTile(
    val bitmap: ImageBitmap,
    val rect: PixelRect,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** Decodes the part of the photo on screen, or returns null when the base image is sharp enough. */
private suspend fun regionTileFor(source: RegionSource, viewport: ZoomViewport, baseWidth: Float): RegionTile? {
    val onScreenWidth = viewport.fittedWidth * viewport.scale
    // Zoomed no further than the base image's own pixels, or the original has no more to give.
    if (baseWidth <= 0f || onScreenWidth <= baseWidth * 1.1f || source.width <= baseWidth * 1.1f) return null
    // Guards against a wrong rotation from MediaStore: never draw a tile that doesn't line up.
    val fitsShape = sameAspect(
        source.width.toFloat(),
        source.height.toFloat(),
        viewport.fittedWidth,
        viewport.fittedHeight,
    )
    if (!fitsShape) return null
    val rect = visibleImageRect(viewport, source.width, source.height) ?: return null
    val sample = regionSampleSize(rect.width, screenPixels = rect.width * onScreenWidth / source.width)
    val bitmap = withContext(Dispatchers.IO) { source.decode(rect, sample) } ?: return null
    return RegionTile(bitmap.asImageBitmap(), rect, source.width, source.height)
}
