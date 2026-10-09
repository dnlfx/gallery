package com.dnlfx.gallery.ui.grid

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.thumbnail.MediaThumbnail
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(
    state: GridState,
    limitedAccess: Boolean,
    onRequestFullAccess: () -> Unit,
    onItemClick: (index: Int, item: MediaItem) -> Unit,
    onFilterSelected: (MediaFilter) -> Unit,
    onSortSelected: (MediaSort) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // The overlap changes on every frame of a scroll; only crossing the threshold matters, so the
    // bar recomposes once when the grid slides under it rather than on every frame.
    val scrolledUnder by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.overlappedFraction > 0.01f }
    }
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Keep thumbnails and the fast scroller clear of the camera cutout and of the navigation
        // buttons, which sit at the side of the screen when the phone is turned sideways.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // The chips sit under the app bar and share its colour, which deepens once the grid
            // scrolls beneath it.
            val barColor by animateColorAsState(
                targetValue = if (scrolledUnder) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "barColor",
            )
            Column(Modifier.background(barColor)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name))
                            if (state is GridState.Loaded && state.items.isNotEmpty()) {
                                val count = state.items.size
                                Text(
                                    text = resources.getQuantityString(
                                        R.plurals.item_count,
                                        count,
                                        NumberFormat.getIntegerInstance().format(count),
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (state is GridState.Loaded && state.allItems.isNotEmpty()) {
                            SortMenu(
                                sort = state.sort,
                                onSelect = { sort ->
                                    scope.launch { gridState.scrollToItem(0) }
                                    onSortSelected(sort)
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barColor,
                        scrolledContainerColor = barColor,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                if (state is GridState.Loaded && state.allItems.isNotEmpty()) {
                    FilterRow(
                        filters = state.filters,
                        selected = state.filter,
                        onSelect = { filter ->
                            // A new filter starts from the newest item, not partway down the old list.
                            scope.launch { gridState.scrollToItem(0) }
                            onFilterSelected(filter)
                        },
                    )
                }
            }
        },
    ) { padding ->
        when (state) {
            GridState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            is GridState.Loaded -> if (state.items.isEmpty() && !limitedAccess) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text(
                        stringResource(
                            if (state.filter == MediaFilter.ALL) R.string.empty_library else R.string.empty_filter,
                        ),
                    )
                }
            } else {
                val media = state.items
                val entries = state.sections.entries
                val bannerCount = if (limitedAccess) 1 else 0
                val sort = state.sort
                val context = LocalContext.current
                val monthFormat = remember {
                    val locale = Locale.getDefault()
                    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale)
                }
                val level = rememberGridLevel()
                Box(Modifier.fillMaxSize().pinchToResize(level)) {
                    LazyVerticalGrid(
                        state = gridState,
                        // Four columns on a phone held upright by default; pinch for 3, 6 or 8.
                        columns = gridCellsFor(level.intValue),
                        contentPadding = padding,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (limitedAccess) {
                            item(span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                                LimitedAccessBanner(onRequestFullAccess)
                            }
                        }
                        items(
                            count = entries.size,
                            key = { index ->
                                when (val entry = entries[index]) {
                                    is GridEntry.Header -> "month-${entry.month}"
                                    is GridEntry.Media -> media[entry.mediaIndex].id
                                }
                            },
                            span = { index ->
                                if (entries[index] is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                            },
                            contentType = { index -> if (entries[index] is GridEntry.Header) "month" else "media" },
                        ) { index ->
                            when (val entry = entries[index]) {
                                is GridEntry.Header -> MonthHeader(
                                    remember(entry.month) { monthFormat.format(Date(entry.millis)) },
                                )
                                is GridEntry.Media -> {
                                    val item = media[entry.mediaIndex]
                                    MediaCell(item = item, onClick = { onItemClick(entry.mediaIndex, item) })
                                }
                            }
                        }
                    }
                    FastScroller(
                        gridState = gridState,
                        labelFor = { index ->
                            when (val entry = entries.getOrNull(index - bannerCount)) {
                                is GridEntry.Header -> monthFormat.format(Date(entry.millis))
                                is GridEntry.Media -> media.getOrNull(entry.mediaIndex)?.let { item ->
                                    when (sort.field) {
                                        SortField.MODIFIED, SortField.TAKEN ->
                                            monthFormat.format(Date(sort.monthSeconds(item) * 1000))
                                        SortField.NAME -> item.displayName?.firstOrNull()?.uppercase()
                                        SortField.SIZE -> Formatter.formatShortFileSize(context, item.sizeBytes)
                                    }
                                }
                                null -> null
                            }
                        },
                        contentPadding = padding,
                    )
                }
            }
        }
    }
}

/** The sort button in the app bar and its menu: what to sort by, then which way round. */
@Composable
private fun SortMenu(sort: MediaSort, onSelect: (MediaSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(GridIcons.Sort, contentDescription = stringResource(R.string.sort_button))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { field ->
                SortMenuItem(
                    label = stringResource(field.label),
                    checked = field == sort.field,
                    // A new field starts in its usual order: newest, A to Z, or largest first.
                    onClick = { open = false; onSelect(MediaSort(field)) },
                )
            }
            HorizontalDivider()
            listOf(false to sort.field.naturalOrder, true to sort.field.reversedOrder).forEach { (reversed, label) ->
                SortMenuItem(
                    label = stringResource(label),
                    checked = sort.reversed == reversed,
                    onClick = { open = false; onSelect(sort.copy(reversed = reversed)) },
                )
            }
        }
    }
}

@Composable
private fun SortMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) },
        modifier = Modifier.semantics { selected = checked },
    )
}

/** One chip per filter, scrolling sideways. All is first and starts selected. */
@Composable
private fun FilterRow(filters: List<MediaFilter>, selected: MediaFilter, onSelect: (MediaFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(bottom = 4.dp),
    ) {
        items(filters, key = { it.name }) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                // Tapping the selected filter again goes back to everything.
                onClick = { onSelect(if (isSelected) MediaFilter.ALL else filter) },
                label = { Text(stringResource(filter.label)) },
            )
        }
    }
}

@Composable
private fun MonthHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun MediaCell(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(item.uri, item.dateModifiedSeconds) {
        ImageRequest.Builder(context)
            .data(MediaThumbnail(item.uri, item.dateModifiedSeconds))
            // Only thumbnails decoded fresh fade in; ones already in memory appear at once.
            .crossfade(CROSSFADE_MILLIS)
            .build()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            // Worked out only when something reads it, such as TalkBack, not for every cell a
            // fling passes.
            .semantics { contentDescription = mediaDescription(context, item) },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.type == MediaType.VIDEO) {
            Text(
                text = item.durationMillis?.let(::formatDuration) ?: "",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 4f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** What TalkBack reads for a cell: "Photo, 1 October 2026" or "Video, 0:32, 1 October 2026". */
private fun mediaDescription(context: Context, item: MediaItem): String {
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG)
        .format(Date(item.dateModifiedSeconds * 1000))
    return when (item.type) {
        MediaType.IMAGE -> context.getString(R.string.cell_photo, date)
        MediaType.VIDEO -> context.getString(
            R.string.cell_video,
            formatDuration(item.durationMillis ?: 0L),
            date,
        )
    }
}

private const val CROSSFADE_MILLIS = 150

@Composable
private fun LimitedAccessBanner(onRequestFullAccess: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.limited_access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestFullAccess) { Text(stringResource(R.string.limited_access_action)) }
    }
}
