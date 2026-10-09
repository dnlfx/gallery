package com.dnlfx.gallery.ui.editor

import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as PlayerMediaItem

/**
 * The built-in editor, over the viewer. Photos and videos can be cropped (free or to a shape),
 * turned, mirrored, straightened and adjusted for light and color; videos can also be trimmed
 * and muted. Undo steps back one change, and holding the compare button shows the original.
 *
 * An edited photo replaces the original (after the system asks), or becomes a new copy in
 * formats that can't be written back; an edited video is always a new copy. [onSaved] gets the
 * uri of whatever was saved.
 */
@Composable
fun EditorScreen(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    when (item.type) {
        MediaType.IMAGE -> PhotoEditor(item, onClose, onSaved)
        MediaType.VIDEO -> VideoEditor(item, onClose, onSaved)
    }
}

/** Everything the editor would change, as one value so it can be undone in steps. */
data class EditState(
    val crop: CropRect = CropRect.Full,
    val transform: Transform = Transform(),
    val adjustments: Adjustments = Adjustments(),
    /** The crop's shape; [portrait] stands it on end. */
    val aspect: AspectChoice = AspectChoice.Free,
    val portrait: Boolean = false,
    /** Videos only: the part to keep, once the length is known. */
    val trim: TrimRange? = null,
    val mute: Boolean = false,
) {
    /** True when the picture itself would change. */
    val changesPicture: Boolean
        get() = !crop.isFull || !transform.isIdentity || !adjustments.isNeutral
}

/** The edit in progress and the steps back to where it started. */
@Stable
private class EditHistory {
    /** Where editing started, which Reset goes back to. */
    var initial by mutableStateOf(EditState())
        private set
    var state by mutableStateOf(EditState())
        private set
    private val undoSteps = mutableStateListOf<EditState>()
    private var gestureStart: EditState? = null

    val canUndo: Boolean get() = undoSteps.isNotEmpty()

    /** A change in one go, like a button tap. */
    fun apply(next: EditState) {
        if (next == state) return
        undoSteps += state
        state = next
    }

    /** A change that follows a finger or slider; [endGesture] makes it one undo step. */
    fun preview(next: EditState) {
        if (gestureStart == null) gestureStart = state
        state = next
    }

    fun endGesture() {
        gestureStart?.let { if (it != state) undoSteps += it }
        gestureStart = null
    }

    fun undo() {
        undoSteps.removeLastOrNull()?.let { state = it }
    }

    /** Fills in something that was only known later (a video's length) everywhere, with no undo step. */
    fun settle(update: (EditState) -> EditState) {
        initial = update(initial)
        state = update(state)
        for (i in undoSteps.indices) undoSteps[i] = update(undoSteps[i])
    }
}

/** Whether a copy is being saved, how far along, and how to stop it. */
@Stable
private class SaveState {
    var job by mutableStateOf<Job?>(null)
        private set

    /** 0 to 1 when the save reports progress, null when it doesn't (or hasn't yet). */
    var progress by mutableStateOf<Float?>(null)

    /** False while a save is running that has to finish once started, like writing over a photo. */
    var canStop by mutableStateOf(true)
        private set

