package com.dnlfx.gallery.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Trash, restore, delete for good and favorites all need Android 11. Android 10 can only delete
 * for good with no way back, so none of them is offered there.
 */
val canChangeMedia: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * Changes to library items that the app doesn't own go through the system, which asks the user
 * to confirm in its own dialog. The library refreshes on its own once something changes, so
 * callers only pass [onApproved] for anything else they want to do, like clearing a selection.
 */
class MediaRequests internal constructor(
    private val resolver: ContentResolver,
    private val launch: (PendingIntent, onApproved: () -> Unit) -> Unit,
) {
    /** Moves items to the system trash, where they can be restored for 30 days. */
    fun trash(uris: Collection<Uri>, onApproved: () -> Unit = {}) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        launch(MediaStore.createTrashRequest(resolver, uris, true), onApproved)
    }

    /** Puts trashed items back in the library. */
    fun restore(uris: Collection<Uri>, onApproved: () -> Unit = {}) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        launch(MediaStore.createTrashRequest(resolver, uris, false), onApproved)
    }

    /** Deletes items for good, without going through the trash. */
    fun deleteForever(uris: Collection<Uri>, onApproved: () -> Unit = {}) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        launch(MediaStore.createDeleteRequest(resolver, uris), onApproved)
    }

    /** Stars items, or unstars them when [favorite] is false. The same flag Photos and Files use. */
    fun favorite(uris: Collection<Uri>, favorite: Boolean, onApproved: () -> Unit = {}) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        launch(MediaStore.createFavoriteRequest(resolver, uris, favorite), onApproved)
    }
}

@Composable
fun rememberMediaRequests(): MediaRequests {
    val context = LocalContext.current
    // One request is on screen at a time, so one slot for what to do once it's approved.
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val onApproved = pending[0]
        pending[0] = null
        if (result.resultCode == Activity.RESULT_OK) onApproved?.invoke()
    }
    return remember(launcher) {
        MediaRequests(context.contentResolver) { intent, onApproved ->
            pending[0] = onApproved
            launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        }
    }
}
