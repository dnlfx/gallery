package com.dnlfx.gallery.ui.viewer

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.dnlfx.gallery.ui.findActivity
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
 * - press and hold for a moment to play at double speed until the finger lifts, or hold and then
 *   slide sideways to scrub through the video;
 * - slide up or down on the left third to change the brightness, or on the right third to change
 *   the volume; pull down in the middle to close the video, like a photo;
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
    onDismissProgress: (Float) -> Unit,
    onDismiss: () -> Unit,
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
    var fastForwarding by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf<Float?>(null) }
    var volumeToken by remember { mutableIntStateOf(0) }
    val window = remember { context.findActivity()?.window }
    var brightness by remember { mutableStateOf<Float?>(null) }
    var brightnessToken by remember { mutableIntStateOf(0) }
    // How far a pull down in the middle has moved the video towards closing.
    var dismissOffset by remember(item.id) { mutableFloatStateOf(0f) }
    var pageHeight by remember { mutableIntStateOf(0) }
    fun dismissProgress() = (dismissOffset / pageHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
    val dismissProgressChanged by rememberUpdatedState(onDismissProgress)
    val dismissed by rememberUpdatedState(onDismiss)
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
    LaunchedEffect(brightnessToken) {
        delay(FEEDBACK_MILLIS)
        brightness = null
    }
    LaunchedEffect(playToken) {
        delay(FEEDBACK_MILLIS)
        playFeedback = null
    }

    // The page is on screen a moment before the player is handed this video. Until then the
    // player still holds the last one, and would draw its frames into this page's surface.
    val playerOnThisVideo = playback.itemId == item.id
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(surfaceView, playerOnThisVideo) {
        val view = surfaceView
        if (view == null || !playerOnThisVideo) return@DisposableEffect onDispose {}
        player.setVideoSurfaceView(view)
        onDispose { player.clearVideoSurfaceView(view) }
    }
    // The thumbnail covers the surface, which stays black until the first frame. It then fades
    // away rather than vanishing, so the switch from thumbnail to video doesn't flicker: the
    // thumbnail can be a different frame, and the video may be in HDR.
    val frameShown = playerOnThisVideo && playback.firstFrameRendered
    val posterAlpha = animateFloatAsState(
        targetValue = if (frameShown) 0f else 1f,
        animationSpec = tween(POSTER_FADE_MILLIS),
        label = "poster",
    )
    val posterVisible by remember { derivedStateOf { posterAlpha.value > 0f } }

    // The viewer behind draws the black backdrop, which fades out as the video is pulled down.
    Box(modifier.fillMaxSize().onSizeChanged { pageHeight = it.height }) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { surfaceView = it } },
            // A SurfaceView follows its view's scale and position, so zooming and pulling down to
            // close cost nothing extra. It shrinks a little as it's pulled, like a photo.
            update = {
                // The surface keeps the video's own size and the screen scales it to fit the view.
                // Sized to the view instead, a resize (a new video's shape, the bars coming and
                // going) left the last frame drawn at the old size, squeezed into part of the
                // view, until the next frame arrived, which for a paused video is never.
                if (playback.frameWidth > 0 && playback.frameHeight > 0) {
                    it.holder.setFixedSize(playback.frameWidth, playback.frameHeight)
                } else {
                    it.holder.setSizeFromLayout()
                }
                val shrink = 1f - DISMISS_SHRINK * dismissProgress()
                it.scaleX = zoom.scale * shrink
                it.scaleY = zoom.scale * shrink
                it.translationX = zoom.x
                it.translationY = zoom.y + dismissOffset
            },
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
        if (posterVisible) {
            VideoPoster(
                item,
                Modifier.graphicsLayer {
                    alpha = posterAlpha.value
                    val shrink = 1f - DISMISS_SHRINK * dismissProgress()
                    scaleX = shrink
                    scaleY = shrink
                    translationY = dismissOffset
                },
            )
        }

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
                        var startBrightness = 0f
                        var released = false
                        var upMillis = 0L
                        var holdTotal = Offset.Zero
                        var speedBeforeHold = 1f
                        var playingBeforeHold = false
                        var lastMillis = down.uptimeMillis
                        velocity.addPosition(down.uptimeMillis, down.position)

                        fun startScrub() {
                            mode = DragMode.SCRUB
                            startPosition = player.currentPosition
                            scrubPosition = startPosition.toFloat()
                            scrubber.begin()
                            scrub = ScrubFeedback(startPosition, 0L, duration)
                        }

                        fun endFastForward() {
                            player.setPlaybackSpeed(speedBeforeHold)
                            player.playWhenReady = playingBeforeHold
                            fastForwarding = false
                        }

                        try {
                            while (true) {
                                // Until the finger moves, wait to see whether it's held still long
                                // enough to play fast.
                                val event = if (mode == DragMode.NONE && !multiTouch && !zoom.zoomed) {
                                    val holdLeft = viewConfiguration.longPressTimeoutMillis - (lastMillis - down.uptimeMillis)
                                    withTimeoutOrNull(holdLeft.coerceAtLeast(0L)) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }
                                if (event == null) {
                                    interacted()
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    if (player.playbackState == Player.STATE_ENDED) {
                                        // Nothing left to play fast: go straight to scrubbing.
                                        startScrub()
                                    } else {
                                        // Held still: play at double speed, even if paused, until
                                        // the finger lifts or starts to slide.
                                        mode = DragMode.FAST_FORWARD
                                        holdTotal = total
                                        speedBeforeHold = player.playbackParameters.speed
                                        playingBeforeHold = player.playWhenReady
                                        player.setPlaybackSpeed(HOLD_SPEED)
                                        player.playWhenReady = true
                                        fastForwarding = true
                                    }
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
                                        else -> when (verticalSlide(tapZone(down.position.x, size.width.toFloat()))) {
                                            VerticalSlide.BRIGHTNESS -> DragMode.BRIGHTNESS
                                            VerticalSlide.CLOSE -> DragMode.DISMISS
                                            VerticalSlide.VOLUME -> DragMode.VOLUME
                                        }
                                    }
                                    if (mode == DragMode.BRIGHTNESS) startBrightness = window?.currentBrightness() ?: 0f
                                    if (mode != DragMode.SWIPE && mode != DragMode.DISMISS) interacted()
                                }
                                when (mode) {
                                    DragMode.FAST_FORWARD -> {
                                        // Sliding after the hold scrubs, as it always has.
                                        if ((total - holdTotal).getDistance() > viewConfiguration.touchSlop) {
                                            endFastForward()
                                            startScrub()
                                        }
                                    }
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
                                            val fraction = levelAfterDrag(startVolume, -total.y, size.height.toFloat())
                                            audio.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                (fraction * maxVolume).roundToInt(),
                                                0,
                                            )
                                            volume = fraction
                                            volumeToken++
                                        }
                                    }
                                    DragMode.BRIGHTNESS -> {
                                        if (window != null) {
                                            val fraction = levelAfterDrag(startBrightness, -total.y, size.height.toFloat())
                                            window.setBrightness(fraction)
                                            brightness = fraction
                                            brightnessToken++
                                        }
                                    }
                                    DragMode.DISMISS -> {
                                        dismissOffset = (dismissOffset + change.positionChange().y).coerceAtLeast(0f)
                                        dismissProgressChanged(dismissProgress())
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
                            // Also runs if the gesture is cut short, so playback always goes back to
                            // normal.
                            if (mode == DragMode.FAST_FORWARD) endFastForward()
                            if (mode == DragMode.SCRUB) {
                                scrubber.finish()
                                scrub = null
                            }
                            if (mode == DragMode.ZOOM) zoom = zoom.settled()
                        }

                        if (mode == DragMode.DISMISS) {
                            // Pulled far enough or flung down: close. Otherwise spring back into place.
                            val flung = velocity.calculateVelocity().y > DISMISS_FLING_DP_PER_SECOND.dp.toPx()
                            val close = released && (flung || dismissProgress() > DISMISS_DISTANCE)
                            scope.launch {
                                animate(
                                    initialValue = dismissOffset,
                                    targetValue = if (close) size.height.toFloat() else 0f,
                                    animationSpec = if (close) tween(DISMISS_MILLIS) else spring(),
                                ) { value, _ ->
                                    dismissOffset = value
                                    dismissProgressChanged(dismissProgress())
                                }
                                if (close) dismissed()
                            }
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
        if (fastForwarding) FastForwardBadge(Modifier.align(Alignment.TopCenter))
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
        // Each level shows on the side of the screen whose slide changes it.
        LevelReadout(
            level = { volume },
            icon = { if (it <= 0f) ViewerIcons.VolumeOff else ViewerIcons.VolumeUp },
            description = R.string.viewer_volume,
            modifier = Modifier.align(Alignment.CenterEnd).levelReadoutPadding(),
        )
        LevelReadout(
            level = { brightness },
            icon = { ViewerIcons.Brightness },
            description = R.string.viewer_brightness,
            modifier = Modifier.align(Alignment.CenterStart).levelReadoutPadding(),
        )
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
private fun LevelReadout(
    level: () -> Float?,
    icon: (Float) -> ImageVector,
    @StringRes description: Int,
    modifier: Modifier = Modifier,
) {
    val current = level()
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        LevelIndicator(current ?: 0f, icon, description)
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

/** Shows while a held finger plays the video at double speed. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FastForwardBadge(modifier: Modifier = Modifier) {
    Pill(
        modifier
            .windowInsetsPadding(
                WindowInsets.systemBarsIgnoringVisibility
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top),
            )
            // Below the top bar, when it's up.
            .padding(top = 72.dp),
    ) {
        Text(
            text = stringResource(R.string.viewer_hold_speed),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** Clear of the navigation bar and camera cutout, which sit at the sides in landscape. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Modifier.levelReadoutPadding(): Modifier = this
    .windowInsetsPadding(
        WindowInsets.systemBarsIgnoringVisibility
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    )
    .padding(horizontal = 24.dp)

/** An upright bar that fills from the bottom, with its icon underneath. */
@Composable
private fun LevelIndicator(fraction: Float, icon: (Float) -> ImageVector, @StringRes description: Int) {
    Pill {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(140.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .background(Color.White),
                )
            }
            Icon(
                imageVector = icon(fraction),
                contentDescription = stringResource(description),
                tint = Color.White,
            )
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

private enum class DragMode { NONE, FAST_FORWARD, SCRUB, VOLUME, BRIGHTNESS, DISMISS, ZOOM, PAN, SWIPE }

private fun IntSize.toZoomSize() = VideoZoom.Size(width.toFloat(), height.toFloat())

private data class SkipFeedback(val forward: Boolean, val seconds: Int)

private data class ScrubFeedback(val targetMillis: Long, val deltaMillis: Long, val durationMillis: Long)

private const val FEEDBACK_MILLIS = 700L
private const val HOLD_SPEED = 2f
private const val POSTER_FADE_MILLIS = 150

