package com.dnlfx.gallery.ui.viewer

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.thumbnail.viewerThumbnailRequest
import com.dnlfx.gallery.ui.grid.formatDuration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A still frame for video pages that aren't on screen, so only one player exists at a time. */
@Composable
fun VideoPoster(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(item.uri, item.dateModifiedSeconds) { viewerThumbnailRequest(context, item) },
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * The video on screen, with its touch controls:
 * - tap anywhere to show or hide the controls;
 * - double-tap the left third to go back 10 seconds, the right third to go forward 10 seconds,
 *   and keep tapping that side to add 10 more each time; double-tap the middle to play or pause;
 * - swipe sideways to move to the previous or next item, like on a photo;
 * - press and hold for a moment, then slide sideways, to scrub through the video;
 * - slide up or down to change the volume;
 * - pinch to zoom; while zoomed in, one finger moves the picture instead of scrubbing.
 *
 * Screen readers get the same skips and volume changes as actions on the video.
 */
@Composable
fun VideoPage(
    item: MediaItem,
    player: ExoPlayer,
    scrubber: Scrubber,
    playback: PlaybackState,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onTogglePlay: () -> Unit,
    onInteraction: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val toggleControls by rememberUpdatedState(onToggleControls)
    val togglePlay by rememberUpdatedState(onTogglePlay)
    val interacted by rememberUpdatedState(onInteraction)
    val scope = rememberCoroutineScope()

    var skip by remember { mutableStateOf<SkipFeedback?>(null) }
    var skipToken by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<ScrubFeedback?>(null) }
    var volume by remember { mutableStateOf<Float?>(null) }
    var volumeToken by remember { mutableIntStateOf(0) }
    var playFeedback by remember { mutableStateOf<Boolean?>(null) }
    var playToken by remember { mutableIntStateOf(0) }
    var zoom by remember(item.id) { mutableStateOf(VideoZoom()) }
    // The zoom changes on every frame of a pinch; the surface follows it on its own (see the
    // AndroidView below), so the page itself only cares whether it's zoomed in at all.
    val zoomed by remember(item.id) { derivedStateOf { zoom.zoomed } }
    var videoSize by remember { mutableStateOf(IntSize.Zero) }
    // While zoomed in, a swipe moves the picture rather than turning to the next item.
    val zoomedChanged by rememberUpdatedState(onZoomedChange)
    LaunchedEffect(zoomed) { zoomedChanged(zoomed) }
    // If this page goes away while zoomed in, swipes must not stay off for whatever comes next.
    DisposableEffect(Unit) { onDispose { zoomedChanged(false) } }

    LaunchedEffect(skipToken) {
        delay(FEEDBACK_MILLIS)
        skip = null
    }
    LaunchedEffect(volumeToken) {
        delay(FEEDBACK_MILLIS)
        volume = null
    }
    LaunchedEffect(playToken) {
        delay(FEEDBACK_MILLIS)
        playFeedback = null
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
            // A SurfaceView follows its view's scale and position, so zooming costs nothing extra.
            update = {
                it.scaleX = zoom.scale
                it.scaleY = zoom.scale
                it.translationX = zoom.x
                it.translationY = zoom.y
            },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier
                .align(Alignment.Center)
                .onSizeChanged { videoSize = it }
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

        val skipBackLabel = stringResource(R.string.viewer_a11y_skip_back)
        val skipForwardLabel = stringResource(R.string.viewer_a11y_skip_forward)
        val volumeUpLabel = stringResource(R.string.viewer_a11y_volume_up)
        val volumeDownLabel = stringResource(R.string.viewer_a11y_volume_down)
        val toggleLabel = stringResource(R.string.viewer_a11y_toggle_controls)
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    item.displayName?.let { contentDescription = it }
                    onClick(label = toggleLabel) {
                        toggleControls()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(skipBackLabel) {
                            interacted()
                            skip = skipBy(player, scrubber, skip, forward = false)
                            skipToken++
                            true
                        },
                        CustomAccessibilityAction(skipForwardLabel) {
                            interacted()
                            skip = skipBy(player, scrubber, skip, forward = true)
                            skipToken++
                            true
                        },
                        // The system volume panel announces the new level.
                        CustomAccessibilityAction(volumeUpLabel) {
                            audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_RAISE,
                                AudioManager.FLAG_SHOW_UI,
                            )
                            true
                        },
                        CustomAccessibilityAction(volumeDownLabel) {
                            audio.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI,
                            )
                            true
                        },
                    )
                }
                .pointerInput(player) {
                    val taps = TapSequence(viewConfiguration.doubleTapTimeoutMillis)
                    var pendingToggle: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = DragMode.NONE
                        var total = Offset.Zero
                        var multiTouch = false
                        var startPosition = 0L
                        var scrubPosition = 0f
                        val duration = playback.durationMillis
                        val velocity = VelocityTracker()
                        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                        var released = false
                        var upMillis = 0L
                        var lastMillis = down.uptimeMillis
                        velocity.addPosition(down.uptimeMillis, down.position)

                        try {
                            while (true) {
                                // Until the finger moves, wait to see whether it's held still long
                                // enough to start scrubbing.
                                val event = if (mode == DragMode.NONE && !multiTouch && !zoom.zoomed) {
                                    val holdLeft = viewConfiguration.longPressTimeoutMillis - (lastMillis - down.uptimeMillis)
                                    withTimeoutOrNull(holdLeft.coerceAtLeast(0L)) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }
                                if (event == null) {
                                    mode = DragMode.SCRUB
                                    interacted()
                                    startPosition = player.currentPosition
                                    scrubPosition = startPosition.toFloat()
                                    scrubber.begin()
                                    scrub = ScrubFeedback(startPosition, 0L, duration)
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    continue
                                }
                                if (event.changes.count { it.pressed } > 1) {
                                    multiTouch = true
                                    if (mode == DragMode.NONE) {
                                        mode = DragMode.ZOOM
                                        interacted()
                                    }
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    upMillis = change.uptimeMillis
                                    break
                                }
                                lastMillis = change.uptimeMillis
                                velocity.addPosition(change.uptimeMillis, change.position)
                                total += change.positionChange()
                                if (mode == DragMode.NONE && !multiTouch && total.getDistance() > viewConfiguration.touchSlop) {
                                    mode = when {
                                        zoom.zoomed -> DragMode.PAN
                                        // A swipe straight away is for the pager: leave it unconsumed.
                                        abs(total.x) > abs(total.y) -> DragMode.SWIPE
                                        else -> DragMode.VOLUME
                                    }
                                    if (mode != DragMode.SWIPE) interacted()
                                }
                                when (mode) {
                                    DragMode.SCRUB -> {
                                        val previous = scrubPosition
                                        scrubPosition = scrubStep(
                                            positionMillis = previous,
                                            dragDp = change.positionChange().x.toDp().value,
                                            speedDpPerSecond = abs(velocity.calculateVelocity().x).toDp().value,
                                            durationMillis = duration,
                                        )
                                        val target = scrubPosition.roundToLong()
                                        scrubber.moveTo(target)
                                        scrub = ScrubFeedback(target, target - startPosition, duration)
                                        // A tick when the slide reaches the start or the end.
                                        val atEnd = scrubPosition <= 0f || (duration > 0L && scrubPosition >= duration)
                                        if (atEnd && previous != scrubPosition) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
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
                                    DragMode.ZOOM -> {
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        if (centroid.isSpecified) {
                                            val pan = event.calculatePan()
                                            zoom = zoom.transformed(
                                                zoom = event.calculateZoom(),
                                                focusX = centroid.x - size.width / 2f,
                                                focusY = centroid.y - size.height / 2f,
                                                panX = pan.x,
                                                panY = pan.y,
                                                content = videoSize.toZoomSize(),
                                                container = size.toZoomSize(),
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    DragMode.PAN -> {
                                        val pan = change.positionChange()
                                        zoom = zoom.panned(pan.x, pan.y, videoSize.toZoomSize(), size.toZoomSize())
                                    }
                                    DragMode.NONE, DragMode.SWIPE -> Unit
                                }
                                if (mode != DragMode.NONE && mode != DragMode.SWIPE) change.consume()
                            }
                        } finally {
                            // Also runs if the gesture is cut short, so playback always resumes.
                            if (mode == DragMode.SCRUB) {
                                scrubber.finish()
                                scrub = null
                            }
                            if (mode == DragMode.ZOOM) zoom = zoom.settled()
                        }

                        if (mode != DragMode.NONE || multiTouch) {
                            taps.reset()
                        } else if (released) {
                            val zone = tapZone(down.position.x, size.width.toFloat())
                            val result = taps.onTap(zone, down.uptimeMillis, upMillis)
                            pendingToggle?.cancel()
                            pendingToggle = null
                            when (result) {
                                // Wait out the double-tap window before toggling, so a double tap
                                // that skips doesn't also flash the controls.
                                TapResult.PENDING -> pendingToggle = scope.launch {
                                    delay(viewConfiguration.doubleTapTimeoutMillis)
                                    toggleControls()
                                }
                                TapResult.SKIP_BACK -> {
                                    interacted()
                                    skip = skipBy(player, scrubber, skip, forward = false)
                                    skipToken++
                                }
                                TapResult.SKIP_FORWARD -> {
                                    interacted()
                                    skip = skipBy(player, scrubber, skip, forward = true)
                                    skipToken++
                                }
                                TapResult.TOGGLE_PLAY -> {
                                    interacted()
                                    togglePlay()
                                    playFeedback = player.playWhenReady
                                    playToken++
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
        // With the controls up, the play button in the middle already flips to show the new state,
        // so a second icon on top of it would only clash with it.
        playFeedback?.takeUnless { controlsVisible }?.let { playing ->
            Pill(Modifier.align(Alignment.Center)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.PlayArrow else ViewerIcons.Pause,
                    contentDescription = stringResource(if (playing) R.string.viewer_play else R.string.viewer_pause),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        // The scrub and volume readouts change with every finger movement, so they read their
        // state in their own scopes and leave the rest of the page alone.
        ScrubReadout(
            feedback = { scrub },
            // Sits above the play button when the controls are up, so the two don't overlap.
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (controlsVisible) Modifier.offset(y = (-88).dp) else Modifier),
        )
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
        VolumeReadout(volume = { volume }, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ScrubReadout(feedback: () -> ScrubFeedback?, modifier: Modifier = Modifier) {
    val current = feedback() ?: return
    Pill(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${formatDuration(current.targetMillis)} / ${formatDuration(current.durationMillis)}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            val sign = if (current.deltaMillis < 0) "-" else "+"
            Text(
                text = sign + formatDuration(abs(current.deltaMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun VolumeReadout(volume: () -> Float?, modifier: Modifier = Modifier) {
    val current = volume()
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        VolumeIndicator(current ?: 0f)
    }
}

/** Seeks 10 seconds, adding to the bubble already on screen when the user taps repeatedly. */
private fun skipBy(player: Player, scrubber: Scrubber, previous: SkipFeedback?, forward: Boolean): SkipFeedback {
    val duration = player.duration.takeIf { it > 0 } ?: 0L
    val step = if (forward) SKIP_MILLIS else -SKIP_MILLIS
    scrubber.jumpTo(clampPosition(player.currentPosition + step, duration))
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

private enum class DragMode { NONE, SCRUB, VOLUME, ZOOM, PAN, SWIPE }

private fun IntSize.toZoomSize() = VideoZoom.Size(width.toFloat(), height.toFloat())

private data class SkipFeedback(val forward: Boolean, val seconds: Int)

private data class ScrubFeedback(val targetMillis: Long, val deltaMillis: Long, val durationMillis: Long)

private const val FEEDBACK_MILLIS = 700L

