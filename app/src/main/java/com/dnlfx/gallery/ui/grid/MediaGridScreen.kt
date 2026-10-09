package com.dnlfx.gallery.ui.grid

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
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
import com.dnlfx.gallery.ui.canChangeMedia
import com.dnlfx.gallery.ui.rememberMediaRequests
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
    /** Opens the trash; null where there's no trash (Android 10). */
    onOpenTrash: (() -> Unit)?,
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
    val requests = rememberMediaRequests()

    // Ids of the selected items. Selecting starts by holding an item and ends once none is left.
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<Long>()) }
    val shown = (state as? GridState.Loaded)?.items
    // Items that left the grid (trashed, unstarred under Favorites, filtered out) leave the selection.
    LaunchedEffect(shown) {
        if (shown != null && selection.isNotEmpty()) {
            val ids = shown.mapTo(HashSet()) { it.id }
            selection = selection.filterTo(HashSet()) { it in ids }
        }
    }
    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { selection = emptySet() }
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
                if (selecting) {
                    // The bar recomposes on every frame of its colour change; walk the library once.
                    val selectedItems = remember(shown, selection) { shown.orEmpty().filter { it.id in selection } }
                    SelectionTopBar(
                        count = selection.size,
                        allFavorite = selectedItems.isNotEmpty() && selectedItems.all { it.isFavorite },
                        onClear = { selection = emptySet() },
                        onFavorite = if (canChangeMedia) {
                            { favorite ->
                                requests.favorite(selectedItems.map { it.uri }, favorite) { selection = emptySet() }
                            }
                        } else {
                            null
                        },
                        onTrash = if (canChangeMedia) {
                            { requests.trash(selectedItems.map { it.uri }) { selection = emptySet() } }
                        } else {
                            null
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = barColor,
                            scrolledContainerColor = barColor,
                        ),
                        scrollBehavior = scrollBehavior,
                    )
                } else TopAppBar(
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
                        if (onOpenTrash != null) MoreMenu(onOpenTrash)
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
                val ids = remember(media) { media.map { it.id } }
                val dragToSelect = rememberDragToSelect(
                    gridState = gridState,
                    ids = ids,
                    selected = selection,
                    onSelectedChange = { selection = it },
                    contentPadding = padding,
                )
                Box(Modifier.fillMaxSize().pinchToResize(level)) {
                    LazyVerticalGrid(
                        state = gridState,
                        // Four columns on a phone held upright by default; pinch for 3, 6 or 8.
                        columns = gridCellsFor(level.intValue),
                        contentPadding = padding,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize().then(dragToSelect),
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
                                    val selected = item.id in selection
                                    MediaCell(
                                        item = item,
                                        onClick = {
                                            when {
                                                !selecting -> onItemClick(entry.mediaIndex, item)
                                                selected -> selection = selection - item.id
                                                else -> selection = selection + item.id
                                            }
                                        },
                                        selecting = selecting,
                                        selected = selected,
                                        onSelect = { selection = selection + item.id },
                                    )
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

/** The app bar while items are selected: how many, and what can be done to all of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    allFavorite: Boolean,
    onClear: () -> Unit,
    onFavorite: ((favorite: Boolean) -> Unit)?,
    onTrash: (() -> Unit)?,
    colors: TopAppBarColors,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_clear))
            }
        },
        title = {
            Text(
                LocalContext.current.resources.getQuantityString(
                    R.plurals.selected_count,
                    count,
                    NumberFormat.getIntegerInstance().format(count),
                ),
            )
        },
        actions = {
            if (onFavorite != null) {
                // Stars them all, or unstars them when every one is already starred.
                IconButton(onClick = { onFavorite(!allFavorite) }) {
                    Icon(
                        if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(
                            if (allFavorite) R.string.favorite_remove else R.string.favorite_add,
                        ),
                    )
                }
            }
            if (onTrash != null) {
                IconButton(onClick = onTrash) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.viewer_delete))
                }
            }
        },
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

/** The overflow button in the app bar. For now it only leads to the trash. */
@Composable
private fun MoreMenu(onOpenTrash: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_title)) },
                onClick = {
                    open = false
                    onOpenTrash()
                },
            )
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

/**
 * One thumbnail. While [selecting], a tap toggles [selected] instead of opening the item, and a
 * selected thumbnail shrinks inside a tinted frame with a tick in its corner. [label], if any,
 * sits in the bottom corner opposite a video's length.
 */
@Composable
internal fun MediaCell(
    item: MediaItem,
    onClick: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    label: String? = null,
) {
    val context = LocalContext.current
    val request = remember(item.uri, item.dateModifiedSeconds) {
        ImageRequest.Builder(context)
            .data(MediaThumbnail(item.uri, item.dateModifiedSeconds))
            // Only thumbnails decoded fresh fade in; ones already in memory appear at once.
            .crossfade(CROSSFADE_MILLIS)
            .build()
    }
    val inset by animateDpAsState(if (selected) SELECTED_INSET else 0.dp, label = "inset")
    val corner by animateDpAsState(if (selected) SELECTED_CORNER else 0.dp, label = "corner")
    val selectLabel = stringResource(R.string.cell_select)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )
            .then(
                if (selecting) {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            // The description is worked out only when something reads it, such as TalkBack, not
            // for every cell a fling passes.
            .semantics {
                contentDescription = mediaDescription(context, item)
                // Holding a cell starts a selection; TalkBack offers the same as an action.
                if (!selecting && onSelect != null) {
                    onLongClick(label = selectLabel) {
                        onSelect()
                        true
                    }
                }
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(RoundedCornerShape(corner)),
        )
        if (item.type == MediaType.VIDEO) {
            CellLabel(
                text = item.durationMillis?.let(::formatDuration) ?: "",
                modifier = Modifier.align(Alignment.BottomEnd).padding(inset),
            )
        }
        if (label != null) {
            CellLabel(text = label, modifier = Modifier.align(Alignment.BottomStart).padding(inset))
        }
        if (selecting) {
            SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

@Composable
private fun CellLabel(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), blurRadius = 4f),
        ),
        modifier = modifier
            .padding(4.dp)
            .clearAndSetSemantics {},
    )
}

/** A tick in a filled circle on selected cells, an empty ring on the others. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier) {
    if (selected) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        )
    } else {
        Box(
            modifier
                .size(24.dp)
                .padding(2.dp)
                .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

private val SELECTED_INSET = 10.dp
private val SELECTED_CORNER = 12.dp

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
