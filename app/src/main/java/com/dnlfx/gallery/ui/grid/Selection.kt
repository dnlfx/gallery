package com.dnlfx.gallery.ui.grid

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Keeps the selected item ids across rotation and process death. */
val SelectionSaver: Saver<Set<Long>, LongArray> = Saver(save = { it.toLongArray() }, restore = { it.toSet() })

/**
 * What a slide across the grid selects. The slide started on [anchor] and is now on [current]
 * (both indexes into [ids], which is in grid order). Everything between them is added to what
 * was selected [before] the slide, or taken out of it when the slide started on an item that was
 * already selected. Sliding back shrinks the range again.
 */
fun dragSelection(before: Set<Long>, ids: List<Long>, anchor: Int, current: Int, adding: Boolean): Set<Long> {
    if (ids.isEmpty()) return before
    val from = min(anchor, current).coerceIn(0, ids.lastIndex)
    val to = max(anchor, current).coerceIn(0, ids.lastIndex)
    val range = ids.subList(from, to + 1)
    return if (adding) before + range else before - range.toSet()
}

/**
 * Press and hold an item to select it, then, without lifting, slide to select everything up to
 * where the finger is, like Photos. Near the top or bottom edge the grid scrolls on its own.
 * A quick slide still scrolls the grid as usual, and taps go to the cells.
 *
 * [ids] are the grid's items in order; cells must be keyed by these ids.
 */
@Composable
fun rememberDragToSelect(
    gridState: LazyGridState,
    ids: List<Long>,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
    contentPadding: PaddingValues,
): Modifier {
    val latestIds by rememberUpdatedState(ids)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnChange by rememberUpdatedState(onSelectedChange)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val maxSpeed = with(density) { AUTO_SCROLL_MAX_SPEED.toPx() }
    val drag = remember { DragState() }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    // Item offsets are measured from inside the content padding.
    val paddingLeft = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val paddingTop = with(density) { contentPadding.calculateTopPadding().toPx() }
    val paddingBottom = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val latestPadding by rememberUpdatedState(Triple(paddingLeft, paddingTop, paddingBottom))

    fun idAt(position: Offset): Long? {
        val (left, top) = latestPadding
        val x = (position.x - left).toInt()
        val y = (position.y - top).toInt()
        return gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            x >= info.offset.x && x < info.offset.x + info.size.width &&
                y >= info.offset.y && y < info.offset.y + info.size.height
        }?.key as? Long
    }

    // Extends the selection to the item under the finger, if it's moved to a new one.
    fun extendTo(position: Offset) {
        val anchor = drag.anchor
        if (anchor < 0) return
        val id = idAt(position) ?: return
        if (id == drag.currentId) return
        val index = latestIds.indexOf(id)
        if (index < 0) return
        drag.currentId = id
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        latestOnChange(dragSelection(drag.before, latestIds, anchor, index, drag.adding))
    }

    val autoScrolling by remember { derivedStateOf { autoScrollSpeed != 0f } }
    LaunchedEffect(autoScrolling) {
        while (autoScrollSpeed != 0f) {
            gridState.scrollBy(autoScrollSpeed)
            // Items move under a finger held still near the edge, so keep extending.
            drag.position?.let(::extendTo)
            withFrameNanos {}
        }
    }

    return Modifier.pointerInput(gridState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Gives up if the finger lifts first, or if the grid starts scrolling.
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val id = idAt(longPress.position) ?: return@awaitEachGesture
            val index = latestIds.indexOf(id)
            if (index < 0) return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val before = latestSelected
            drag.anchor = index
            drag.currentId = id
            drag.before = before
            drag.adding = id !in before
            drag.position = longPress.position
            latestOnChange(dragSelection(before, latestIds, index, index, drag.adding))
            longPress.consume()

            // From here the finger belongs to the selection: take its moves before the grid can
            // scroll with them, and its lift before the cell can count it as a tap.
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                    drag.position = change.position
                    extendTo(change.position)
                    val (_, top, bottom) = latestPadding
                    val y = change.position.y
                    val bottomEdge = size.height - bottom
                    autoScrollSpeed = when {
                        y < top + edge -> -maxSpeed * ((top + edge - y) / edge).coerceAtMost(1f)
                        y > bottomEdge - edge -> maxSpeed * ((y - (bottomEdge - edge)) / edge).coerceAtMost(1f)
                        else -> 0f
                    }
                }
            } finally {
                autoScrollSpeed = 0f
                drag.anchor = -1
                drag.position = null
            }
        }
    }
}

private class DragState {
    var anchor = -1
    var currentId = -1L
    var before: Set<Long> = emptySet()
    var adding = true
    var position: Offset? = null
}

private val AUTO_SCROLL_EDGE = 64.dp
private val AUTO_SCROLL_MAX_SPEED = 16.dp
