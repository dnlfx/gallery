package com.dnlfx.gallery.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.thumbnail.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGridScreen(
    state: GridState,
    limitedAccess: Boolean,
    onRequestFullAccess: () -> Unit,
    onItemClick: (index: Int, item: MediaItem) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        if (state is GridState.Loaded && state.items.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.item_count, state.items.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (state) {
            GridState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            is GridState.Loaded -> if (state.items.isEmpty() && !limitedAccess) {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    Text(stringResource(R.string.empty_library))
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    // About four columns on a phone held upright, more in landscape.
                    columns = GridCells.Adaptive(minSize = 88.dp),
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
                    val media = state.items
                    items(count = media.size, key = { media[it].id }, contentType = { "media" }) { index ->
                        val item = media[index]
                        MediaCell(item = item, onClick = { onItemClick(index, item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCell(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = MediaThumbnail(item.uri, item.dateModifiedSeconds),
            contentDescription = item.displayName,
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

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
