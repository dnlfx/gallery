package com.dnlfx.gallery.ui.viewer

import kotlin.math.roundToLong

/** Playback speeds offered in the speed picker, from 0.25x to 3x. */
val PlaybackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/** "1x", "1.5x", "0.25x". */
fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "${text}x"
}

const val SKIP_MILLIS = 10_000L

/** Which part of the video a single tap landed on. */
enum class TapZone { BACK, CENTER, FORWARD }

/** The left third skips back, the right third skips forward, the middle is neither. */
fun tapZone(x: Float, width: Float): TapZone = when {
    width <= 0f -> TapZone.CENTER
    x < width / 3f -> TapZone.BACK
    x > width * 2f / 3f -> TapZone.FORWARD
    else -> TapZone.CENTER
}

/** What a finished tap on the video does. */
enum class TapResult {
    /** Possibly a single tap: show or hide the controls unless another tap follows in time. */
    PENDING,
    SKIP_BACK,
    SKIP_FORWARD,

    /** A double tap in the middle third: play or pause. */
    TOGGLE_PLAY,
}

/**
 * Tells single taps from double taps. A single tap anywhere shows or hides the controls; a double
 * tap on the left or right third skips 10 seconds, and each further tap on that side while the
 * taps keep coming adds 10 more, like YouTube and Photos. A double tap in the middle third plays
 * or pauses.
 */
class TapSequence(private val doubleTapMillis: Long) {
    private var lastZone: TapZone? = null
    private var lastUpMillis = 0L

    fun onTap(zone: TapZone, downMillis: Long, upMillis: Long): TapResult {
        val quick = lastZone != null && downMillis - lastUpMillis <= doubleTapMillis
        val sameZone = quick && zone == lastZone
        lastZone = zone
        lastUpMillis = upMillis
        return when {
            !sameZone -> TapResult.PENDING
            zone == TapZone.BACK -> TapResult.SKIP_BACK
            zone == TapZone.FORWARD -> TapResult.SKIP_FORWARD
            else -> {
                // Play or pause once per double tap; a third quick tap starts afresh.
                lastZone = null
                TapResult.TOGGLE_PLAY
            }
        }
    }

    /** A slide or a multi-finger touch ends any tap sequence. */
    fun reset() {
        lastZone = null
    }
}

/** Clamps a seek target into the video. An unknown duration (zero or less) only clamps at the start. */
fun clampPosition(positionMillis: Long, durationMillis: Long): Long {
    val atLeastZero = positionMillis.coerceAtLeast(0L)
    return if (durationMillis > 0L) atLeastZero.coerceAtMost(durationMillis) else atLeastZero
}

/**
 * How long one frame lasts, in microseconds, for a video of [frameRate] frames a second. Videos
 * that don't say (zero or less) count as 30 a second, the most common rate.
 */
fun frameDurationUs(frameRate: Float): Long {
    val rate = if (frameRate > 0f) frameRate else DEFAULT_FRAME_RATE
    return (1_000_000.0 / rate).roundToLong().coerceAtLeast(1L)
}

/** The frame on screen at [positionUs]: frame 0 starts the video. */
fun frameAt(positionUs: Long, frameUs: Long): Long = positionUs.coerceAtLeast(0L) / frameUs

/**
 * The last frame of a video [durationMillis] long, or null if its length isn't known yet. The
 * last frame starts at least half a frame before the end, which allows for rounding in both.
 */
fun lastFrame(durationMillis: Long, frameUs: Long): Long? =
    if (durationMillis > 0L) ((durationMillis * 1_000L - frameUs / 2) / frameUs).coerceAtLeast(0L) else null

/**
 * Frames a sideways slide of [dragDp] moves at [speedDpPerSecond]. At an ordinary pace every
 * [SCRUB_DP_PER_FRAME] dp is exactly one frame, so the picture steps through the video frame by
 * frame; only a deliberate fast flick covers several frames per step (see [scrubGain]).
 */
fun scrubFrames(dragDp: Float, speedDpPerSecond: Float): Float =
    dragDp / SCRUB_DP_PER_FRAME * scrubGain(speedDpPerSecond)

/**
 * Up to [SLOW_SLIDE_DP_PER_SECOND] a slide moves one frame per [SCRUB_DP_PER_FRAME] dp; faster,
 * the multiplier climbs to [MAX_SCRUB_GAIN] at [FAST_SLIDE_DP_PER_SECOND], so a quick flick can
 * still get somewhere. The frames it shows are still in order.
 */
