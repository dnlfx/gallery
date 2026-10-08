package com.dnlfx.gallery.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay

/** Snapshot of the player that Compose can observe. */
@Stable
class PlaybackState {
    var isPlaying by mutableStateOf(false)
        internal set
    var playWhenReady by mutableStateOf(false)
        internal set
    var ended by mutableStateOf(false)
        internal set
    var positionMillis by mutableLongStateOf(0L)
        internal set
    var durationMillis by mutableLongStateOf(0L)
        internal set

    /** Width over height of the video frame, or 0 until the first frame size is known. */
    var aspectRatio by mutableFloatStateOf(0f)
        internal set
    var firstFrameRendered by mutableStateOf(false)
        internal set

    internal fun resetForNewItem() {
        firstFrameRendered = false
        aspectRatio = 0f
        positionMillis = 0L
        durationMillis = 0L
        ended = false
    }

    internal fun sync(player: Player) {
        isPlaying = player.isPlaying
        playWhenReady = player.playWhenReady
        ended = player.playbackState == Player.STATE_ENDED
        positionMillis = player.currentPosition.coerceAtLeast(0L)
        durationMillis = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    }
}

@Composable
fun rememberPlaybackState(player: Player): PlaybackState {
    val state = remember(player) { PlaybackState() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.sync(player)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                state.aspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                } else {
                    0f
                }
            }

            override fun onRenderedFirstFrame() {
                state.firstFrameRendered = true
            }
        }
        player.addListener(listener)
        state.sync(player)
        onDispose { player.removeListener(listener) }
    }
    // The player doesn't report position ticks, so poll while something is loaded.
    LaunchedEffect(player) {
        while (true) {
            state.sync(player)
            delay(if (state.isPlaying) 100L else 250L)
        }
    }
    return state
}
