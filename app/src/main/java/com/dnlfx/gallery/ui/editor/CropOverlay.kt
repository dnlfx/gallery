package com.dnlfx.gallery.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Where a picture of [aspectRatio] (width over height) sits when fitted and centered in a
 * [width] by [height] area with [margin] left free on every side for the crop handles.
 */
fun fittedPicture(width: Float, height: Float, margin: Float, aspectRatio: Float): Rect {
    val roomWidth = max(width - 2 * margin, 1f)
    val roomHeight = max(height - 2 * margin, 1f)
    val fittedWidth = min(roomWidth, roomHeight * aspectRatio)
    val fittedHeight = fittedWidth / aspectRatio
    val left = (width - fittedWidth) / 2f
    val top = (height - fittedHeight) / 2f
    return Rect(left, top, left + fittedWidth, top + fittedHeight)
}

/**
 * The crop box over a picture laid out as [fittedPicture] places it in this composable's bounds:
 * everything outside the box is dimmed, corners and edges resize it and dragging inside moves
 * it. The handles reach a little beyond the picture, which is what [margin] leaves room for.
 * With a [ratio] (width over height, in fractions of the picture) the box keeps that shape.
 * When not [interactive], it only shows the crop, dimming the rest further.
 */
@Composable
fun CropOverlay(
    aspectRatio: Float,
    margin: Dp,
    crop: CropRect,
    ratio: Float?,
    interactive: Boolean,
    onCropChange: (CropRect) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestRatio by rememberUpdatedState(ratio)
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier
            .semantics { contentDescription = label }
            .pointerInput(aspectRatio, interactive) {
                if (!interactive) return@pointerInput
                val marginPx = margin.toPx()
                val reach = HANDLE_REACH.toPx()
                val minSide = MIN_CROP_SIDE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val picture = fittedPicture(size.width.toFloat(), size.height.toFloat(), marginPx, aspectRatio)
                    val start = latestCrop
                    val handle = cropHandleAt(
                        x = down.position.x,
                        y = down.position.y,
                        left = picture.left + start.left * picture.width,
                        top = picture.top + start.top * picture.height,
                        right = picture.left + start.right * picture.width,
                        bottom = picture.top + start.bottom * picture.height,
                        reach = reach,
                    ) ?: return@awaitEachGesture
                    down.consume()
                    dragging = true
                    latestOnDragStart()
                    val locked = latestRatio
                    val minWidth = min(minSide / picture.width, 1f)
                    val minHeight = min(minSide / picture.height, 1f)
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            // Measured from where the finger went down, so edges held at the
                            // picture's border don't drift.
                            val moved = change.position - down.position
                            val dx = moved.x / picture.width
                            val dy = moved.y / picture.height
                            latestOnCropChange(
                                if (locked != null) {
                                    dragCropLocked(start, handle, dx, dy, locked, minWidth, minHeight)
                                } else {
                                    dragCrop(start, handle, dx, dy, minWidth, minHeight)
                                },
                            )
                        }
                    } finally {
                        dragging = false
                        latestOnDragEnd()
                    }
                }
            }
            .drawBehind {
                val picture = fittedPicture(size.width, size.height, margin.toPx(), aspectRatio)
                val box = Rect(
                    left = picture.left + crop.left * picture.width,
                    top = picture.top + crop.top * picture.height,
                    right = picture.left + crop.right * picture.width,
                    bottom = picture.top + crop.bottom * picture.height,
                )
                drawCropBox(picture, box, showGrid = dragging, showHandles = interactive)
            },
    )
}

private fun DrawScope.drawCropBox(picture: Rect, box: Rect, showGrid: Boolean, showHandles: Boolean) {
    val dim = Color.Black.copy(alpha = if (showHandles) 0.6f else 0.85f)
    // The picture outside the box, in four strips around it.
    drawRect(dim, Offset(picture.left, picture.top), Size(picture.width, box.top - picture.top))
    drawRect(dim, Offset(picture.left, box.bottom), Size(picture.width, picture.bottom - box.bottom))
    drawRect(dim, Offset(picture.left, box.top), Size(box.left - picture.left, box.height))
    drawRect(dim, Offset(box.right, box.top), Size(picture.right - box.right, box.height))

    if (showGrid) {
        val grid = Color.White.copy(alpha = 0.5f)
        val line = 1.dp.toPx()
        for (i in 1..2) {
            val x = box.left + box.width * i / 3f
            val y = box.top + box.height * i / 3f
            drawLine(grid, Offset(x, box.top), Offset(x, box.bottom), line)
            drawLine(grid, Offset(box.left, y), Offset(box.right, y), line)
        }
    }
    if (!showHandles) return
    drawRect(Color.White, box.topLeft, box.size, style = Stroke(1.5.dp.toPx()))

    // Thick corner brackets and edge ticks, drawn just outside the box so they don't hide the
    // picture's edge.
    val thick = 4.dp.toPx()
    val half = thick / 2f
    val arm = min(CORNER_ARM.toPx(), min(box.width, box.height) / 2f)
    val outer = Rect(box.left - half, box.top - half, box.right + half, box.bottom + half)
    fun line(from: Offset, to: Offset) = drawLine(Color.White, from, to, thick, cap = StrokeCap.Square)
    line(outer.topLeft, outer.topLeft + Offset(arm, 0f))
    line(outer.topLeft, outer.topLeft + Offset(0f, arm))
    line(outer.topRight, outer.topRight - Offset(arm, 0f))
    line(outer.topRight, outer.topRight + Offset(0f, arm))
    line(outer.bottomLeft, outer.bottomLeft + Offset(arm, 0f))
    line(outer.bottomLeft, outer.bottomLeft - Offset(0f, arm))
    line(outer.bottomRight, outer.bottomRight - Offset(arm, 0f))
    line(outer.bottomRight, outer.bottomRight - Offset(0f, arm))
    val tick = arm / 2f
    if (box.width > 4 * arm) {
        line(Offset(outer.center.x - tick, outer.top), Offset(outer.center.x + tick, outer.top))
        line(Offset(outer.center.x - tick, outer.bottom), Offset(outer.center.x + tick, outer.bottom))
    }
    if (box.height > 4 * arm) {
        line(Offset(outer.left, outer.center.y - tick), Offset(outer.left, outer.center.y + tick))
        line(Offset(outer.right, outer.center.y - tick), Offset(outer.right, outer.center.y + tick))
    }
}

/** How far from a corner or edge a touch still grabs it. */
private val HANDLE_REACH = 28.dp

/** The box can't be made smaller than this on screen. */
private val MIN_CROP_SIDE = 48.dp

private val CORNER_ARM = 20.dp