fun scrubGain(speedDpPerSecond: Float): Float {
    val t = (speedDpPerSecond - SLOW_SLIDE_DP_PER_SECOND) / (FAST_SLIDE_DP_PER_SECOND - SLOW_SLIDE_DP_PER_SECOND)
    return 1f + t.coerceIn(0f, 1f) * (MAX_SCRUB_GAIN - 1f)
}

/** [frame] kept inside the video: no earlier than the first frame, no later than [lastFrame]. */
fun clampFrame(frame: Long, lastFrame: Long?): Long {
    val atLeastZero = frame.coerceAtLeast(0L)
    return if (lastFrame != null) atLeastZero.coerceAtMost(lastFrame) else atLeastZero
}

/** When [frame] starts, in milliseconds: what the time readout shows for it. */
fun frameStartMillis(frame: Long, frameUs: Long): Long = (frame * frameUs + 500L) / 1_000L

/**
 * Where to seek, in milliseconds, to show exactly [frame]. An exact seek shows the first frame at
 * or after its target, so aiming half a frame early lands on [frame] even when its timestamp is a
 * little off the even spacing.
 */
fun frameSeekMillis(frame: Long, frameUs: Long): Long =
    ((frame * frameUs - frameUs / 2) / 1_000L).coerceAtLeast(0L)

/** A time down to the hundredth of a second, like 0:12.36 or 1:02:03.50, for scrubbing by frame. */
fun formatPreciseTime(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val hundredths = (clamped % 1000) / 10
    return if (hours > 0) {
        "%d:%02d:%02d.%02d".format(hours, minutes, seconds, hundredths)
    } else {
        "%d:%02d.%02d".format(minutes, seconds, hundredths)
    }
}

/**
 * Where a scrub to [positionMillis] actually seeks. Scrub previews stop just short of the very end,
 * where the player has no frame left to show and can stall; the time still reads the full length.
 */
fun scrubSeekPosition(positionMillis: Long, durationMillis: Long): Long =
    if (durationMillis > SCRUB_END_MARGIN_MILLIS) {
        positionMillis.coerceAtMost(durationMillis - SCRUB_END_MARGIN_MILLIS)
    } else {
        positionMillis
    }

/**
 * Whether a frame about to be shown at [framePositionUs] is the one a seek to [targetUs] was
 * after. An exact seek shows the first frame at or just past the target, so anything within a
 * few frames of it counts.
 */
fun seekLanded(framePositionUs: Long, targetUs: Long): Boolean =
    framePositionUs >= targetUs - SEEK_LANDED_BEFORE_US && framePositionUs <= targetUs + SEEK_LANDED_AFTER_US

/**
 * The size a video shows at, given its stored [width] and [height] and the clockwise rotation it
 * plays with: a quarter turn swaps the sides.
 */
fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (isQuarterTurn(rotationDegrees)) height to width else width to height

/** Whether [rotationDegrees] turns the picture on its side (90 or 270 degrees, either way). */
fun isQuarterTurn(rotationDegrees: Int): Boolean = ((rotationDegrees % 180) + 180) % 180 == 90

/** What an up or down slide on the video does, by the third of the screen it starts in. */
enum class VerticalSlide { BRIGHTNESS, CLOSE, VOLUME }

/** Left third: brightness. Middle: pull down to close, like a photo. Right third: volume. */
fun verticalSlide(zone: TapZone): VerticalSlide = when (zone) {
    TapZone.BACK -> VerticalSlide.BRIGHTNESS
    TapZone.CENTER -> VerticalSlide.CLOSE
    TapZone.FORWARD -> VerticalSlide.VOLUME
}

/**
 * New volume or brightness as a 0..1 fraction after an upward slide of [dragUp] pixels; sliding
 * the full [height] of the screen goes from lowest to highest.
 */
fun levelAfterDrag(startFraction: Float, dragUp: Float, height: Float): Float {
    if (height <= 0f) return startFraction.coerceIn(0f, 1f)
    return (startFraction + dragUp / height).coerceIn(0f, 1f)
}

private const val DEFAULT_FRAME_RATE = 30f
private const val SCRUB_DP_PER_FRAME = 6f
private const val SLOW_SLIDE_DP_PER_SECOND = 1_200f
private const val FAST_SLIDE_DP_PER_SECOND = 3_000f
private const val MAX_SCRUB_GAIN = 4f
private const val SCRUB_END_MARGIN_MILLIS = 50L
private const val SEEK_LANDED_BEFORE_US = 1_000L
private const val SEEK_LANDED_AFTER_US = 250_000L
