package com.dnlfx.gallery.ui.viewer

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Moving to the trash needs Android 11; Android 10 can only delete for good, so it isn't offered. */
val canTrash: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * Asks the system to move a library item to its trash. The system shows its own confirmation, and
 * the item can be restored from the trash for 30 days. The library refreshes on its own once the
 * item is gone, so nothing needs to happen when the dialog closes.
 */
@Composable
fun rememberTrashRequest(): (Uri) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    return remember(launcher) {
        { uri: Uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true)
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
    }
}
