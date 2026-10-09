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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface GridState {
    data object Loading : GridState
    data class Loaded(
        /** What the grid shows: the library narrowed by [filter]. */
        val items: List<MediaItem>,
        val sections: GridSections = GridSections.Empty,
        /** The access level the library was read with. */
        val access: MediaAccess = MediaAccess.NONE,
        /** The whole library, whatever the filter. */
        val allItems: List<MediaItem> = items,
        val filter: MediaFilter = MediaFilter.ALL,
        /** The filters worth offering: All, the selected one, and any with at least one item. */
        val filters: List<MediaFilter> = listOf(MediaFilter.ALL),
    ) : GridState
}

class GridViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val access = MutableStateFlow(MediaAccess.NONE)
    private val filter = MutableStateFlow(MediaFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GridState> = access
        // Re-query whenever the access level changes, e.g. partial to full.
        .flatMapLatest { level ->
            if (level == MediaAccess.NONE) {
                flowOf(GridState.Loaded(emptyList()))
            } else {
                combine<List<MediaItem>, MediaFilter, GridState>(repository.observeMedia(), filter) { items, selected ->
                    val shown = if (selected == MediaFilter.ALL) items else items.filter(selected::matches)
                    GridState.Loaded(
                        items = shown,
                        sections = buildGridSections(shown.map { it.dateModifiedSeconds }),
                        access = level,
                        allItems = items,
                        filter = selected,
                        filters = availableFilters(items, selected),
                    )
                }
                    .flowOn(Dispatchers.Default)
                    .onStart { emit(GridState.Loading) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GridState.Loading)

    fun onAccessChanged(level: MediaAccess) {
        access.value = level
    }

    fun onFilterSelected(selected: MediaFilter) {
        filter.value = selected
    }
}
