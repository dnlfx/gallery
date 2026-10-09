package com.dnlfx.gallery.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.thumbnail.viewerThumbnailRequest
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

/** Pulled down this fraction of the screen, or flung faster than this, the viewer closes on release. */
internal const val DISMISS_DISTANCE = 0.15f
internal const val DISMISS_FLING_DP_PER_SECOND = 1_000
internal const val DISMISS_SHRINK = 0.3f
internal const val DISMISS_MILLIS = 150

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
    onDismissProgress: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // How far a downward slide at normal size has pulled the photo towards closing.
        var dismissOffset by remember { mutableFloatStateOf(0f) }
        val latestOnDismissProgress by rememberUpdatedState(onDismissProgress)
        val latestOnDismiss by rememberUpdatedState(onDismiss)
        fun dismissProgress() = (dismissOffset / container.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
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
                dismissOffset = 0f
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
                // Each of these is tens of megabytes; a few would push every grid thumbnail out of
                // the memory cache, and the grid would reload them all on the way back. The pages
                // either side stay loaded anyway, and the thumbnail covers a re-decode.
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
        }
        val thumbnailRequest = remember(item.uri, item.dateModifiedSeconds) { viewerThumbnailRequest(context, item) }

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
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // At normal size, a slide that starts downwards closes the viewer.
                        var slide = Offset.Zero
                        var decided = false
                        var dismissing = false
                        val velocity = VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        do {
                            val event = awaitPointerEvent()
                            if (!dismissing && event.changes.any { it.isConsumed }) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            val pinching = pressed >= 2
                            if (dismissing) {
                                event.changes.firstOrNull { it.id == down.id }?.let {
                                    velocity.addPosition(it.uptimeMillis, it.position)
                                }
                                dismissOffset = (dismissOffset + event.calculatePan().y).coerceAtLeast(0f)
                                latestOnDismissProgress(dismissProgress())
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (!pinching && scale <= 1f) {
                                if (!decided) {
                                    slide += event.calculatePan()
                                    if (slide.getDistance() > viewConfiguration.touchSlop) {
                                        decided = true
                                        // Clearly downwards; anything more sideways is the pager's swipe.
                                        dismissing = slide.y > 0f && slide.y > abs(slide.x) * 1.5f
                                        if (dismissing) event.changes.forEach { it.consume() }
                                    }
                                }
                                continue
                            }

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

                        if (dismissing) {
                            val flung = velocity.calculateVelocity().y > DISMISS_FLING_DP_PER_SECOND.dp.toPx()
                            val close = flung || dismissProgress() > DISMISS_DISTANCE
                            scope.launch {
                                animate(
                                    initialValue = dismissOffset,
                                    targetValue = if (close) container.height else 0f,
                                    animationSpec = if (close) tween(DISMISS_MILLIS) else spring(),
                                ) { value, _ ->
                                    dismissOffset = value
                                    latestOnDismissProgress(dismissProgress())
                                }
                                if (close) latestOnDismiss()
                            }
                        }
                    }
                }
                .graphicsLayer {
                    // Shrinks a little as it's pulled down, so it reads as going back to the grid.
                    val shrink = 1f - DISMISS_SHRINK * dismissProgress()
                    scaleX = scale * shrink
                    scaleY = scale * shrink
                    translationX = offset.x
                    translationY = offset.y + dismissOffset
                },
        ) {
            if (!fullLoaded) {
                AsyncImage(
                    model = thumbnailRequest,
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
