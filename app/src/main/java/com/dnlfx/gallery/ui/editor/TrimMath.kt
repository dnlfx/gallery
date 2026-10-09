package com.dnlfx.gallery.ui.editor

import java.util.Locale
import kotlin.math.abs

/** The part of a video to keep, in milliseconds from its start. */
data class TrimRange(val startMillis: Long, val endMillis: Long) {
    val lengthMillis: Long get() = endMillis - startMillis

    /** True when nothing is cut from either end of a [durationMillis] video. */
    fun isWhole(durationMillis: Long): Boolean = startMillis <= 0L && endMillis >= durationMillis

    /** Moves the start to [millis], keeping at least [minLength] before the end. */
    fun withStart(millis: Long, minLength: Long): TrimRange =
        copy(startMillis = millis.coerceIn(0L, (endMillis - minLength).coerceAtLeast(0L)))

    /** Moves the end to [millis], keeping at least [minLength] after the start. */
    fun withEnd(millis: Long, durationMillis: Long, minLength: Long): TrimRange =
        copy(endMillis = millis.coerceIn((startMillis + minLength).coerceAtMost(durationMillis), durationMillis))
}

/** Which trim handle a touch grabs. */
enum class TrimHandle { Start, End }

/**
 * The handle closest to a touch at [x] on a bar where the handles sit at [startX] and [endX],
 * if it's within [reach]. When both are in reach (a very short range), the one on the side the
 * touch is on wins, so either can still be pulled outwards.
 */
fun trimHandleAt(x: Float, startX: Float, endX: Float, reach: Float): TrimHandle? {
    val toStart = abs(x - startX)
    val toEnd = abs(x - endX)
    if (toStart > reach && toEnd > reach) return null
    return when {
        toStart > reach -> TrimHandle.End
        toEnd > reach -> TrimHandle.Start
        x < (startX + endX) / 2f -> TrimHandle.Start
        x > (startX + endX) / 2f -> TrimHandle.End
        else -> if (toStart <= toEnd) TrimHandle.Start else TrimHandle.End
    }
}

/**
 * The shortest trim allowed: half a second, or the whole video if it's shorter than that.
 */
fun minTrimLength(durationMillis: Long): Long = minOf(MIN_TRIM_MILLIS, durationMillis)

private const val MIN_TRIM_MILLIS = 500L

/** A trim point to the tenth of a second: 0:03.2, 12:34.5, 1:02:03.0. */
fun formatTrimTime(millis: Long): String {
    val tenths = (millis.coerceAtLeast(0) + 50) / 100
    val totalSeconds = tenths / 10
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths % 10)
    } else {
        String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths % 10)
    }
}
