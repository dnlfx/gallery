package com.dnlfx.gallery.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

/**
 * The trim bar: frames from the whole video, with a handle at each end of the part to keep.
 * Drag a handle to move that end (the video shows the frame under it); tap elsewhere to look at
 * that moment. The white line is where playback is.
 */
@Composable
fun TrimBar(
    durationMillis: Long,
    range: TrimRange,
    positionMillis: Long,
    frames: List<ImageBitmap?>,
    onRangeChange: (TrimRange, TrimHandle) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onSeek: (Long) -> Unit,
    label: String,
    stateLabel: String,
    modifier: Modifier = Modifier,
) {
    val latestRange by rememberUpdatedState(range)
    val latestDuration by rememberUpdatedState(durationMillis)
    val latestOnRangeChange by rememberUpdatedState(onRangeChange)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnSeek by rememberUpdatedState(onSeek)
    Box(
        modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT + HANDLE_OVERHANG * 2)
            .semantics {
                contentDescription = label
                stateDescription = stateLabel
                if (durationMillis > 0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = range.startMillis.toFloat(),
                        range = 0f..durationMillis.toFloat(),
                    )
                }
            }
            .pointerInput(Unit) {
                val inset = SIDE_INSET.toPx()
                val reach = HANDLE_REACH.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val duration = latestDuration
                    if (duration <= 0) return@awaitEachGesture
                    val trackWidth = size.width - 2 * inset
                    fun xOf(millis: Long) = inset + trackWidth * millis / duration.toFloat()
                    fun millisAt(x: Float) = ((x - inset) / trackWidth * duration).roundToLong().coerceIn(0L, duration)
                    val start = latestRange
                    val handle = trimHandleAt(down.position.x, xOf(start.startMillis), xOf(start.endMillis), reach)
                    down.consume()
                    if (handle == null) {
                        latestOnSeek(millisAt(down.position.x).coerceIn(start.startMillis, start.endMillis))
                        return@awaitEachGesture
                    }
                    latestOnDragStart()
                    val minLength = minTrimLength(duration)
                    // Keep the finger's offset from the handle, so grabbing it slightly off-center
                    // doesn't make it jump.
                    val grabOffset = down.position.x - xOf(if (handle == TrimHandle.Start) start.startMillis else start.endMillis)
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            val millis = millisAt(change.position.x - grabOffset)
                            val current = latestRange
                            val moved = when (handle) {
                                TrimHandle.Start -> current.withStart(millis, minLength)
                                TrimHandle.End -> current.withEnd(millis, duration, minLength)
                            }
                            if (moved != current) latestOnRangeChange(moved, handle)
                        }
                    } finally {
                        latestOnDragEnd()
                    }
                }
            },
    ) {
        Row(
            Modifier
                .padding(horizontal = SIDE_INSET, vertical = HANDLE_OVERHANG)
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.1f)),
        ) {
            frames.forEach { frame ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (durationMillis <= 0) return@drawBehind
                    val inset = SIDE_INSET.toPx()
                    val overhang = HANDLE_OVERHANG.toPx()
                    val trackWidth = size.width - 2 * inset
                    fun xOf(millis: Long) = inset + trackWidth * millis / durationMillis.toFloat()
                    val startX = xOf(range.startMillis)
                    val endX = xOf(range.endMillis)
                    val top = overhang
                    val bottom = size.height - overhang
                    val dim = Color.Black.copy(alpha = 0.6f)
                    drawRect(dim, Offset(inset, top), Size(startX - inset, bottom - top))
                    drawRect(dim, Offset(endX, top), Size(size.width - inset - endX, bottom - top))

                    // The kept part: a frame with a grip at each end.
                    val accent = Color(0xFFFFD54F)
                    val edge = 3.dp.toPx()
                    val handleWidth = HANDLE_WIDTH.toPx()
                    drawRect(accent, Offset(startX, top), Size(endX - startX, edge))
                    drawRect(accent, Offset(startX, bottom - edge), Size(endX - startX, edge))
                    val radius = CornerRadius(4.dp.toPx())
                    drawRoundRect(accent, Offset(startX - handleWidth, 0f), Size(handleWidth, size.height), radius)
                    drawRoundRect(accent, Offset(endX, 0f), Size(handleWidth, size.height), radius)
                    val grip = Size(2.dp.toPx(), size.height / 3f)
                    val gripTop = (size.height - grip.height) / 2f
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(startX - (handleWidth + grip.width) / 2f, gripTop), grip)
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(endX + (handleWidth - grip.width) / 2f, gripTop), grip)

                    val playX = xOf(positionMillis.coerceIn(0L, durationMillis))
                    drawLine(Color.White, Offset(playX, top), Offset(playX, bottom), 2.dp.toPx())
                },
        )
    }
}

private val BAR_HEIGHT = 56.dp
private val HANDLE_OVERHANG = 4.dp
private val HANDLE_WIDTH = 14.dp

/** Room at each side so the handles can sit outside the frames at the very start and end. */
private val SIDE_INSET = 20.dp
private val HANDLE_REACH = 28.dp
