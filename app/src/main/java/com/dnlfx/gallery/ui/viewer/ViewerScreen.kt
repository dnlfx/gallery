package com.dnlfx.gallery.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.ui.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
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
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
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
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val playback = rememberPlaybackState(player)

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
    var interactions by remember { mutableIntStateOf(0) }
    var draggingSeekBar by remember { mutableStateOf(false) }
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
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { latestItems[it].id },
            beyondViewportPageCount = 1,
            // On a video, sideways slides scrub; the arrows in its controls move between items.
            userScrollEnabled = settledItem?.type != MediaType.VIDEO,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = latestItems[page]
            val onCurrentPage = page == pagerState.settledPage
            when (item.type) {
                MediaType.IMAGE -> ZoomableImage(
                    item = item,
                    isCurrentPage = onCurrentPage,
                    onTap = { controlsVisible = !controlsVisible },
                )
                MediaType.VIDEO -> if (onCurrentPage && item.id == video?.id) {
                    VideoPage(
                        item = item,
                        player = player,
                        playback = playback,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onInteraction = { interactions++ },
                    )
                } else {
                    VideoPoster(item)
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && settledItem != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            settledItem?.let { item ->
                ViewerTopBar(
                    item = item,
                    onBack = onClose,
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
                        when {
                            playback.ended -> {
                                player.seekTo(0L)
                                player.play()
                            }
                            player.playWhenReady -> player.pause()
                            else -> player.play()
                        }
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
                var resumeAfterSeek by remember { mutableStateOf(false) }
                val page = pagerState.settledPage
                VideoBottomBar(
                    playback = playback,
                    speed = speed,
                    onSpeedChange = {
                        speed = it
                        interactions++
                    },
                    onSeek = { player.seekTo(clampPosition(it, playback.durationMillis)) },
                    onSeekStart = {
                        draggingSeekBar = true
                        resumeAfterSeek = player.playWhenReady
                        player.pause()
                    },
                    onSeekEnd = {
                        draggingSeekBar = false
                        if (resumeAfterSeek) player.play()
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
}

@Composable
private fun ViewerTopBar(item: MediaItem, onBack: () -> Unit, onRotate: () -> Unit) {
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
            val millis = item.dateTakenMillis ?: (item.dateModifiedSeconds * 1000)
            Text(
                text = remember(millis) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
            )
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
