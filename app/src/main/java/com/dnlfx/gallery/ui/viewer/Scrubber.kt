package com.dnlfx.gallery.ui.viewer

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener

/**
 * All seeks in a video: a finger moving through it (the hold-then-slide or a drag along the seek
 * bar), and single jumps (a tap on the seek bar, a double-tap skip, TalkBack).
 *
 * Both run the player in Media3's scrubbing mode, which is built for exactly this: while a seek is
 * still finding its frame, newer ones replace the waiting one instead of queueing behind it, so
 * the preview never falls behind the finger. Every preview is the exact frame, and the player
 * runs the decoder at full speed, skips decoding frames the preview doesn't need, carries on
 * decoding instead of starting over from a keyframe when the new spot is just ahead, and leaves
 * audio out, which makes each seek much cheaper. Playback is held while scrubbing rather than
 * paused, so it carries on from the new spot the moment the finger lifts or the jump lands.
 *
 * The time and progress bar follow the finger (see [PlaybackState.scrubTargetMillis]).
 */
class Scrubber(private val player: ExoPlayer, private val playback: PlaybackState) {
    var active = false
        private set

    /** Where the last [moveTo] asked to go, or null before the first move. */
    var targetMillis: Long? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    // A jump stays in scrubbing mode until its frame is on screen; then playback carries on.
    private var jumping = false
    @Volatile private var pendingJump: Jump? = null
    private val jumpTimeout = Runnable { finishJump() }

    // Called on the playback thread just before each frame is shown.
    private val frameListener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        val jump = pendingJump
        if (jump != null && seekLanded(presentationTimeUs, jump.targetUs)) {
            // Ignored if a newer jump has replaced this one by the time it runs.
            handler.post { if (pendingJump === jump) finishJump() }
        }
    }

    init {
        player.setVideoFrameMetadataListener(frameListener)
    }

    fun begin() {
        if (active) return
        active = true
        targetMillis = null
        if (jumping) {
            // Already in scrubbing mode from a jump that hasn't landed: the finger takes over.
            stopJump()
            return
        }
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

    /**
     * One seek, such as a tap on the seek bar. It gets the same fast path as scrubbing, and a
     * jump made before the last one lands simply replaces it.
     */
    fun jumpTo(positionMillis: Long) {
        if (active) {
            moveTo(positionMillis)
            return
        }
        val seekMillis = scrubSeekPosition(positionMillis, player.duration)
        if (!jumping) {
            jumping = true
            player.setScrubbingModeEnabled(true)
        }
        pendingJump = Jump(Util.msToUs(seekMillis))
        playback.scrubTargetMillis = positionMillis
        player.seekTo(seekMillis)
        // Never left in scrubbing mode, even if no frame shows up (say, a seek to where it is).
        handler.removeCallbacks(jumpTimeout)
        handler.postDelayed(jumpTimeout, JUMP_TIMEOUT_MILLIS)
    }

    private fun stopJump() {
        jumping = false
        pendingJump = null
        handler.removeCallbacks(jumpTimeout)
    }

    private fun finishJump() {
        if (!jumping) return
        stopJump()
        player.setScrubbingModeEnabled(false)
        playback.scrubTargetMillis = null
        playback.sync(player)
    }

    fun release() {
        finish()
        finishJump()
        player.clearVideoFrameMetadataListener(frameListener)
    }
}

@Composable
fun rememberScrubber(player: ExoPlayer, playback: PlaybackState): Scrubber {
    val scrubber = remember(player, playback) { Scrubber(player, playback) }
    DisposableEffect(scrubber) {
        // Leaving mid-scrub: don't leave the player held in scrubbing mode.
        onDispose { scrubber.release() }
    }
    return scrubber
}

private class Jump(val targetUs: Long)

private const val JUMP_TIMEOUT_MILLIS = 2_000L
