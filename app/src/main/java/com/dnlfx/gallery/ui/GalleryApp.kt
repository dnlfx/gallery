package com.dnlfx.gallery.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.externalMediaItem
import com.dnlfx.gallery.data.mediaStoreId
import com.dnlfx.gallery.ui.grid.GridState
import com.dnlfx.gallery.ui.grid.GridViewModel
import com.dnlfx.gallery.ui.grid.MediaGridScreen
import com.dnlfx.gallery.ui.permission.MediaAccess
import com.dnlfx.gallery.ui.permission.MediaPermissions
import com.dnlfx.gallery.ui.permission.PermissionScreen
import com.dnlfx.gallery.ui.viewer.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The whole app: the permission screen, then the grid with the viewer over it. When another app
 * asked to show [openedUri], only the viewer appears, and leaving it calls [onFinish].
 */
@Composable
fun GalleryApp(
    openedUri: Uri? = null,
    openedMimeType: String? = null,
    onFinish: () -> Unit = {},
    viewModel: GridViewModel = viewModel(),
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(MediaPermissions.currentAccess(context)) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        access = MediaPermissions.currentAccess(context)
        val activity = context.findActivity()
        // After a denial the system stops showing the dialog once rationale is no longer offered.
        permanentlyDenied = access == MediaAccess.NONE && activity != null &&
            MediaPermissions.requested.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }

    // The user may change access in system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = MediaPermissions.currentAccess(context)
        if (access != MediaAccess.NONE) permanentlyDenied = false
    }
    LaunchedEffect(access) { viewModel.onAccessChanged(access) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    if (openedUri != null) {
        OpenedItemViewer(uri = openedUri, mimeType = openedMimeType, access = access, state = state, onClose = onFinish)
        return
    }

    // Survives the trip into the viewer so the grid comes back where it was.
    val gridState = rememberLazyGridState()
    var viewerItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (access == MediaAccess.NONE) {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(MediaPermissions.requested) },
            onOpenSettings = { context.openAppSettings() },
        )
        return
    }

    val loaded = state as? GridState.Loaded
    val items = loaded?.items
    val limitedAccess = access == MediaAccess.PARTIAL
    val openItemId = viewerItemId
    val viewerOpen = openItemId != null && items != null
    Box(Modifier.fillMaxSize()) {
        // The grid stays underneath the viewer, so pulling a photo down reveals it.
        MediaGridScreen(
            state = state,
            limitedAccess = limitedAccess,
            onRequestFullAccess = { launcher.launch(MediaPermissions.requested) },
            onItemClick = { _, item -> viewerItemId = item.id },
            onFilterSelected = viewModel::onFilterSelected,
            gridState = gridState,
            modifier = if (viewerOpen) Modifier.clearAndSetSemantics {} else Modifier,
        )
        if (openItemId != null && items != null) {
            ViewerScreen(
                items = items,
                startItemId = openItemId,
                onCurrentItemChange = { viewerItemId = it },
                onClose = { viewerItemId = null },
            )
        }
    }
    // Keep the item being viewed on screen in the grid, so closing the viewer lands on it.
    LaunchedEffect(viewerItemId, loaded) {
        val id = viewerItemId ?: return@LaunchedEffect
        val library = loaded ?: return@LaunchedEffect
        val index = library.items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val gridIndex = library.sections.entryIndexOf(index) + if (limitedAccess) 1 else 0
            val visible = snapshotFlow { gridState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
            if (visible.none { it.index == gridIndex }) gridState.scrollToItem(gridIndex)
        }
    }
}

/**
 * A photo or video another app asked to show. One that's in the library opens among its
 * neighbours, so swiping works as usual; anything else opens on its own.
 */
@Composable
private fun OpenedItemViewer(uri: Uri, mimeType: String?, access: MediaAccess, state: GridState, onClose: () -> Unit) {
    val context = LocalContext.current
    val libraryId = remember(uri) { mediaStoreId(uri) }
    // Decided once, so a library refresh (say, after moving the item to the trash) keeps the same viewer.
    var inLibrary by remember { mutableStateOf<Boolean?>(null) }
    val library = (state as? GridState.Loaded)?.takeIf { it.access == access }
    LaunchedEffect(libraryId, library, access) {
        if (inLibrary != null) return@LaunchedEffect
        inLibrary = when {
            libraryId == null || access == MediaAccess.NONE -> false
            library == null -> null
            else -> library.allItems.any { it.id == libraryId }
        }
    }
    var standalone by remember { mutableStateOf<MediaItem?>(null) }
    var standaloneFailed by remember { mutableStateOf(false) }
    LaunchedEffect(inLibrary) {
        if (inLibrary != false) return@LaunchedEffect
        val item = withContext(Dispatchers.IO) { externalMediaItem(context.contentResolver, uri, mimeType) }
        standalone = item
        standaloneFailed = item == null
    }

    // The whole library, so a filter left on from earlier doesn't narrow what can be swiped to.
    val libraryItems = library?.allItems
    val single = standalone
    when {
        inLibrary == true && libraryId != null && libraryItems != null -> ViewerScreen(
            items = libraryItems,
            startItemId = libraryId,
            onCurrentItemChange = {},
            onClose = onClose,
        )
        single != null -> ViewerScreen(
            items = listOf(single),
            startItemId = single.id,
            onCurrentItemChange = {},
            onClose = onClose,
        )
        else -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (standaloneFailed) {
                Text(
                    text = stringResource(R.string.viewer_open_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
