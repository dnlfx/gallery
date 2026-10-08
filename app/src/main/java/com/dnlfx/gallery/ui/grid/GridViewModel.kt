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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface GridState {
    data object Loading : GridState
    data class Loaded(
        val items: List<MediaItem>,
        val sections: GridSections = GridSections.Empty,
        /** The access level the library was read with. */
        val access: MediaAccess = MediaAccess.NONE,
    ) : GridState
}

class GridViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val access = MutableStateFlow(MediaAccess.NONE)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GridState> = access
        // Re-query whenever the access level changes, e.g. partial to full.
        .flatMapLatest { level ->
            if (level == MediaAccess.NONE) {
                flowOf(GridState.Loaded(emptyList()))
            } else {
                repository.observeMedia()
                    .map<List<MediaItem>, GridState> { items ->
                        GridState.Loaded(items, buildGridSections(items.map { it.dateModifiedSeconds }), level)
                    }
                    .flowOn(Dispatchers.Default)
                    .onStart { emit(GridState.Loading) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GridState.Loading)

    fun onAccessChanged(level: MediaAccess) {
        access.value = level
    }
}
