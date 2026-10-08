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

/** The left third skips back, the right third skips forward, the middle toggles the controls. */
fun tapZone(x: Float, width: Float): TapZone = when {
    width <= 0f -> TapZone.CENTER
    x < width / 3f -> TapZone.BACK
    x > width * 2f / 3f -> TapZone.FORWARD
    else -> TapZone.CENTER
}

/** Clamps a seek target into the video. An unknown duration (zero or less) only clamps at the start. */
fun clampPosition(positionMillis: Long, durationMillis: Long): Long {
    val atLeastZero = positionMillis.coerceAtLeast(0L)
    return if (durationMillis > 0L) atLeastZero.coerceAtMost(durationMillis) else atLeastZero
}

/**
 * How far a sideways slide across the whole screen moves playback. Short clips map their full
 * length onto the screen width; longer ones cap at [MAX_SCRUB_SPAN_MILLIS] so fine control is kept.
 */
fun scrubSpanMillis(durationMillis: Long): Long =
    if (durationMillis <= 0L) MAX_SCRUB_SPAN_MILLIS else durationMillis.coerceAtMost(MAX_SCRUB_SPAN_MILLIS)

/** Target position for a sideways slide of [dragX] pixels that started at [startMillis]. */
fun scrubTarget(startMillis: Long, dragX: Float, width: Float, durationMillis: Long): Long {
    if (width <= 0f) return clampPosition(startMillis, durationMillis)
    val delta = (dragX / width * scrubSpanMillis(durationMillis)).toLong()
    return clampPosition(startMillis + delta, durationMillis)
}

/**
 * New volume as a 0..1 fraction after an upward slide of [dragUp] pixels; sliding the full
 * [height] of the screen goes from silent to full.
 */
fun volumeAfterDrag(startFraction: Float, dragUp: Float, height: Float): Float {
    if (height <= 0f) return startFraction.coerceIn(0f, 1f)
    return (startFraction + dragUp / height).coerceIn(0f, 1f)
}

private const val MAX_SCRUB_SPAN_MILLIS = 120_000L
