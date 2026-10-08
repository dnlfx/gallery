package com.dnlfx.gallery.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.ui.grid.GridViewModel
import com.dnlfx.gallery.ui.grid.MediaGridScreen
import com.dnlfx.gallery.ui.permission.MediaAccess
import com.dnlfx.gallery.ui.permission.MediaPermissions
import com.dnlfx.gallery.ui.permission.PermissionScreen

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

    if (access == MediaAccess.NONE) {
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(MediaPermissions.requested) },
            onOpenSettings = { context.openAppSettings() },
        )
    } else {
        val state by viewModel.state.collectAsStateWithLifecycle()
        MediaGridScreen(
            state = state,
            limitedAccess = access == MediaAccess.PARTIAL,
            onRequestFullAccess = { launcher.launch(MediaPermissions.requested) },
            onItemClick = { _, item -> context.openInSystemViewer(item) },
        )
    }
}

// Stand-in until the in-app viewer lands: hand the item to whatever app handles it.
private fun Context.openInSystemViewer(item: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(item.uri, item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
