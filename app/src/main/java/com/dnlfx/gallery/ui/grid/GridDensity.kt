package com.dnlfx.gallery.ui.grid

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Cell widths a pinch steps through, biggest first: about 3, 4, 6 and 8 columns on an upright phone. */
private val CellWidths = listOf(137.dp, 103.dp, 69.dp, 51.5.dp)
private const val DEFAULT_LEVEL = 1
private const val PREFS = "grid"
private const val KEY_LEVEL = "density"

/** How much a pinch has to spread or close before the grid changes by one step. */
private const val ZOOM_STEP = 1.3f

fun gridCellsFor(level: Int): GridCells = ColumnsOfWidth(CellWidths[level.coerceIn(0, CellWidths.lastIndex)])

fun zoomedLevel(level: Int, zoomIn: Boolean): Int =
    (if (zoomIn) level - 1 else level + 1).coerceIn(0, CellWidths.lastIndex)

/** The grid's density, kept on the device across launches. */
@Composable
fun rememberGridLevel(): MutableIntState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val level = remember {
        mutableIntStateOf(prefs.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(0, CellWidths.lastIndex))
    }
    LaunchedEffect(level) {
        snapshotFlow { level.intValue }.collect { prefs.edit().putInt(KEY_LEVEL, it).apply() }
    }
    return level
}

/**
 * Two-finger pinch on the grid: spreading the fingers makes the cells bigger, closing them makes
 * them smaller, one step of [level] at a time. A pinch stops the grid from scrolling and its cells
 * from opening.
 */
fun Modifier.pinchToResize(level: MutableIntState): Modifier = this.then(
    Modifier.pointerInput(level) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    when {
                        zoom > ZOOM_STEP -> {
                            level.intValue = zoomedLevel(level.intValue, zoomIn = true)
                            zoom = 1f
                        }
                        zoom < 1f / ZOOM_STEP -> {
                            level.intValue = zoomedLevel(level.intValue, zoomIn = false)
                            zoom = 1f
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    },
)

/** As many columns as fit at roughly [cellWidth] each, so landscape gets more. */
private class ColumnsOfWidth(private val cellWidth: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = (availableSize / cellWidth.toPx()).roundToInt().coerceAtLeast(1)
        val density = this
        return with(GridCells.Fixed(count)) { density.calculateCrossAxisCellSizes(availableSize, spacing) }
    }

    override fun equals(other: Any?): Boolean = other is ColumnsOfWidth && other.cellWidth == cellWidth

    override fun hashCode(): Int = cellWidth.hashCode()
}
