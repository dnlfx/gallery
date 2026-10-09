package com.dnlfx.gallery.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext

private const val PREFS = "viewer"
private const val KEY_LOOP = "loop"

/** Whether videos play again from the start when they finish, kept on the device across launches. */
@Composable
fun rememberLoopVideos(): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val loop = remember { mutableStateOf(prefs.getBoolean(KEY_LOOP, false)) }
    LaunchedEffect(loop) {
        snapshotFlow { loop.value }.collect { prefs.edit().putBoolean(KEY_LOOP, it).apply() }
    }
    return loop
}
