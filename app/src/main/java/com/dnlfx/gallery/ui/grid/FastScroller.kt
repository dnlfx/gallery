package com.dnlfx.gallery.ui.grid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val HIDE_DELAY_MILLIS = 1_500L

/** Only libraries this many screens long or more get the thumb; shorter ones scroll fine by hand. */
private const val MIN_SCREENS = 4

private val ThumbTouchWidth = 40.dp
private val ThumbHeight = 56.dp

/**
 * A thumb on the right edge that appears while the grid scrolls. Dragging it jumps straight
 * through a large library, with a bubble naming the month of the photos under it.
 */
@Composable
fun FastScroller(
    gridState: LazyGridState,
    labelFor: (index: Int) -> String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val metrics by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            ScrollMetrics(gridState.firstVisibleItemIndex, info.visibleItemsInfo.size, info.totalItemsCount)
        }
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    var shown by remember { mutableStateOf(false) }

    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            shown = true
        } else {
            delay(HIDE_DELAY_MILLIS)
            shown = false
        }
    }
    // Each new target replaces the jump still in flight, so the grid keeps up with the finger.
    LaunchedEffect(targetIndex) {
        if (targetIndex >= 0) gridState.scrollToItem(targetIndex)
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollerAlpha")

    val current = metrics
    if (alpha == 0f || current.total < current.visible * MIN_SCREENS || current.visible == 0) return

    BoxWithConstraints(modifier.fillMaxSize().padding(contentPadding)) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - ThumbHeight).toPx() }.coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else scrollFraction(current.first, current.visible, current.total)
        val draggableState = rememberDraggableState { delta ->
            dragFraction = (dragFraction + delta / trackPx).coerceIn(0f, 1f)
            targetIndex = indexForFraction(dragFraction, metrics.visible, metrics.total)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * trackPx).roundToInt()) }
                .alpha(alpha),
        ) {
            if (dragging) {
                labelFor(targetIndex.coerceAtLeast(current.first))?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = ThumbTouchWidth, height = ThumbHeight)
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                        onDragStarted = {
                            dragFraction = scrollFraction(metrics.first, metrics.visible, metrics.total)
                            targetIndex = metrics.first
                            dragging = true
                        },
                        onDragStopped = { dragging = false },
                    ),
            ) {
                Box(
                    Modifier
                        .width(if (dragging) 8.dp else 6.dp)
                        .height(ThumbHeight - 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private data class ScrollMetrics(val first: Int, val visible: Int, val total: Int)

/** How far through the grid the first visible item is, from 0 at the top to 1 at the bottom. */
fun scrollFraction(firstVisible: Int, visibleCount: Int, total: Int): Float {
    val scrollable = total - visibleCount
    if (scrollable <= 0) return 0f
    return (firstVisible.toFloat() / scrollable).coerceIn(0f, 1f)
}

/** The item to scroll to so the thumb sits at [fraction]: the inverse of [scrollFraction]. */
fun indexForFraction(fraction: Float, visibleCount: Int, total: Int): Int {
    if (total <= 0) return 0
    val scrollable = (total - visibleCount).coerceAtLeast(0)
    return (fraction.coerceIn(0f, 1f) * scrollable).roundToInt().coerceIn(0, total - 1)
}
