package com.dnlfx.gallery.ui.viewer

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * Seeks for a finger moving through a video, used by both the sideways slide and the seek bar.
 *
 * Every seek makes the player find and decode a frame, which on a long or high-resolution video
 * takes longer than the gap between touch events. Queuing a seek per event left the player working
 * through a backlog long after the finger let go, so the video sat frozen. Here only one seek is
 * in flight at a time: newer targets replace the waiting one, and the next goes out as soon as
 * the player has a frame. [finish] lands on the exact frame and resumes playback if it was playing.
 *
 * The time and progress bar follow the finger (see [PlaybackState.scrubTargetMillis]), so the
 * picture should show that same moment. Short clips preview exact frames throughout. Long videos
 * preview keyframes while the finger moves, to keep up, and as soon as the player has caught up
 * it settles on the exact frame, so picture, time and bar agree whenever the finger slows or stops.
 */
class Scrubber(private val player: ExoPlayer, private val playback: PlaybackState) {
    var active = false
        private set

    /** Where the last [moveTo] asked to go, or null before the first move. */
    var targetMillis: Long? = null
        private set

    private var resume = false
    private var keyframePreviews = false
    private var waiting: Long? = null
    private var lastSeekAt = 0L
    private var lastSeekExact = true

    internal val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!active || playbackState == Player.STATE_BUFFERING) return
            val next = waiting
            val target = targetMillis
            when {
                next != null -> seekNow(next, exact = !keyframePreviews)
                // Caught up with the finger on a keyframe: refine to the exact frame.
                !lastSeekExact && target != null -> seekNow(target, exact = true)
            }
        }
    }

    fun begin() {
        if (active) return
        active = true
        targetMillis = null
        waiting = null
        // A finished video stays paused after scrubbing back, like it was before.
        resume = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        player.pause()
        keyframePreviews = !scrubsExactly(player.duration)
    }

    fun moveTo(positionMillis: Long) {
        if (!active || positionMillis == targetMillis) return
        targetMillis = positionMillis
        playback.scrubTargetMillis = positionMillis
        // The player reports buffering from the moment of a seek until it has the frame. If that
        // takes unusually long, send the newest target anyway rather than freeze the preview.
        val busy = player.playbackState == Player.STATE_BUFFERING &&
            SystemClock.uptimeMillis() - lastSeekAt < MAX_WAIT_MILLIS
        if (busy) waiting = positionMillis else seekNow(positionMillis, exact = !keyframePreviews)
    }

    fun finish() {
        if (!active) return
        active = false
        waiting = null
        targetMillis?.let { seekNow(it, exact = true) }
        playback.scrubTargetMillis = null
        // Show where the scrub landed now, not the stale frame from before the next poll.
        playback.sync(player)
        if (resume) player.play()
    }

    private fun seekNow(positionMillis: Long, exact: Boolean) {
        waiting = null
        lastSeekAt = SystemClock.uptimeMillis()
        if (exact != lastSeekExact) {
            player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            lastSeekExact = exact
        }
        player.seekTo(positionMillis)
    }
}

@Composable
fun rememberScrubber(player: ExoPlayer, playback: PlaybackState): Scrubber {
    val scrubber = remember(player, playback) { Scrubber(player, playback) }
    DisposableEffect(scrubber) {
        player.addListener(scrubber.listener)
        onDispose {
            player.removeListener(scrubber.listener)
            // Leaving mid-scrub (the page changed): don't strand the player on keyframe seeks.
            scrubber.finish()
        }
    }
    return scrubber
}

private const val MAX_WAIT_MILLIS = 500L
