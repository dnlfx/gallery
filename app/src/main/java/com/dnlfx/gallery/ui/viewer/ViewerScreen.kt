package com.dnlfx.gallery.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.EXTERNAL_ITEM_ID
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.ui.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.media3.common.MediaItem as PlayerMediaItem

private const val CONTROLS_HIDE_MILLIS = 3_000L
private const val LOCAL_START_BUFFER_MILLIS = 250

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
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestOnCurrentItemChange by rememberUpdatedState(onCurrentItemChange)

    val startIndex = remember { items.indexOfFirst { it.id == startItemId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var currentId by remember { mutableLongStateOf(startItemId) }
    val settledItem = items.getOrNull(pagerState.settledPage)

    BackHandler(onBack = onClose)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestItems.getOrNull(page)?.let {
                currentId = it.id
                latestOnCurrentItemChange(it.id)
            }
        }
    }
    // The library can change underneath (a new photo, a deletion): stay on the same item.
    LaunchedEffect(items) {
        if (items.isEmpty()) {
            onClose()
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

    val player = remember {
        // If the phone's preferred decoder can't take a file (an unusual HEVC profile, say),
        // try the next one instead of failing.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Everything plays from the phone's own storage, which reads far faster than playback
            // needs, so start (and restart after a seek or scrub) once a quarter second is ready
            // instead of the default full second.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMsForLocalPlayback(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
                        LOCAL_START_BUFFER_MILLIS,
                        LOCAL_START_BUFFER_MILLIS,
                    )
                    .build(),
            )
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val playback = rememberPlaybackState(player)
    val scrubber = rememberScrubber(player, playback)

    // Chosen speed carries over from one video to the next until the viewer is closed.
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    val video = settledItem?.takeIf { it.type == MediaType.VIDEO }
    LaunchedEffect(video?.id) {
        if (video == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            playback.resetForNewItem()
            player.setMediaItem(PlayerMediaItem.fromUri(video.uri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    // 0 normally, rising towards 1 as a photo is pulled down to close the viewer.
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    val trash = rememberTrashRequest()
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
    DisposableEffect(playback.isPlaying) {
        view.keepScreenOn = playback.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val activity = remember { context.findActivity() }
    val originalOrientation = remember { activity?.requestedOrientation }
    DisposableEffect(activity) {
        onDispose { originalOrientation?.let { activity?.requestedOrientation = it } }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - dismissProgress)),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { latestItems[it].id },
            beyondViewportPageCount = 1,
            // Swipes turn the page on videos too (holding first scrubs instead), except while a
            // video is zoomed in and a swipe moves the picture.
            userScrollEnabled = !videoZoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = latestItems[page]
            val onCurrentPage = page == pagerState.settledPage
            when (item.type) {
                MediaType.IMAGE -> ZoomableImage(
                    item = item,
                    isCurrentPage = onCurrentPage,
                    onTap = { controlsVisible = !controlsVisible },
                    onDismissProgress = { dismissProgress = it },
                    onDismiss = onClose,
                )
                MediaType.VIDEO -> if (onCurrentPage && item.id == video?.id) {
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
                    )
                } else {
                    VideoPoster(item)
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && settledItem != null && dismissProgress == 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            settledItem?.let { item ->
                ViewerTopBar(
                    item = item,
                    onBack = onClose,
                    onInfo = { detailsOpen = true },
                    // Offered while a video is paused on the frame to keep.
                    onSaveFrame = if (video != null && item.id == video.id && (!playback.playWhenReady || playback.ended)) {
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
                    onTrash = if (canTrash && item.id != EXTERNAL_ITEM_ID) {
                        { trash(item.uri) }
                    } else {
                        null
                    },
                    onRotate = {
                        activity?.requestedOrientation = if (landscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        interactions++
                    },
                )
            }
        }

        if (video != null) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
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
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val page = pagerState.settledPage
                VideoBottomBar(
                    playback = playback,
                    speed = speed,
                    onSpeedChange = {
                        speed = it
                        interactions++
                    },
                    onSeek = {
                        val target = clampPosition(it, playback.durationMillis)
                        // A drag along the bar previews like a sideways slide; a tap jumps exactly.
                        if (scrubber.active) scrubber.moveTo(target) else player.seekTo(target)
                    },
                    onSeekStart = {
                        draggingSeekBar = true
                        scrubber.begin()
                    },
                    onSeekEnd = {
                        draggingSeekBar = false
                        scrubber.finish()
                    },
                    hasPrevious = page > 0,
                    hasNext = page < items.lastIndex,
                    onPrevious = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                    onNext = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    onInteraction = { interactions++ },
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
                )
            }
            if (!controlsVisible) {
                HairlineProgress(playback, Modifier.align(Alignment.BottomCenter))
            }
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

@Composable
private fun ViewerTopBar(
    item: MediaItem,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onSaveFrame: (() -> Unit)?,
    onTrash: (() -> Unit)?,
    onRotate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.viewer_back),
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            // The same date the grid sorts and groups by; the date taken is in the details.
            val millis = (item.dateModifiedSeconds * 1000).takeIf { it > 0 } ?: item.dateTakenMillis
            if (millis != null) {
                Text(
                    text = remember(millis) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            item.displayName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSaveFrame != null) {
            IconButton(onClick = onSaveFrame) {
                Icon(
                    ViewerIcons.PhotoCamera,
                    contentDescription = stringResource(R.string.viewer_save_frame),
                    tint = Color.White,
                )
            }
        }
        IconButton(onClick = onInfo) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.viewer_info),
                tint = Color.White,
            )
        }
        if (onTrash != null) {
            IconButton(onClick = onTrash) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.viewer_delete),
                    tint = Color.White,
                )
            }
        }
        IconButton(onClick = onRotate) {
            Icon(
                ViewerIcons.ScreenRotation,
                contentDescription = stringResource(R.string.viewer_rotate),
                tint = Color.White,
            )
        }
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
