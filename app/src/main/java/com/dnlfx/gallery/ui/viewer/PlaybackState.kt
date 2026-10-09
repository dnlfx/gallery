package com.dnlfx.gallery.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Snapshot of the player that Compose can observe. */
@Stable
class PlaybackState {
    var isPlaying by mutableStateOf(false)
        internal set
    var playWhenReady by mutableStateOf(false)
        internal set
    var ended by mutableStateOf(false)
        internal set
    private var playerPositionMillis by mutableLongStateOf(0L)

    /**
     * Where a scrub is heading, while one is under way. The player only reports the frame it last
     * landed on, which trails the finger, so the time and progress bar show this instead.
     */
    internal var scrubTargetMillis by mutableStateOf<Long?>(null)

    val positionMillis: Long get() = scrubTargetMillis ?: playerPositionMillis
    var durationMillis by mutableLongStateOf(0L)
        internal set

    /** Width over height of the video frame, or 0 until the first frame size is known. */
    var aspectRatio by mutableFloatStateOf(0f)
        internal set

    /** The video's own size in pixels, or 0 by 0 until it's known. */
    var frameWidth by mutableIntStateOf(0)
        internal set
    var frameHeight by mutableIntStateOf(0)
        internal set
    var firstFrameRendered by mutableStateOf(false)
        internal set

    /** The player gave up on this video, for example a codec the phone can't decode. */
    var failed by mutableStateOf(false)
        internal set

    internal fun resetForNewItem() {
        firstFrameRendered = false
        aspectRatio = 0f
        frameWidth = 0
        frameHeight = 0
        playerPositionMillis = 0L
        scrubTargetMillis = null
        durationMillis = 0L
        ended = false
        failed = false
    }

    internal fun sync(player: Player) {
        isPlaying = player.isPlaying
        playWhenReady = player.playWhenReady
        ended = player.playbackState == Player.STATE_ENDED
        playerPositionMillis = player.currentPosition.coerceAtLeast(0L)
        durationMillis = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    }
}

/** Follows [player], or stays idle while there's no player yet. */
@Composable
fun rememberPlaybackState(player: Player?): PlaybackState {
    val state = remember(player) { PlaybackState() }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.sync(player)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                state.frameWidth = videoSize.width.coerceAtLeast(0)
                state.frameHeight = videoSize.height.coerceAtLeast(0)
                state.aspectRatio = if (videoSize.width > 0 && videoSize.height > 0) {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                } else {
                    0f
                }
            }

            override fun onRenderedFirstFrame() {
                state.firstFrameRendered = true
            }

            override fun onPlayerError(error: PlaybackException) {
                state.failed = true
            }
        }
        player.addListener(listener)
        state.sync(player)
        onDispose { player.removeListener(listener) }
    }
    // The player doesn't report position ticks, so poll while it plays. Everything else (a seek,
    // a pause, the end) arrives as an event, so a photo, a paused video or the app in the
    // background wakes nothing up.
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        snapshotFlow { state.isPlaying }.collectLatest { playing ->
            while (playing) {
                state.sync(player)
                delay(POSITION_POLL_MILLIS)
            }
        }
    }
    return state
}

private const val POSITION_POLL_MILLIS = 100L
