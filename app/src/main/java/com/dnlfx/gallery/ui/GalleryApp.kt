package com.dnlfx.gallery.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dnlfx.gallery.ui.grid.GridState
import com.dnlfx.gallery.ui.grid.GridViewModel
import com.dnlfx.gallery.ui.grid.MediaGridScreen
import com.dnlfx.gallery.ui.permission.MediaAccess
import com.dnlfx.gallery.ui.permission.MediaPermissions
import com.dnlfx.gallery.ui.permission.PermissionScreen
import com.dnlfx.gallery.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.first

@Composable
fun GalleryApp(viewModel: GridViewModel = viewModel()) {
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

    // Survives the trip into the viewer so the grid comes back where it was.
    val gridState = rememberLazyGridState()
    var viewerItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var returnToItemId by remember { mutableStateOf<Long?>(null) }

    if (access == MediaAccess.NONE) {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(MediaPermissions.requested) },
            onOpenSettings = { context.openAppSettings() },
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = (state as? GridState.Loaded)?.items
    val limitedAccess = access == MediaAccess.PARTIAL
    val openItemId = viewerItemId
    if (openItemId != null && items != null) {
        ViewerScreen(
            items = items,
            startItemId = openItemId,
            onCurrentItemChange = { viewerItemId = it },
            onClose = {
                returnToItemId = viewerItemId
                viewerItemId = null
            },
        )
    } else {
        MediaGridScreen(
            state = state,
            limitedAccess = limitedAccess,
            onRequestFullAccess = { launcher.launch(MediaPermissions.requested) },
            onItemClick = { _, item -> viewerItemId = item.id },
            gridState = gridState,
        )
        // Coming back from the viewer: make sure the last item viewed is on screen.
        LaunchedEffect(returnToItemId, items) {
            val id = returnToItemId ?: return@LaunchedEffect
            val index = items?.indexOfFirst { it.id == id } ?: -1
            if (index >= 0) {
                val gridIndex = index + if (limitedAccess) 1 else 0
                val visible = snapshotFlow { gridState.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
                if (visible.none { it.index == gridIndex }) gridState.scrollToItem(gridIndex)
            }
            returnToItemId = null
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
