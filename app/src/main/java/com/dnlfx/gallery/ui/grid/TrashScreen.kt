package com.dnlfx.gallery.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.ui.rememberMediaRequests
import java.text.NumberFormat
import kotlin.math.ceil

/**
 * The system trash: everything moved there from this app or any other, most recent first, with
 * the days each has left. Tap items to select them, or hold and slide as in the grid, then
 * restore them or delete them for good. [items] is null while the trash is being read.
 */
@Composable
fun TrashScreen(items: List<MediaItem>?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val requests = rememberMediaRequests()
    val gridState = rememberLazyGridState()
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<Long>()) }
    // Restored or deleted items leave the selection with the trash.
    LaunchedEffect(items) {
        if (items != null && selection.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selection = selection.filterTo(HashSet()) { it in ids }
        }
    }
    val selecting = selection.isNotEmpty()
    BackHandler { if (selecting) selection = emptySet() else onClose() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val chosen = remember(items, selection) { items.orEmpty().filter { it.id in selection } }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LargeFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_clear))
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trash_back))
                        }
                    }
                },
                title = {
                    if (selecting) {
                        val count = selection.size
                        Text(
                            LocalContext.current.resources.getQuantityString(
                                R.plurals.selected_count,
                                count,
                                NumberFormat.getIntegerInstance().format(count),
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.trash_title))
                    }
                },
                actions = {
                    if (!selecting && !items.isNullOrEmpty()) {
                        TextButton(onClick = { selection = items.mapTo(HashSet()) { it.id } }) {
                            Text(stringResource(R.string.trash_select_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            items == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                LoadingIndicator(Modifier.size(64.dp))
            }
            items.isEmpty() -> EmptyState(
                title = stringResource(R.string.trash_empty),
                body = stringResource(R.string.trash_note),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> {
                val level = rememberGridLevel()
                val ids = remember(items) { items.map { it.id } }
                val layoutDirection = LocalLayoutDirection.current
                val gridPadding = remember(padding, selecting, layoutDirection) {
                    PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding() + if (selecting) 96.dp else 0.dp,
                    )
                }
                val dragToSelect = rememberDragToSelect(
                    gridState = gridState,
                    ids = ids,
                    selected = selection,
                    onSelectedChange = { selection = it },
                    contentPadding = gridPadding,
                )
                val context = LocalContext.current
                val now = remember(items) { System.currentTimeMillis() / 1000 }
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = gridCellsFor(level.intValue),
                        contentPadding = gridPadding,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize().pinchToResize(level).then(dragToSelect),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = "note") {
                            Text(
                                text = stringResource(R.string.trash_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(items, key = { it.id }, contentType = { "media" }) { item ->
                            val selected = item.id in selection
                            val label = remember(item.dateExpiresSeconds, now) {
                                item.dateExpiresSeconds?.let { daysLeftLabel(context, it, now) }
                            }
                            MediaCell(
                                item = item,
                                onClick = { selection = if (selected) selection - item.id else selection + item.id },
                                selecting = selecting,
                                selected = selected,
                                onSelect = { selection = selection + item.id },
                                label = label,
                                modifier = Modifier.animateItem(fadeInSpec = null),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = selecting,
                        enter = slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeIn(),
                        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = padding.calculateBottomPadding() + 16.dp),
                    ) {
                        HorizontalFloatingToolbar(
                            expanded = true,
                            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                        ) {
                            TextButton(
                                onClick = { requests.restore(chosen.map { it.uri }) { selection = emptySet() } },
                                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trash_restore))
                            }
                            IconButton(onClick = { requests.deleteForever(chosen.map { it.uri }) { selection = emptySet() } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.trash_delete_forever))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "12 days left", counting a part day as a whole one, or "Last day" when less than one is left. */
private fun daysLeftLabel(context: android.content.Context, expiresSeconds: Long, nowSeconds: Long): String {
    val days = daysLeft(expiresSeconds, nowSeconds)
    return if (days <= 1) {
        context.getString(R.string.trash_last_day)
    } else {
        context.resources.getQuantityString(R.plurals.trash_days_left, days, days)
    }
}

/** Whole days until [expiresSeconds], rounding a part day up, and never below zero. */
fun daysLeft(expiresSeconds: Long, nowSeconds: Long): Int =
    ceil((expiresSeconds - nowSeconds).coerceAtLeast(0) / SECONDS_PER_DAY).toInt()

private const val SECONDS_PER_DAY = 86_400.0
