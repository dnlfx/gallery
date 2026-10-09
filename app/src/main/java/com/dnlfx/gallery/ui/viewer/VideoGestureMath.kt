package com.dnlfx.gallery.ui.viewer

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
 * Milliseconds of video that a 1dp sideways slide moves at a slow, careful pace. The whole clip
 * takes [FULL_CLIP_SLIDE_DP] of sliding, about two portrait screen widths, so short clips don't
 * race to the end; long videos cap at [MAX_SCRUB_MILLIS_PER_DP] so small nudges stay small.
 */
fun scrubMillisPerDp(durationMillis: Long): Float =
    if (durationMillis <= 0L) {
        MAX_SCRUB_MILLIS_PER_DP
    } else {
        (durationMillis / FULL_CLIP_SLIDE_DP).coerceAtMost(MAX_SCRUB_MILLIS_PER_DP)
    }

/**
 * Faster slides cover more ground: up to [SLOW_SLIDE_DP_PER_SECOND] a slide moves at the base
 * rate, then the multiplier climbs to [MAX_SCRUB_GAIN] at [FAST_SLIDE_DP_PER_SECOND]. Careful
 * slides stay precise while a quick flick still crosses a long video.
 */
fun scrubGain(speedDpPerSecond: Float): Float {
    val t = (speedDpPerSecond - SLOW_SLIDE_DP_PER_SECOND) / (FAST_SLIDE_DP_PER_SECOND - SLOW_SLIDE_DP_PER_SECOND)
    return 1f + t.coerceIn(0f, 1f) * (MAX_SCRUB_GAIN - 1f)
}

/**
 * Position after one step of a sideways slide: [dragDp] dp at [speedDpPerSecond], from
 * [positionMillis]. Each step is clamped, so sliding back after reaching an end responds at once.
 */
fun scrubStep(positionMillis: Float, dragDp: Float, speedDpPerSecond: Float, durationMillis: Long): Float {
    val moved = positionMillis + dragDp * scrubMillisPerDp(durationMillis) * scrubGain(speedDpPerSecond)
    val atLeastZero = moved.coerceAtLeast(0f)
    return if (durationMillis > 0L) atLeastZero.coerceAtMost(durationMillis.toFloat()) else atLeastZero
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

private const val FULL_CLIP_SLIDE_DP = 800f
private const val MAX_SCRUB_MILLIS_PER_DP = 250f
private const val SLOW_SLIDE_DP_PER_SECOND = 400f
private const val FAST_SLIDE_DP_PER_SECOND = 2_500f
private const val MAX_SCRUB_GAIN = 3f
private const val SCRUB_END_MARGIN_MILLIS = 50L
private const val SEEK_LANDED_BEFORE_US = 1_000L
private const val SEEK_LANDED_AFTER_US = 250_000L
