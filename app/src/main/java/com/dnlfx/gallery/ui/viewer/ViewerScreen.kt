package com.dnlfx.gallery.ui.viewer

import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
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
    // The item open in the built-in editor, and a copy it saved to show once it's in the library.
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var revealId by remember { mutableStateOf<Long?>(null) }
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
    // A brightness slide on a video lasts only while the viewer is open.
    DisposableEffect(activity) {
        onDispose { activity?.window?.resetBrightness() }
    }
    val originalOrientation = remember { activity?.requestedOrientation }
    DisposableEffect(activity) {
        onDispose { originalOrientation?.let { activity?.requestedOrientation = it } }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = 1f - dismissProgress)) },
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
            visible = controlsVisible && settledItem != null && !dismissing,
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
                    // Only library items can be starred or trashed, not files other apps handed over.
                    onFavorite = if (canChangeMedia && item.id != EXTERNAL_ITEM_ID) {
                        { requests.favorite(listOf(item.uri), !item.isFavorite) }
                    } else {
                        null
                    },
                    onTrash = if (canChangeMedia && item.id != EXTERNAL_ITEM_ID) {
                        { requests.trash(listOf(item.uri)) }
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

        if (video != null && player != null && scrubber != null) {
            AnimatedVisibility(
                visible = controlsVisible && !dismissing,
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
                visible = controlsVisible && !dismissing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
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
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
                )
            }
            if (!controlsVisible && !dismissing) {
                HairlineProgress(playback, Modifier.align(Alignment.BottomCenter))
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

@Composable
private fun ViewerTopBar(
    item: MediaItem,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onSaveFrame: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onFavorite: (() -> Unit)?,
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
        if (onFavorite != null) {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (item.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(
                        if (item.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                    ),
                    tint = Color.White,
                )
            }
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
        ViewerMoreMenu(onEdit = onEdit, onInfo = onInfo, onRotate = onRotate)
    }
}

/**
 * The overflow button: the editor, the details sheet and turning the screen, used less often than
 * the buttons beside it, which leaves the date and name room on a phone held upright.
 */
@Composable
private fun ViewerMoreMenu(onEdit: (() -> Unit)?, onInfo: () -> Unit, onRotate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.viewer_more),
                tint = Color.White,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        open = false
                        onEdit()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_info)) },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = {
                    open = false
                    onInfo()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_rotate)) },
                leadingIcon = { Icon(ViewerIcons.ScreenRotation, contentDescription = null) },
                onClick = {
                    open = false
                    onRotate()
                },
            )
        }
    }
}

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
