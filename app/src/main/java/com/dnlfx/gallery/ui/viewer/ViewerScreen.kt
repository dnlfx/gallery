package com.dnlfx.gallery.ui.viewer

import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.EXTERNAL_ITEM_ID
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.ui.canChangeMedia
import com.dnlfx.gallery.ui.editor.EditorScreen
import com.dnlfx.gallery.ui.findActivity
import com.dnlfx.gallery.ui.rememberMediaRequests
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as PlayerMediaItem

private const val CONTROLS_HIDE_MILLIS = 3_000L

/**
 * Full-screen viewer. Swipe sideways between items in grid order; photos zoom, videos play with
 * speed control and touch gestures (see [VideoPage]). Only one video player exists at a time.
 */
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startItemId: Long,
    onCurrentItemChange: (Long) -> Unit,
    onClose: () -> Unit,
    /** The grid cell the viewer opens out of, in screen coordinates; null to just appear. */
    openFrom: Rect? = null,
    /** Where an item's grid cell is on screen, so closing can shrink back into it. */
    boundsOf: (Long) -> Rect? = { null },
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestOnCurrentItemChange by rememberUpdatedState(onCurrentItemChange)

    val startIndex = remember { items.indexOfFirst { it.id == startItemId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var currentId by remember { mutableLongStateOf(startItemId) }
    // The item open in the built-in editor, and a copy it saved to show once it's in the library.
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var revealId by remember { mutableStateOf<Long?>(null) }
    val settledItem = items.getOrNull(pagerState.settledPage)

    // Opening grows the photo out of its grid cell; closing shrinks it back into the cell it
    // belongs to now. 0 is the cell, 1 is the photo filling the screen.
    val motion = MaterialTheme.motionScheme
    val reveal = remember { Animatable(if (openFrom != null) 0f else 1f) }
    var anchor by remember { mutableStateOf(openFrom) }
    var anchorAspect by remember { mutableFloatStateOf(items.firstOrNull { it.id == startItemId }?.uprightAspect() ?: 0f) }
    var closing by remember { mutableStateOf(false) }
    // How far a predictive back swipe has gone, and from which edge.
    val back = remember { Animatable(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) reveal.animateTo(1f, motion.defaultSpatialSpec())
    }
    val latestOnClose by rememberUpdatedState(onClose)
    val latestBoundsOf by rememberUpdatedState(boundsOf)
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            val current = latestItems.firstOrNull { it.id == currentId }
            val target = current?.let { latestBoundsOf(it.id) }
            val aspect = current?.uprightAspect() ?: 0f
            if (target != null && aspect > 0f) {
                anchor = target
                anchorAspect = aspect
                launch { back.animateTo(0f, motion.fastSpatialSpec()) }
                reveal.animateTo(0f, motion.fastSpatialSpec())
            }
            latestOnClose()
        }
    }

    // Android's predictive back: the viewer shrinks towards the swipe as it's dragged, then
    // closes into the grid on release, or springs back if the swipe is cancelled.
    PredictiveBackHandler { events ->
        try {
            events.collect { event ->
                backFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                back.snapTo(event.progress)
            }
            close()
        } catch (cancelled: CancellationException) {
            scope.launch { back.animateTo(0f, motion.fastSpatialSpec()) }
            throw cancelled
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestItems.getOrNull(page)?.let {
                currentId = it.id
                latestOnCurrentItemChange(it.id)
            }
        }
    }
    // The library can change underneath (a new photo, a deletion): stay on the same item, or move
    // to a copy the editor just saved once it shows up.
    LaunchedEffect(items, revealId) {
        if (items.isEmpty()) {
            onClose()
            return@LaunchedEffect
        }
        val revealIndex = revealId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
        if (revealIndex >= 0) {
            pagerState.scrollToPage(revealIndex)
            currentId = items[revealIndex].id
            latestOnCurrentItemChange(currentId)
            revealId = null
            return@LaunchedEffect
        }
        val index = items.indexOfFirst { it.id == currentId }
        if (index >= 0) {
            if (index != pagerState.currentPage) pagerState.scrollToPage(index)
        } else {
            // The item on screen was moved to the trash: the pager now shows its neighbour.
            items.getOrNull(pagerState.currentPage.coerceAtMost(items.lastIndex))?.let {
                currentId = it.id
                latestOnCurrentItemChange(it.id)
            }
        }
    }

    val video = settledItem?.takeIf { it.type == MediaType.VIDEO }
    // Making a player loads the whole video stack, so a viewer opened on a photo doesn't make one
    // until it first reaches a video. From then on it stays until the viewer closes, so swiping
    // between photos and videos doesn't keep rebuilding it.
    var playerNeeded by remember { mutableStateOf(false) }
    if (video != null && !playerNeeded) SideEffect { playerNeeded = true }
    val player = if (playerNeeded || video != null) rememberViewerPlayer() else null
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player?.pause() }
    val playback = rememberPlaybackState(player)
    val scrubber = player?.let { rememberScrubber(it, playback) }

    // Chosen speed carries over from one video to the next until the viewer is closed.
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    LaunchedEffect(speed, player) { player?.setPlaybackSpeed(speed) }
    // Looping is a lasting choice: it applies to every video until it's turned off again.
    var loop by rememberLoopVideos()
    LaunchedEffect(loop, player) { player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    LaunchedEffect(video?.id, player) {
        if (player == null) return@LaunchedEffect
        if (video == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            val (width, height) = uprightSize(video.width, video.height, video.orientationDegrees)
            playback.resetForNewItem(video.id, width, height)
            player.setMediaItem(PlayerMediaItem.fromUri(video.uri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    // 0 normally, rising towards 1 as a photo is pulled down to close the viewer. It changes every
    // frame of the pull, so only the backdrop's drawing and this check read it.
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    val dismissing by remember { derivedStateOf { dismissProgress > 0f } }
    val requests = rememberMediaRequests()
    val haptics = LocalHapticFeedback.current
    var interactions by remember { mutableIntStateOf(0) }
    var draggingSeekBar by remember { mutableStateOf(false) }
    var videoZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible, playback.isPlaying, interactions, draggingSeekBar, video?.id) {
        if (controlsVisible && video != null && playback.isPlaying && !draggingSeekBar) {
            delay(CONTROLS_HIDE_MILLIS)
            controlsVisible = false
        }
    }

    SystemBarsEffect(visible = controlsVisible)
    // Only photos need it. A video page left in HDR mode makes the screen flare up as it opens;
    // an HDR video shows in HDR either way.
    HdrEffect(enabled = settledItem?.type == MediaType.IMAGE)
    DisposableEffect(playback.isPlaying) {
        view.keepScreenOn = playback.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val activity = remember { context.findActivity() }
    // A brightness slide lasts only while its video is open: back to the phone's brightness as
    // soon as a swipe heads for another item, and when the viewer closes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage != pagerState.settledPage }
            .collect { leaving -> if (leaving) activity?.window?.resetBrightness() }
    }
    DisposableEffect(activity) {
        onDispose { activity?.window?.resetBrightness() }
    }
    val originalOrientation = remember { activity?.requestedOrientation }
    DisposableEffect(activity) {
        onDispose { originalOrientation?.let { activity?.requestedOrientation = it } }
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Android 16 and later ignore orientation requests on tablets and unfolded foldables, so
    // turning the screen is only offered on a phone-sized screen.
    val canRotate = configuration.smallestScreenWidthDp < LARGE_SCREEN_DP
    val density = LocalDensity.current
    val revealCorner = with(density) { REVEAL_CORNER.toPx() }
    val backShift = with(density) { BACK_SHIFT.toPx() }
    val revealed by remember { derivedStateOf { reveal.value > 0.9f && !closing } }
    val chromeVisible = controlsVisible && settledItem != null && !dismissing && revealed

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val shown = reveal.value.coerceIn(0f, 1f) * (1f - BACK_DIM * back.value)
                drawRect(Color.Black.copy(alpha = (1f - dismissProgress) * shown))
            },
    ) {
        HorizontalPager(
            state = pagerState,
            key = { latestItems[it].id },
            beyondViewportPageCount = 1,
            // Swipes turn the page on videos too (holding first scrubs instead), except while a
            // video is zoomed in and a swipe moves the picture.
            userScrollEnabled = !videoZoomed && !closing,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    applyRevealAndBack(
                        reveal = reveal.value,
                        anchor = anchor,
                        aspect = anchorAspect,
                        back = back.value,
                        backFromLeft = backFromLeft,
                        backShift = backShift,
                        corner = revealCorner,
                    )
                },
        ) { page ->
            val item = latestItems[page]
            val onCurrentPage = page == pagerState.settledPage
            when (item.type) {
                // Starts afresh when the photo itself changes, like after saving a crop over it.
                MediaType.IMAGE -> key(item.dateModifiedSeconds) {
                    ZoomableImage(
                        item = item,
                        isCurrentPage = onCurrentPage,
                        onTap = { controlsVisible = !controlsVisible },
                        onDismissProgress = { dismissProgress = it },
                        onDismiss = onClose,
                    )
                }
                MediaType.VIDEO -> if (onCurrentPage && item.id == video?.id && player != null && scrubber != null) {
                    VideoPage(
                        item = item,
                        player = player,
                        scrubber = scrubber,
                        playback = playback,
                        controlsVisible = controlsVisible,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onTogglePlay = { togglePlayback(player) },
                        onInteraction = { interactions++ },
                        onZoomedChange = { videoZoomed = it },
                        onDismissProgress = { dismissProgress = it },
                        onDismiss = onClose,
                    )
                } else {
                    VideoPoster(item)
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            settledItem?.let { item ->
                ViewerTopBar(
                    item = item,
                    onBack = ::close,
                    // Offered while a video is paused on the frame to keep.
                    onSaveFrame = if (video != null && player != null && item.id == video.id && (!playback.playWhenReady || playback.ended)) {
                        {
                            val position = player.currentPosition
                            scope.launch {
                                val saved = saveVideoFrame(context, video, position)
                                Toast.makeText(
                                    context,
                                    if (saved) R.string.viewer_frame_saved else R.string.viewer_frame_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    } else {
                        null
                    },
                    onRotate = if (canRotate) {
                        {
                            activity?.requestedOrientation = if (landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                            interactions++
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (video != null && player != null && scrubber != null) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f),
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                PlayPauseButton(
                    playing = playback.playWhenReady && !playback.ended,
                    onClick = {
                        togglePlayback(player)
                        interactions++
                    },
                )
            }
            if (!controlsVisible && !dismissing && revealed) {
                HairlineProgress(playback, Modifier.align(Alignment.BottomCenter))
            }
        }

        // The bottom: a video's seek bar and playback options, then the item's actions.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(motion.defaultEffectsSpec()) +
                slideInVertically(motion.defaultSpatialSpec()) { it / 3 },
            exit = fadeOut(motion.fastEffectsSpec()) +
                slideOutVertically(motion.fastSpatialSpec()) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (video != null && player != null && scrubber != null) {
                    VideoBottomBar(
                        playback = playback,
                        speed = speed,
                        onSpeedChange = {
                            speed = it
                            interactions++
                        },
                        loop = loop,
                        onLoopChange = { loop = it },
                        onSeek = {
                            val target = clampPosition(it, playback.durationMillis)
                            // A drag along the bar previews like a scrub; a tap jumps straight there.
                            scrubber.jumpTo(target)
                        },
                        onSeekStart = {
                            draggingSeekBar = true
                            scrubber.begin()
                        },
                        onSeekEnd = {
                            draggingSeekBar = false
                            scrubber.finish()
                        },
                        onInteraction = { interactions++ },
                    )
                }
                settledItem?.let { item ->
                    val canStar = canChangeMedia && item.id != EXTERNAL_ITEM_ID
                    ViewerActionsToolbar(
                        isFavorite = item.isFavorite,
                        // Only library items can be starred or trashed, not files other apps handed over.
                        onFavorite = if (canStar) {
                            {
                                haptics.performHapticFeedback(
                                    if (item.isFavorite) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                )
                                requests.favorite(listOf(item.uri), !item.isFavorite)
                                interactions++
                            }
                        } else {
                            null
                        },
                        // Animated GIFs would lose their animation, so they aren't edited.
                        onEdit = if (item.mimeType != "image/gif") {
                            {
                                player?.pause()
                                controlsVisible = true
                                editing = item
                            }
                        } else {
                            null
                        },
                        onInfo = { detailsOpen = true },
                        onTrash = if (canStar) {
                            { requests.trash(listOf(item.uri)) }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        editing?.let { item ->
            EditorScreen(
                item = item,
                onClose = { editing = null },
                onSaved = { uri ->
                    editing = null
                    revealId = ContentUris.parseId(uri)
                },
            )
        }
    }

    if (detailsOpen) {
        settledItem?.let { DetailsSheet(it, onDismiss = { detailsOpen = false }) }
    }
}

/** Plays or pauses; a finished video starts again from the beginning. */
private fun togglePlayback(player: Player) {
    when {
        player.playbackState == Player.STATE_ENDED -> {
            player.seekTo(0L)
            player.play()
        }
        player.playWhenReady -> player.pause()
        else -> player.play()
    }
}

/** The upright shape of [this] item, width over height, or 0 when unknown. */
private fun MediaItem.uprightAspect(): Float {
    val (w, h) = uprightSize(width, height, orientationDegrees)
    return if (w > 0 && h > 0) w.toFloat() / h else 0f
}

/**
 * Lays the pager out between its grid cell ([anchor], at [reveal] 0) and full screen ([reveal]
 * 1): the photo is cropped to the cell's shape and scaled onto it, then opens out to its fitted
 * size. A predictive back swipe ([back] 0 to 1) then shrinks it towards the swiping edge.
 */
private fun GraphicsLayerScope.applyRevealAndBack(
    reveal: Float,
    anchor: Rect?,
    aspect: Float,
    back: Float,
    backFromLeft: Boolean,
    backShift: Float,
    corner: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val backScale = 1f - BACK_SHRINK * back
    var scale = backScale
    var tx = (if (backFromLeft) 1f else -1f) * backShift * back
    var ty = 0f
    var clipWidth = w
    var clipHeight = h
    var radius = corner * back * 2f
    if (reveal < 1f && anchor != null && aspect > 0f) {
        val t = reveal.coerceAtLeast(0f)
        // The photo as it sits fitted on screen.
        val fitW = if (w / h > aspect) h * aspect else w
        val fitH = if (w / h > aspect) h else w / aspect
        // The largest part of it with the cell's shape: what the grid's crop shows.
        val cellAspect = anchor.width / anchor.height
        val cropW = if (fitW / fitH > cellAspect) fitH * cellAspect else fitW
        val cropH = if (fitW / fitH > cellAspect) fitH else fitW / cellAspect
        clipWidth = lerp(cropW, fitW, t)
        clipHeight = lerp(cropH, fitH, t)
        scale *= lerp(anchor.width / cropW, 1f, t)
        tx += lerp(anchor.center.x - w / 2f, 0f, t)
        ty += lerp(anchor.center.y - h / 2f, 0f, t)
        radius = corner * (1f - t) / scale.coerceAtLeast(0.01f)
    }
    scaleX = scale
    scaleY = scale
    translationX = tx
    translationY = ty
    if (clipWidth < w || clipHeight < h || radius > 0f) {
        clip = true
        shape = CenteredRoundRect(clipWidth, clipHeight, radius)
    } else {
        clip = false
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/** A rounded rectangle of a set size in the middle of whatever it clips. */
private class CenteredRoundRect(val width: Float, val height: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        return Outline.Rounded(RoundRect(left, top, left + width, top + height, CornerRadius(radius)))
    }
}

private const val LARGE_SCREEN_DP = 600
private const val BACK_SHRINK = 0.1f
private const val BACK_DIM = 0.35f
private val BACK_SHIFT = 16.dp
private val REVEAL_CORNER = 16.dp

/**
 * Shows Ultra HDR photos (what a Pixel's camera saves) with their full brightness, like Photos,
 * while [enabled]. Ordinary photos and the rest of the app look the same.
 */
@Composable
private fun HdrEffect(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val view = LocalView.current
    val window = remember { view.context.findActivity()?.window } ?: return
    DisposableEffect(window, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val previous = window.colorMode
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose { window.colorMode = previous }
    }
}

/** Hides the status and navigation bars along with the controls; a swipe from the edge peeks them. */
@Composable
private fun SystemBarsEffect(visible: Boolean) {
    val view = LocalView.current
    val window = remember { view.context.findActivity()?.window } ?: return
    val controller = remember { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val lightStatusBars = controller.isAppearanceLightStatusBars
        val lightNavigationBars = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // White icons over the black viewer.
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatusBars
            controller.isAppearanceLightNavigationBars = lightNavigationBars
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
