package com.dnlfx.gallery.ui.grid

/** Formats a video length like the Photos app: 0:07, 12:34, 1:02:03. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