    /**
     * Runs [save]. One that isn't [stoppable] runs to the end even if the editor closes, so it
     * never stops halfway with the file changed but the editor still showing the old edit, which
     * a second save would apply again on top. A save that fails unexpectedly says so instead of
     * closing the app.
     */
    fun start(scope: CoroutineScope, context: Context, stoppable: Boolean, save: suspend () -> Unit) {
        if (job != null) return
        progress = null
        canStop = stoppable
        job = scope.launch {
            try {
                withContext(if (stoppable) EmptyCoroutineContext else NonCancellable) { save() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EditorScreen", "Save failed", e)
                Toast.makeText(context, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                job = null
            }
        }
    }

    fun cancel() {
        if (canStop) job?.cancel()
    }
}

private enum class Tool(@StringRes val label: Int) {
    Crop(R.string.editor_tool_crop),
    Adjust(R.string.editor_tool_adjust),
    Trim(R.string.editor_tool_trim),
}

@Composable
private fun PhotoEditor(item: MediaItem, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { EditHistory() }
    var aspectRatio by remember { mutableFloatStateOf(0f) }
    val saving = remember { SaveState() }
    val requests = rememberMediaRequests()
    val overwrite = remember(item) { canOverwrite(item) }

    EditorContent(
        history = history,
        tools = listOf(Tool.Crop, Tool.Adjust),
        saveLabel = stringResource(if (overwrite) R.string.editor_save else R.string.editor_save_copy),
        canSave = history.state.changesPicture,
        saving = saving,
        onClose = onClose,
        onSave = {
            val state = history.state
            val edit = PhotoEdit(state.crop, state.transform, state.adjustments)
            if (overwrite) {
                // The system asks before the original is changed; nothing happens if it's declined.
                requests.write(listOf(item.uri)) {
                    saving.start(scope, context, stoppable = false) {
                        val saved = overwriteWithEdit(context, item, edit)
                        finishSave(context, if (saved) item.uri else null, R.string.editor_saved, onSaved)
                    }
                }
            } else {
                saving.start(scope, context, stoppable = false) {
                    val uri = saveEditedPhoto(context, item, edit)
                    finishSave(context, uri, R.string.editor_saved_copy, onSaved)
                }
            }
        },
        aspectRatio = aspectRatio,
        detect = { detectPhotoContent(context, item) },
        picture = { colors ->
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                colorFilter = colors?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
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
        ExoPlayer.Builder(context, renderers)
            // Like the viewer: other apps' audio pauses while the preview plays, and unplugging
            // headphones pauses it.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
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

    val history = remember { EditHistory() }
    val saving = remember { SaveState() }
    LaunchedEffect(duration) {
        if (duration > 0 && history.state.trim == null) history.settle { it.copy(trim = TrimRange(0L, duration)) }
    }
    LaunchedEffect(history.state.mute) { player.volume = if (history.state.mute) 0f else 1f }
    // Keep playback inside the kept part.
    LaunchedEffect(player) {
        while (true) {
            val range = history.state.trim
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

    val state = history.state
    val range = state.trim
    EditorContent(
        history = history,
        tools = listOf(Tool.Trim, Tool.Crop, Tool.Adjust),
        saveLabel = stringResource(R.string.editor_save_copy),
        canSave = range != null && (state.changesPicture || !range.isWhole(duration) || state.mute),
        saving = saving,
        onClose = onClose,
        onSave = {
            if (range != null) {
                player.pause()
                saving.start(scope, context, stoppable = true) {
                    val edit = VideoEdit(
                        trim = range.takeUnless { it.isWhole(duration) },
                        crop = state.crop,
                        transform = state.transform,
                        adjustments = state.adjustments,
                        mute = state.mute,
                    )
                    val uri = saveEditedVideo(context, item, edit) { saving.progress = it }
                    finishSave(context, uri, R.string.editor_saved_copy, onSaved)
                }
            }
        },
        aspectRatio = playback.aspectRatio,
        detect = { detectVideoContent(context, item, history.state.trim ?: TrimRange(0L, duration)) },
        picture = { colors ->
            // A TextureView draws in the window like any other view, so it turns with the frame
            // and the crop box sits on top of it, clear of the viewer's own video surface.
            AndroidView(
                factory = { TextureView(it).also(player::setVideoTextureView) },
                update = { view ->
                    view.setLayerPaint(colors?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } })
                },
                onRelease = { player.clearVideoTextureView(it) },
                modifier = Modifier.fillMaxSize(),
            )
            if (!playback.firstFrameRendered) VideoPoster(item)
        },
        trimTool = {
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
                    )
                    IconButton(onClick = { history.apply(history.state.copy(mute = !history.state.mute)) }) {
                        Icon(
                            imageVector = if (state.mute) ViewerIcons.VolumeOff else ViewerIcons.VolumeUp,
                            contentDescription = stringResource(if (state.mute) R.string.editor_unmute else R.string.editor_mute),
                            tint = Color.White,
                        )
                    }
                }
                TrimBar(
                    durationMillis = duration,
                    range = range,
                    positionMillis = playback.positionMillis,
                    frames = frames,
                    onRangeChange = { moved, handle ->
                        history.preview(history.state.copy(trim = moved))
                        player.seekTo(if (handle == TrimHandle.Start) moved.startMillis else moved.endMillis)
                    },
                    onDragStart = {
                        player.pause()
                        // Seeks replace each other instead of queueing, so the picture keeps up
                        // with the handle.
                        player.setScrubbingModeEnabled(true)
                    },
                    onDragEnd = {
                        player.setScrubbingModeEnabled(false)
                        history.endGesture()
                    },
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

private fun finishSave(context: Context, uri: Uri?, @StringRes savedMessage: Int, onSaved: (Uri) -> Unit) {
    if (uri == null) {
        Toast.makeText(context, R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
        onSaved(uri)
    }
}

/**
 * The editor's layout: the top bar, the picture as it will be saved with the crop box over it,
 * the controls of the tool in use, and the tool tabs.
 *
 * [aspectRatio] is the original upright picture's width over height (0 until it's known).
 * [picture] draws the original, untouched, filling its box; it gets the color matrix to show
 * the adjustments with, or null for none. [detect] finds the picture inside any bars, measured
 * on the original.
 */
@Composable
private fun EditorContent(
    history: EditHistory,
    tools: List<Tool>,
    saveLabel: String,
    canSave: Boolean,
    saving: SaveState,
    onClose: () -> Unit,
    onSave: () -> Unit,
    aspectRatio: Float,
    detect: suspend () -> CropRect?,
    picture: @Composable (colors: FloatArray?) -> Unit,
    trimTool: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(tools.first()) }
    var comparing by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    val state = history.state
    // While comparing, the original as it was, uncropped.
    val shown = if (comparing) EditState() else state
    val frameAspect = if (aspectRatio <= 0f) 0f else if (shown.transform.sideways) 1f / aspectRatio else aspectRatio
    val lockedFraction = if (frameAspect > 0f) {
        lockedRatio(state.aspect, state.portrait, frameAspect)?.let { fractionRatio(it, frameAspect) }
    } else {
        null
    }

    BackHandler {
        // Back stops a save that can be stopped, and otherwise waits for it.
        if (saving.job != null) saving.cancel() else onClose()
    }
    val buttonColors = ButtonDefaults.textButtonColors(
        contentColor = Color.White,
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )
    val iconColors = IconButtonDefaults.iconButtonColors(
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
            IconButton(onClick = onClose, colors = iconColors) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_cancel))
            }
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = history::undo, enabled = history.canUndo, colors = iconColors) {
                Icon(ViewerIcons.Undo, contentDescription = stringResource(R.string.editor_undo))
            }
            CompareButton(onComparingChange = { comparing = it })
            TextButton(onClick = onSave, enabled = canSave && saving.job == null, colors = buttonColors) {
                Text(saveLabel)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.fillMaxSize().padding(PICTURE_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                if (frameAspect > 0f) {
                    TransformedPicture(
                        transform = shown.transform,
                        frameAspect = frameAspect,
                        colors = shown.adjustments.takeUnless { it.isNeutral }?.colorMatrix(),
                        picture = picture,
                        modifier = Modifier.aspectRatio(frameAspect),
                    )
                } else {
                    // Lays the picture out so its size can be found.
                    Box(Modifier.fillMaxSize()) { picture(null) }
                }
            }
            if (frameAspect > 0f && !comparing) {
                CropOverlay(
                    aspectRatio = frameAspect,
                    margin = PICTURE_MARGIN,
                    crop = state.crop,
                    ratio = lockedFraction,
                    interactive = tool == Tool.Crop,
                    onCropChange = { history.preview(history.state.copy(crop = it)) },
                    onDragStart = {},
                    onDragEnd = history::endGesture,
                    label = stringResource(R.string.editor_crop_area),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when (tool) {
            Tool.Crop -> CropTools(
                history = history,
                frameAspect = frameAspect,
                detecting = detecting,
                onAutoFit = {
                    if (!detecting && aspectRatio > 0f) {
                        scope.launch {
                            detecting = true
                            val found = try {
                                detect()
                            } finally {
                                detecting = false
                            }
                            when {
                                found == null -> Toast.makeText(context, R.string.editor_auto_fit_failed, Toast.LENGTH_SHORT).show()
                                found.isFull -> Toast.makeText(context, R.string.editor_auto_fit_nothing, Toast.LENGTH_SHORT).show()
                                else -> history.apply(
                                    history.state.copy(
                                        crop = history.state.transform.mapCrop(found, aspectRatio),
                                        aspect = AspectChoice.Free,
                                    ),
                                )
                            }
                        }
                    }
                },
            )
            Tool.Adjust -> AdjustTools(history)
            Tool.Trim -> trimTool()
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (each in tools) {
                val selected = each == tool
                TextButton(
                    onClick = { tool = each },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f),
                    ),
                ) {
                    Text(stringResource(each.label), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { history.apply(history.initial) },
                enabled = state != history.initial,
                colors = buttonColors,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
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
                if (saving.canStop) {
                    TextButton(onClick = saving::cancel) { Text(stringResource(R.string.editor_stop)) }
                }
            },
        )
    }
}

/**
 * The picture turned, mirrored and tilted to fill a frame of [frameAspect], the way it will be
 * saved. Only what's inside the frame shows.
 */
@Composable
private fun TransformedPicture(
    transform: Transform,
    frameAspect: Float,
    colors: FloatArray?,
    picture: @Composable (colors: FloatArray?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        // Laid out at its own shape, so a quarter turn fills the frame exactly.
        val width = if (transform.sideways) maxHeight else maxWidth
        val height = if (transform.sideways) maxWidth else maxHeight
        val zoom = transform.zoom(frameAspect, 1f)
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer {
                    rotationZ = transform.rotationDegrees
                    scaleX = zoom * (if (transform.flipHorizontal) -1f else 1f)
                    scaleY = zoom * (if (transform.flipVertical) -1f else 1f)
                },
        ) {
            picture(colors)
        }
    }
}

/** Shows the original while held. */
@Composable
private fun CompareButton(onComparingChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.editor_compare)
    Box(
        Modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onComparingChange(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onComparingChange(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(ViewerIcons.Compare, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun CropTools(history: EditHistory, frameAspect: Float, detecting: Boolean, onAutoFit: () -> Unit) {
    val state = history.state
    val ready = frameAspect > 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(R.string.editor_rotate, enabled = ready, onClick = {
                history.apply(
                    state.copy(
                        transform = state.transform.rotatedClockwise(),
                        crop = state.crop.rotatedClockwise(),
                        portrait = !state.portrait,
                    ),
                )
            }) { Icon(ViewerIcons.RotateRight, contentDescription = null) }
            ToolButton(R.string.editor_flip_horizontal, enabled = ready, onClick = {
                history.apply(
                    state.copy(
                        transform = state.transform.mirroredHorizontally(),
                        crop = state.crop.mirroredHorizontally(),
                    ),
                )
            }) { Icon(ViewerIcons.Flip, contentDescription = null) }
            ToolButton(R.string.editor_flip_vertical, enabled = ready, onClick = {
                history.apply(
                    state.copy(
                        transform = state.transform.mirroredVertically(),
                        crop = state.crop.mirroredVertically(),
                    ),
                )
            }) { Icon(ViewerIcons.Flip, contentDescription = null, modifier = Modifier.rotate(90f)) }
            ToolButton(R.string.editor_auto_fit, enabled = ready && !detecting, onClick = onAutoFit) {
                if (detecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(ViewerIcons.AutoFix, contentDescription = null)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (choice in AspectChoice.entries) {
                FilterChip(
                    selected = state.aspect == choice,
                    enabled = ready,
                    onClick = {
                        val portrait = when (choice) {
                            AspectChoice.Original -> frameAspect < 1f
                            AspectChoice.Free, AspectChoice.Square -> state.portrait
                            // Keeps the way round already chosen, or follows the picture.
                            else -> if (state.aspect.width > 0f) state.portrait else frameAspect < 1f
                        }
                        val crop = lockedRatio(choice, portrait, frameAspect)
                            ?.let { fitAspect(state.crop, fractionRatio(it, frameAspect)) }
                            ?: state.crop
                        history.apply(state.copy(aspect = choice, portrait = portrait, crop = crop))
                    },
                    label = { Text(stringResource(choice.label)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Color.White,
                        disabledLabelColor = Color.White.copy(alpha = 0.38f),
                    ),
                )
            }
            val canTurn = state.aspect != AspectChoice.Free && state.aspect != AspectChoice.Square
            IconButton(
                onClick = {
                    val portrait = !state.portrait
                    val crop = lockedRatio(state.aspect, portrait, frameAspect)
                        ?.let { fitAspect(state.crop, fractionRatio(it, frameAspect)) }
                        ?: state.crop
                    history.apply(state.copy(portrait = portrait, crop = crop))
                },
                enabled = ready && canTurn,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    ViewerIcons.Portrait,
                    contentDescription = stringResource(R.string.editor_aspect_turn),
                    modifier = Modifier.rotate(if (state.portrait) 0f else 90f),
                )
            }
        }

        LabeledSlider(
            label = stringResource(R.string.editor_straighten),
            valueText = stringResource(R.string.editor_degrees, String.format(Locale.getDefault(), "%.1f", state.transform.straighten)),
            value = state.transform.straighten,
            range = -MAX_STRAIGHTEN..MAX_STRAIGHTEN,
            enabled = ready,
            onValueChange = { history.preview(history.state.copy(transform = history.state.transform.copy(straighten = it))) },
            onValueChangeFinished = history::endGesture,
        )
    }
}

@Composable
private fun AdjustTools(history: EditHistory) {
    val adjustments = history.state.adjustments
    fun change(update: (Adjustments) -> Adjustments) {
        history.preview(history.state.copy(adjustments = update(history.state.adjustments)))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        AdjustSlider(R.string.editor_brightness, adjustments.brightness, history) { v -> change { it.copy(brightness = v) } }
        AdjustSlider(R.string.editor_contrast, adjustments.contrast, history) { v -> change { it.copy(contrast = v) } }
        AdjustSlider(R.string.editor_saturation, adjustments.saturation, history) { v -> change { it.copy(saturation = v) } }
        AdjustSlider(R.string.editor_warmth, adjustments.warmth, history) { v -> change { it.copy(warmth = v) } }
    }
}

@Composable
private fun AdjustSlider(@StringRes label: Int, value: Float, history: EditHistory, onValueChange: (Float) -> Unit) {
    val percent = (value * 100f).roundToInt()
    LabeledSlider(
        label = stringResource(label),
        valueText = if (percent > 0) "+$percent" else "$percent",
        value = value,
        range = -1f..1f,
        enabled = true,
        onValueChange = onValueChange,
        onValueChangeFinished = history::endGesture,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.width(96.dp).padding(start = 8.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_NUMBERS),
            color = Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            modifier = Modifier.width(56.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun ToolButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@get:StringRes
private val AspectChoice.label: Int
    get() = when (this) {
        AspectChoice.Free -> R.string.editor_aspect_free
        AspectChoice.Original -> R.string.editor_aspect_original
        AspectChoice.Square -> R.string.editor_aspect_square
        AspectChoice.FourThree -> R.string.editor_aspect_4_3
        AspectChoice.SixteenNine -> R.string.editor_aspect_16_9
    }

/** Room around the picture for the crop handles, which reach a little past its edges. */
private val PICTURE_MARGIN = 24.dp

private const val TIMELINE_FRAMES = 8
private const val LOOP_POLL_MILLIS = 30L
private const val MAX_STRAIGHTEN = 45f

/** Playback a little outside the kept part (a seek landing just short of it) doesn't restart it. */
private const val LOOP_SLACK_MILLIS = 100L

private const val TABULAR_NUMBERS = "tnum"
