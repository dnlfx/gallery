package com.dnlfx.gallery.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

private const val LOCAL_START_BUFFER_MILLIS = 250

/** The viewer's one video player, released when it leaves the screen. */
@Composable
fun rememberViewerPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember { buildViewerPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

private fun buildViewerPlayer(context: Context): ExoPlayer {
    // If the phone's preferred decoder can't take a file (an unusual HEVC profile, say),
    // try the next one instead of failing.
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderers)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        // Everything plays from the phone's own storage, which reads far faster than playback
        // needs, so start (and restart after a seek or scrub) once a quarter second is ready
        // instead of the default full second.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMsForLocalPlayback(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                    LOCAL_START_BUFFER_MILLIS,
                    LOCAL_START_BUFFER_MILLIS,
                )
                .build(),
        )
        .build()
}
