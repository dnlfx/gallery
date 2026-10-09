package com.dnlfx.gallery.ui.grid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaRepository
import com.dnlfx.gallery.ui.permission.MediaAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface GridState {
    data object Loading : GridState

    /**
     * A plain class, not a data class: comparing two states field by field would walk the whole
     * library on the main thread every time a new one arrives, and a new one always differs.
     */
    class Loaded(
        /** What the grid shows: the library narrowed by [filter]. */
        val items: List<MediaItem>,
        val sections: GridSections = GridSections.Empty,
        /** The access level the library was read with. */
        val access: MediaAccess = MediaAccess.NONE,
        /** The whole library, whatever the filter. */
        val allItems: List<MediaItem> = items,
        val filter: MediaFilter = MediaFilter.ALL,
        val sort: MediaSort = MediaSort.Default,
        /** The filters worth offering: All, the selected one, and any with at least one item. */
        val filters: List<MediaFilter> = listOf(MediaFilter.ALL),
    ) : GridState
}

class GridViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val access = MutableStateFlow(MediaAccess.NONE)
    private val filter = MutableStateFlow(MediaFilter.ALL)
    private val sort = MutableStateFlow(MediaSort.load(application))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GridState> = access
        // Re-query whenever the access level changes, e.g. partial to full.
        .flatMapLatest { level ->
            if (level == MediaAccess.NONE) {
                flowOf(GridState.Loaded(emptyList()))
            } else {
                // Worked out once per library read, not again on every filter or sort change.
                val library = repository.observeMedia().map { items -> Library(items, nonEmptyFilters(items)) }
                combine<Library, MediaFilter, MediaSort, GridState>(library, filter, sort) { lib, selected, order ->
                    val items = lib.items
                    val filtered = if (selected == MediaFilter.ALL) items else items.filter(selected::matches)
                    val shown = order.sorted(filtered)
                    GridState.Loaded(
                        items = shown,
                        sections = if (order.field.hasMonths) {
                            buildGridSections(shown.map(order::monthSeconds))
                        } else {
                            flatGridSections(shown.size)
                        },
                        access = level,
                        allItems = items,
                        filter = selected,
                        sort = order,
                        filters = chipFilters(lib.nonEmptyFilters, selected),
                    )
                }
                    .flowOn(Dispatchers.Default)
                    // Coming back to the app reads the library again. Until it's in, keep showing
                    // the last one rather than a spinner, so an open viewer and its video stay put.
                    .onStart { if ((state.value as? GridState.Loaded)?.access != level) emit(GridState.Loading) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GridState.Loading)

    fun onAccessChanged(level: MediaAccess) {
        access.value = level
    }

    fun onFilterSelected(selected: MediaFilter) {
        filter.value = selected
    }

    fun onSortSelected(selected: MediaSort) {
        sort.value = selected
        MediaSort.save(getApplication(), selected)
    }
}

/** One read of the library, with the filters that have anything in it. */
private class Library(val items: List<MediaItem>, val nonEmptyFilters: Set<MediaFilter>)
