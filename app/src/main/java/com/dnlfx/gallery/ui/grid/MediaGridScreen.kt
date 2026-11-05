package com.dnlfx.gallery.ui.grid

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
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
    /** Where each cell on screen is, so the viewer can open out of it and close back into it. */
    cellBounds: CellBounds? = null,
) {
    // A large title that folds into a compact bar as the grid scrolls, like the system apps.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Only crossing the threshold matters, so the bar recomposes once when the grid slides under
    // it rather than on every frame of a scroll.
    val scrolledUnder by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
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
    // The bar recomposes on every frame of its colour change; walk the library once.
    val selectedItems = remember(shown, selection) {
        if (selection.isEmpty()) emptyList() else shown.orEmpty().filter { it.id in selection }
    }
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
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "barColor",
            )
            val colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barColor,
                scrolledContainerColor = barColor,
            )
            Column(Modifier.background(barColor)) {
                if (selecting) {
                    val count = selection.size
                    LargeFlexibleTopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_clear))
                            }
                        },
                        title = {
                            Text(
                                resources.getQuantityString(
                                    R.plurals.selected_count,
                                    count,
                                    NumberFormat.getIntegerInstance().format(count),
                                ),
                                maxLines = 1,
                            )
                        },
                        colors = colors,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    val loaded = state as? GridState.Loaded
                    LargeFlexibleTopAppBar(
                        title = { Text(stringResource(R.string.app_name), maxLines = 1) },
                        subtitle = {
                            if (loaded != null && loaded.items.isNotEmpty()) {
                                val count = loaded.items.size
                                Text(
                                    text = resources.getQuantityString(
                                        R.plurals.item_count,
                                        count,
                                        NumberFormat.getIntegerInstance().format(count),
                                    ),
                                    maxLines = 1,
                                )
                            }
                        },
                        actions = {
                            if (loaded != null && loaded.allItems.isNotEmpty()) {
                                SortMenu(
                                    sort = loaded.sort,
                                    onSelect = { sort ->
                                        scope.launch { gridState.scrollToItem(0) }
                                        onSortSelected(sort)
                                    },
                                )
                            }
                            if (onOpenTrash != null) MoreMenu(onOpenTrash)
                        },
                        colors = colors,
                        scrollBehavior = scrollBehavior,
                    )
                }
                if (state is GridState.Loaded && state.allItems.isNotEmpty()) {
                    FilterRow(
                        filters = state.filters,
                        selected = state.filter,
                        enabled = !selecting,
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
                LoadingIndicator(Modifier.size(64.dp))
            }
            is GridState.Loaded -> if (state.items.isEmpty() && !limitedAccess) {
                EmptyState(
                    title = stringResource(
                        if (state.filter == MediaFilter.ALL) R.string.empty_library else R.string.empty_filter,
                    ),
                    body = if (state.filter == MediaFilter.ALL) stringResource(R.string.empty_library_body) else null,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                val media = state.items
                val entries = state.sections.entries
                val bannerCount = if (limitedAccess) 1 else 0
                val sort = state.sort
                val context = LocalContext.current
                val haptics = LocalHapticFeedback.current
                val monthFormat = remember {
                    val locale = Locale.getDefault()
                    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale)
                }
                val level = rememberGridLevel()
                val ids = remember(media) { media.map { it.id } }
                // Room under the last row for the floating toolbar while selecting.
                val layoutDirection = LocalLayoutDirection.current
                val gridPadding = remember(padding, selecting, layoutDirection) {
                    PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding() + if (selecting) TOOLBAR_CLEARANCE else 0.dp,
                    )
                }
                val dragToSelect = rememberDragToSelect(
                    gridState = gridState,
                    ids = ids,
                    selected = selection,
                    onSelectedChange = { selection = it },
                    contentPadding = gridPadding,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .pinchToResize(level) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        // Four columns on a phone held upright by default; pinch for 3, 6 or 8.
                        columns = gridCellsFor(level.intValue),
                        contentPadding = gridPadding,
                        horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                        verticalArrangement = Arrangement.spacedBy(CELL_GAP),
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
                                    // Slides into place when a pinch changes the columns.
                                    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
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
                                        bounds = cellBounds,
                                        modifier = Modifier.animateItem(fadeInSpec = null),
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
                        contentPadding = gridPadding,
                    )
                    SelectionToolbar(
                        visible = selecting,
                        allFavorite = selectedItems.isNotEmpty() && selectedItems.all { it.isFavorite },
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
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = padding.calculateBottomPadding()),
                    )
                }
            }
        }
    }
}

