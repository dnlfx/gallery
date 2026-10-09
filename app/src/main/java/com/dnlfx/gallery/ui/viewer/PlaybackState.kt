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
import androidx.media3.common.Tracks
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

    /** The video the player has been given, or null before the first. */
    var itemId by mutableStateOf<Long?>(null)
        internal set

    /** The player gave up on this video, for example a codec the phone can't decode. */
    var failed by mutableStateOf(false)
        internal set

    /**
     * Starts over for a new video. [width] and [height] are its upright size as the gallery
     * knows it, so the picture can be laid out at its own shape before the player has read the
     * file; 0 if unknown.
     */
    internal fun resetForNewItem(id: Long, width: Int, height: Int) {
        itemId = id
        firstFrameRendered = false
        setFrame(width, height, pixelRatio = 1f)
        playerPositionMillis = 0L
        scrubTargetMillis = null
        durationMillis = 0L
        ended = false
        failed = false
    }

    internal fun setFrame(width: Int, height: Int, pixelRatio: Float) {
        val known = width > 0 && height > 0
        frameWidth = if (known) width else 0
        frameHeight = if (known) height else 0
        aspectRatio = if (known) width * pixelRatio / height else 0f
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

            // The size is known three times over, each surer than the last: from the gallery
            // (see resetForNewItem), from the file's track once it's been read, which is before
            // any frame is decoded, and from the decoder with the first frame. Laying the picture
            // out at the right shape before that first frame keeps it from flashing full screen.
            override fun onTracksChanged(tracks: Tracks) {
                val format = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat) }
                    ?: return
                val (width, height) = uprightSize(format.width, format.height, format.rotationDegrees)
                val ratio = if (isQuarterTurn(format.rotationDegrees)) {
                    1f / format.pixelWidthHeightRatio
                } else {
                    format.pixelWidthHeightRatio
                }
                state.setFrame(width, height, ratio)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Between videos the size is briefly unknown; keep the last one rather than
                // flashing the full screen.
                if (videoSize.width > 0 && videoSize.height > 0) {
                    state.setFrame(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
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
