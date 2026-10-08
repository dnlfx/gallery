package com.dnlfx.gallery.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Seeks for a finger moving through a video, used by both the sideways slide and the seek bar.
 *
 * It runs the player in Media3's scrubbing mode, which is built for exactly this: while a seek is
 * still finding its frame, newer ones replace the waiting one instead of queueing behind it, so
 * the preview never falls behind the finger. Every preview is the exact frame, and the player
 * keeps the decoder warm, skips decoding frames the preview doesn't need and leaves audio out,
 * which makes each seek much cheaper. Playback is held while scrubbing rather than paused, so it
 * carries on from the new spot the moment the finger lifts.
 *
 * The time and progress bar follow the finger (see [PlaybackState.scrubTargetMillis]).
 */
class Scrubber(private val player: ExoPlayer, private val playback: PlaybackState) {
    var active = false
        private set

    /** Where the last [moveTo] asked to go, or null before the first move. */
    var targetMillis: Long? = null
        private set

    fun begin() {
        if (active) return
        active = true
        targetMillis = null
        // A finished video stays paused after scrubbing back, like it was before.
        if (player.playbackState == Player.STATE_ENDED) player.pause()
        player.setScrubbingModeEnabled(true)
    }

    fun moveTo(positionMillis: Long) {
        if (!active || positionMillis == targetMillis) return
        targetMillis = positionMillis
        playback.scrubTargetMillis = positionMillis
        player.seekTo(scrubSeekPosition(positionMillis, player.duration))
    }

    fun finish() {
        if (!active) return
        active = false
        // The last seek is already exact, so leaving scrubbing mode is all that's left: playback,
        // if it was on, continues straight from there.
        player.setScrubbingModeEnabled(false)
        playback.scrubTargetMillis = null
        // Show where the scrub landed now, not the stale position from before the next poll.
        playback.sync(player)
    }
}

@Composable
fun rememberScrubber(player: ExoPlayer, playback: PlaybackState): Scrubber {
    val scrubber = remember(player, playback) { Scrubber(player, playback) }
    DisposableEffect(scrubber) {
        // Leaving mid-scrub (the page changed): don't leave the player held in scrubbing mode.
        onDispose { scrubber.finish() }
    }
    return scrubber
}