/** What can be done to every selected item, floating over the bottom of the grid. */
@Composable
private fun SelectionToolbar(
    visible: Boolean,
    allFavorite: Boolean,
    onFavorite: ((favorite: Boolean) -> Unit)?,
    onTrash: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onFavorite == null && onTrash == null) return
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeIn(),
        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
        modifier = modifier.padding(bottom = 16.dp),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        ) {
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
        }
    }
}

/** A friendly empty screen: a soft shape with a photo glyph, a title and an optional line. */
@Composable
internal fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GridIcons.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
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
private fun FilterRow(
    filters: List<MediaFilter>,
    selected: MediaFilter,
    enabled: Boolean,
    onSelect: (MediaFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(bottom = 8.dp),
    ) {
        items(filters, key = { it.name }) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                enabled = enabled,
                // Tapping the selected filter again goes back to everything.
                onClick = { onSelect(if (isSelected) MediaFilter.ALL else filter) },
                label = { Text(stringResource(filter.label)) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = null,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun MonthHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp)
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
    bounds: CellBounds? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(item.uri, item.dateModifiedSeconds) {
        ImageRequest.Builder(context)
            .data(MediaThumbnail(item.uri, item.dateModifiedSeconds))
            // Only thumbnails decoded fresh fade in; ones already in memory appear at once.
            .crossfade(CROSSFADE_MILLIS)
            .build()
    }
    // One springy value drives both the inset and the corners of a selected cell.
    val selectedFraction by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "selected",
    )
    val inset = SELECTED_INSET * selectedFraction
    val corner = SELECTED_CORNER * selectedFraction
    val selectLabel = stringResource(R.string.cell_select)
    if (bounds != null) {
        DisposableEffect(bounds, item.id) { onDispose { bounds.drop(item.id) } }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (bounds != null) Modifier.onPlaced { bounds.place(item.id, it) } else Modifier)
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
        if (item.isFavorite && label == null) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(inset)
                    .padding(5.dp)
                    .size(14.dp),
            )
        }
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
        Box(
            modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
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
private val SELECTED_CORNER = 16.dp
private val CELL_GAP = 2.dp

/** Space the floating selection toolbar needs above the bottom inset: its height plus margins. */
private val TOOLBAR_CLEARANCE = 96.dp

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
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.limited_access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = onRequestFullAccess,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text(stringResource(R.string.limited_access_action)) }
    }
}

/**
 * Where each grid cell on screen sits, kept up to date as cells are placed. Opening the viewer
 * grows the photo out of its cell, and closing it shrinks the photo back into the one it lands on.
 * Plain fields, not state: nothing redraws when a cell moves.
 */
@Stable
class CellBounds {
    private val placed = HashMap<Long, LayoutCoordinates>()

    internal fun place(id: Long, coordinates: LayoutCoordinates) {
        placed[id] = coordinates
    }

    internal fun drop(id: Long) {
        placed.remove(id)
    }

    /** The cell's bounds on screen, or null when it isn't on screen. */
    fun boundsOf(id: Long): Rect? =
        placed[id]?.takeIf { it.isAttached }?.boundsInRoot()?.takeIf { it.width > 0f && it.height > 0f }
}
