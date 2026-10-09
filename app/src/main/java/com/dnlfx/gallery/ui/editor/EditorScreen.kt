package com.dnlfx.gallery.ui.editor

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.ui.rememberMediaRequests
import com.dnlfx.gallery.ui.viewer.VideoPoster
import com.dnlfx.gallery.ui.viewer.ViewerIcons
import com.dnlfx.gallery.ui.viewer.rememberPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as PlayerMediaItem

/**
 * The built-in editor, over the viewer: crop for photos, crop and trim for videos. A cropped
 * photo replaces the original (after the system asks), or becomes a new copy in formats that
 * can't be written back; an edited video is always a new copy. [onSaved] gets the uri of
 * whatever was saved.
 */
@Composable
fun EditorScreen(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    when (item.type) {
        MediaType.IMAGE -> PhotoEditor(item, onClose, onSaved)
        MediaType.VIDEO -> VideoEditor(item, onClose, onSaved)
    }
}

/** Whether a copy is being saved, how far along, and how to stop it. */
@Stable
private class SaveState {
    var job by mutableStateOf<Job?>(null)
        private set

    /** 0 to 1 when the save reports progress, null when it doesn't (or hasn't yet). */
    var progress by mutableStateOf<Float?>(null)

    fun start(scope: CoroutineScope, save: suspend () -> Unit) {
        if (job != null) return
        progress = null
        job = scope.launch {
            try {
                save()
            } finally {
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

@Composable
private fun PhotoEditor(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var crop by remember { mutableStateOf(CropRect.Full) }
    var aspectRatio by remember { mutableFloatStateOf(0f) }
    var detecting by remember { mutableStateOf(false) }
    val saving = remember { SaveState() }
    val requests = rememberMediaRequests()
    val overwrite = remember(item) { canOverwrite(item) }

    EditorScaffold(
        title = stringResource(R.string.editor_title),
        saveLabel = stringResource(if (overwrite) R.string.editor_save else R.string.editor_save_copy),
        canSave = !crop.isFull,
        saving = saving,
        onClose = onClose,
        onSave = {
            if (overwrite) {
                // The system asks before the original is changed; nothing happens if it's declined.
                requests.write(listOf(item.uri)) {
                    saving.start(scope) {
                        val saved = overwriteWithCrop(context, item, crop)
                        finishSave(context, if (saved) item.uri else null, R.string.editor_saved, onSaved)
                    }
                }
            } else {
                saving.start(scope) {
                    val uri = saveCroppedPhoto(context, item, crop)
                    finishSave(context, uri, R.string.editor_saved_copy, onSaved)
                }
            }
        },
        crop = crop,
        onCropChange = { crop = it },
        aspectRatio = aspectRatio,
        detecting = detecting,
        onAutoFit = {
            if (!detecting) {
                scope.launch {
                    detecting = true
                    val found = try {
                        detectPhotoContent(context, item)
                    } finally {
                        detecting = false
                    }
                    applyAutoFit(context, found) { crop = it }
                }
            }
        },
        canReset = !crop.isFull,
        onReset = { crop = CropRect.Full },
        picture = {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) aspectRatio = size.width / size.height
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun VideoEditor(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            // Plays the kept part over and over (see below); this covers a trim that runs to the end.
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(PlayerMediaItem.fromUri(item.uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val playback = rememberPlaybackState(player)
    val duration = playback.durationMillis

    var crop by remember { mutableStateOf(CropRect.Full) }
    var trim by remember { mutableStateOf<TrimRange?>(null) }
    var detecting by remember { mutableStateOf(false) }
    val saving = remember { SaveState() }
    LaunchedEffect(duration) {
        if (duration > 0 && trim == null) trim = TrimRange(0L, duration)
    }
    // Keep playback inside the kept part.
    LaunchedEffect(player) {
        while (true) {
            val range = trim
            if (range != null && player.isPlaying) {
                val position = player.currentPosition
                if (position >= range.endMillis || position < range.startMillis - LOOP_SLACK_MILLIS) {
                    player.seekTo(range.startMillis)
                }
            }
            delay(LOOP_POLL_MILLIS)
        }
    }

    val frames = remember { mutableStateListOf<ImageBitmap?>().apply { repeat(TIMELINE_FRAMES) { add(null) } } }
    val frameHeight = with(LocalDensity.current) { 56.dp.roundToPx() }
    LaunchedEffect(duration) {
        if (duration > 0) {
            loadTimelineFrames(context, item.uri, duration, TIMELINE_FRAMES, frameHeight) { index, frame ->
                frames[index] = frame.asImageBitmap()
            }
        }
    }

    val range = trim
    EditorScaffold(
        title = stringResource(R.string.editor_title),
        saveLabel = stringResource(R.string.editor_save_copy),
        canSave = range != null && (!crop.isFull || !range.isWhole(duration)),
        saving = saving,
        onClose = onClose,
        onSave = {
            if (range != null) {
                player.pause()
                saving.start(scope) {
                    val edit = VideoEdit(trim = range.takeUnless { it.isWhole(duration) }, crop = crop)
                    val uri = saveEditedVideo(context, item, edit) { saving.progress = it }
                    finishSave(context, uri, R.string.editor_saved_copy, onSaved)
                }
            }
        },
        crop = crop,
        onCropChange = { crop = it },
        aspectRatio = playback.aspectRatio,
        detecting = detecting,
        onAutoFit = {
            if (!detecting && duration > 0) {
                scope.launch {
                    detecting = true
                    val found = try {
                        detectVideoContent(context, item, trim ?: TrimRange(0L, duration))
                    } finally {
                        detecting = false
                    }
                    applyAutoFit(context, found) { crop = it }
                }
            }
        },
        canReset = !crop.isFull || (range != null && !range.isWhole(duration)),
        onReset = {
            crop = CropRect.Full
            if (duration > 0) trim = TrimRange(0L, duration)
        },
        picture = {
            // A TextureView draws in the window like any other view, so the crop box sits on top
            // of it without fighting the viewer's own video surface underneath.
            AndroidView(
                factory = { TextureView(it).also(player::setVideoTextureView) },
                onRelease = { player.clearVideoTextureView(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (!playback.firstFrameRendered) VideoPoster(item)
        },
        timeline = {
            if (range != null && duration > 0) {
                val playing = playback.playWhenReady && playback.isPlaying
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (player.playWhenReady) {
                                player.pause()
                            } else {
                                val position = player.currentPosition
                                if (position < range.startMillis || position >= range.endMillis - LOOP_SLACK_MILLIS) {
                                    player.seekTo(range.startMillis)
                                }
                                player.play()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (playing) ViewerIcons.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.editor_trim_range,
                            formatTrimTime(range.startMillis),
                            formatTrimTime(range.endMillis),
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMBERS),
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.editor_trim_length, formatTrimTime(range.lengthMillis)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMBERS),
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                TrimBar(
                    durationMillis = duration,
                    range = range,
                    positionMillis = playback.positionMillis,
                    frames = frames,
                    onRangeChange = { moved, handle ->
                        trim = moved
                        player.seekTo(if (handle == TrimHandle.Start) moved.startMillis else moved.endMillis)
                    },
                    onDragStart = {
                        player.pause()
                        // Seeks replace each other instead of queueing, so the picture keeps up
                        // with the handle.
                        player.setScrubbingModeEnabled(true)
                    },
                    onDragEnd = { player.setScrubbingModeEnabled(false) },
                    onSeek = { player.seekTo(it) },
                    label = stringResource(R.string.editor_trim_bar),
                    stateLabel = stringResource(
                        R.string.editor_trim_range,
                        formatTrimTime(range.startMillis),
                        formatTrimTime(range.endMillis),
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        },
    )
}

private fun applyAutoFit(context: Context, found: CropRect?, apply: (CropRect) -> Unit) {
    when {
        found == null -> Toast.makeText(context, R.string.editor_auto_fit_failed, Toast.LENGTH_SHORT).show()
        found.isFull -> Toast.makeText(context, R.string.editor_auto_fit_nothing, Toast.LENGTH_SHORT).show()
        else -> apply(found)
    }
}

private fun finishSave(context: Context, uri: Uri?, @StringRes savedMessage: Int, onSaved: (Uri) -> Unit) {
    if (uri == null) {
        Toast.makeText(context, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
        onSaved(uri)
    }
}

@Composable
private fun EditorScaffold(
    title: String,
    saveLabel: String,
    canSave: Boolean,
    saving: SaveState,
    onClose: () -> Unit,
    onSave: () -> Unit,
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
    aspectRatio: Float,
    detecting: Boolean,
    onAutoFit: () -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    picture: @Composable () -> Unit,
    timeline: @Composable () -> Unit = {},
) {
    BackHandler {
        if (saving.job != null) saving.cancel() else onClose()
    }
    val buttonColors = ButtonDefaults.textButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Takes every touch, so none reach the viewer underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_cancel), tint = Color.White)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            TextButton(onClick = onSave, enabled = canSave && saving.job == null, colors = buttonColors) {
                Text(saveLabel)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.fillMaxSize().padding(PICTURE_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                Box(if (aspectRatio > 0f) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize()) {
                    picture()
                }
            }
            if (aspectRatio > 0f) {
                CropOverlay(
                    aspectRatio = aspectRatio,
                    margin = PICTURE_MARGIN,
                    crop = crop,
                    onCropChange = onCropChange,
                    label = stringResource(R.string.editor_crop_area),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        timeline()

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAutoFit, enabled = !detecting && aspectRatio > 0f, colors = buttonColors) {
                if (detecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(ViewerIcons.AutoFix, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_auto_fit))
            }
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onReset, enabled = canReset, colors = buttonColors) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_reset))
            }
        }
    }

    if (saving.job != null) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.editor_saving)) },
            text = {
                val progress = saving.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = saving::cancel) { Text(stringResource(R.string.editor_stop)) }
            },
        )
    }
}

/** Room around the picture for the crop handles, which reach a little past its edges. */
private val PICTURE_MARGIN = 24.dp

private const val TIMELINE_FRAMES = 8
private const val LOOP_POLL_MILLIS = 30L

/** Playback a little outside the kept part (a seek landing just short of it) doesn't restart it. */
private const val LOOP_SLACK_MILLIS = 100L

private const val TABULAR_NUMBERS = "tnum"
