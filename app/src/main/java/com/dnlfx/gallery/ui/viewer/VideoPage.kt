package com.dnlfx.gallery.ui.viewer

import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import coil3.compose.AsyncImage
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.thumbnail.MediaThumbnail
import com.dnlfx.gallery.ui.grid.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** A still frame for video pages that aren't on screen, so only one player exists at a time. */
@Composable
fun VideoPoster(item: MediaItem, modifier: Modifier = Modifier) {
    AsyncImage(
        model = MediaThumbnail(item.uri, item.dateModifiedSeconds),
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * The video on screen, with its touch controls:
 * - tap the left third to go back 10 seconds, the right third to go forward 10 seconds,
 *   the middle to show or hide the controls;
 * - slide sideways anywhere to scrub through the video;
 * - slide up or down to change the volume.
 */
@Composable
fun VideoPage(
    item: MediaItem,
    player: ExoPlayer,
    playback: PlaybackState,
    onToggleControls: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val toggleControls by rememberUpdatedState(onToggleControls)
    val interacted by rememberUpdatedState(onInteraction)

    var skip by remember { mutableStateOf<SkipFeedback?>(null) }
    var skipToken by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<ScrubFeedback?>(null) }
    var volume by remember { mutableStateOf<Float?>(null) }
    var volumeToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(skipToken) {
        delay(FEEDBACK_MILLIS)
        skip = null
    }
    LaunchedEffect(volumeToken) {
        delay(FEEDBACK_MILLIS)
        volume = null
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier
                .align(Alignment.Center)
                .then(
                    if (playback.aspectRatio > 0f) {
                        Modifier.aspectRatio(playback.aspectRatio)
                    } else {
                        Modifier.fillMaxSize()
                    },
                ),
        )
        // The surface stays black until the first frame, so cover it with the thumbnail until then.
        if (!playback.firstFrameRendered) VideoPoster(item)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = DragMode.NONE
                        var total = Offset.Zero
                        var multiTouch = false
                        val startPosition = player.currentPosition
                        val duration = playback.durationMillis
                        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                        var resumeAfterScrub = false
                        var lastSeek = startPosition
                        var released = false

                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) multiTouch = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            total += change.positionChange()
                            if (mode == DragMode.NONE && !multiTouch && total.getDistance() > viewConfiguration.touchSlop) {
                                mode = if (abs(total.x) > abs(total.y)) DragMode.SCRUB else DragMode.VOLUME
                                interacted()
                                if (mode == DragMode.SCRUB) {
                                    resumeAfterScrub = player.playWhenReady
                                    player.pause()
                                    // Keyframe seeks keep up with a moving finger; the final seek is exact.
                                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                }
                            }
                            when (mode) {
                                DragMode.SCRUB -> {
                                    val target = scrubTarget(startPosition, total.x, size.width.toFloat(), duration)
                                    if (abs(target - lastSeek) >= SCRUB_SEEK_STEP_MILLIS) {
                                        player.seekTo(target)
                                        lastSeek = target
                                    }
                                    scrub = ScrubFeedback(target, target - startPosition, duration)
                                }
                                DragMode.VOLUME -> {
                                    if (!audio.isVolumeFixed) {
                                        val fraction = volumeAfterDrag(startVolume, -total.y, size.height.toFloat())
                                        audio.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            (fraction * maxVolume).roundToInt(),
                                            0,
                                        )
                                        volume = fraction
                                        volumeToken++
                                    }
                                }
                                DragMode.NONE -> Unit
                            }
                            if (mode != DragMode.NONE) change.consume()
                        }

                        when {
                            mode == DragMode.SCRUB -> {
                                player.setSeekParameters(SeekParameters.EXACT)
                                scrub?.let { player.seekTo(it.targetMillis) }
                                if (resumeAfterScrub) player.play()
                                scrub = null
                            }
                            mode == DragMode.NONE && released && !multiTouch -> {
                                when (tapZone(down.position.x, size.width.toFloat())) {
                                    TapZone.CENTER -> toggleControls()
                                    TapZone.BACK -> {
                                        interacted()
                                        skip = skipBy(player, skip, forward = false)
                                        skipToken++
                                    }
                                    TapZone.FORWARD -> {
                                        interacted()
                                        skip = skipBy(player, skip, forward = true)
                                        skipToken++
                                    }
                                }
                            }
                        }
                    }
                },
        )

        skip?.let { feedback ->
            SkipBubble(
                feedback = feedback,
                modifier = Modifier
                    .align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp),
            )
        }
        scrub?.let { feedback ->
            Pill(Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${formatDuration(feedback.targetMillis)} / ${formatDuration(feedback.durationMillis)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    val sign = if (feedback.deltaMillis < 0) "-" else "+"
                    Text(
                        text = sign + formatDuration(abs(feedback.deltaMillis)),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (playback.failed) {
            // Below the play button, which sits in the center.
            Pill(Modifier.align(Alignment.Center).padding(top = 160.dp)) {
                Text(
                    text = stringResource(R.string.viewer_video_error),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }
        AnimatedVisibility(
            visible = volume != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            VolumeIndicator(volume ?: 0f)
        }
    }
}

/** Seeks 10 seconds, adding to the bubble already on screen when the user taps repeatedly. */
private fun skipBy(player: Player, previous: SkipFeedback?, forward: Boolean): SkipFeedback {
    val duration = player.duration.takeIf { it > 0 } ?: 0L
    val step = if (forward) SKIP_MILLIS else -SKIP_MILLIS
    player.seekTo(clampPosition(player.currentPosition + step, duration))
    val seconds = if (previous != null && previous.forward == forward) previous.seconds + 10 else 10
    return SkipFeedback(forward, seconds)
}

@Composable
private fun SkipBubble(feedback: SkipFeedback, modifier: Modifier = Modifier) {
    Pill(modifier) {
        Text(
            text = stringResource(
                if (feedback.forward) R.string.viewer_skip_forward else R.string.viewer_skip_back,
                feedback.seconds,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun VolumeIndicator(fraction: Float) {
    Pill {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (fraction <= 0f) ViewerIcons.VolumeOff else ViewerIcons.VolumeUp,
                contentDescription = stringResource(R.string.viewer_volume),
                tint = Color.White,
            )
            Box(
                Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private enum class DragMode { NONE, SCRUB, VOLUME }

private data class SkipFeedback(val forward: Boolean, val seconds: Int)

private data class ScrubFeedback(val targetMillis: Long, val deltaMillis: Long, val durationMillis: Long)

private const val FEEDBACK_MILLIS = 700L
private const val SCRUB_SEEK_STEP_MILLIS = 100L

