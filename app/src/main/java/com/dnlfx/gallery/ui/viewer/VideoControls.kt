package com.dnlfx.gallery.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.ButtonDefaults
import com.dnlfx.gallery.ui.theme.ViewerColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dnlfx.gallery.R
import com.dnlfx.gallery.ui.grid.formatDuration

/** Large play or pause button in the middle of the video, shown with the other controls. */
@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Round while paused, a softer square while playing, morphing between the two.
    val corner by animateDpAsState(
        targetValue = if (playing) 28.dp else 40.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playCorner",
    )
    FilledIconButton(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = ViewerColors.Glass,
            contentColor = ViewerColors.OnGlass,
        ),
        modifier = modifier.size(80.dp),
    ) {
        Icon(
            imageVector = if (playing) ViewerIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play),
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * The bottom strip while controls are shown: current time, a thin seek bar, the length, the loop
 * toggle and the speed button. It sits on a faint gradient so most of the frame stays visible. Swipes move between
 * items, so there are no arrows.
 */
@Composable
fun VideoBottomBar(
    playback: PlaybackState,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedPickerOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = speedPickerOpen) {
            SpeedPicker(
                selected = speed,
                onSelect = {
                    onSpeedChange(it)
                    speedPickerOpen = false
                },
            )
        }
        Row(
            // The speed button carries its own inner padding at the end.
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The position changes many times a second while playing, so only the parts that
            // show it read it, not the whole bar.
            PositionLabel(playback)
            ThinSeekBar(
                playback = playback,
                onSeek = onSeek,
                onSeekStart = onSeekStart,
                onSeekEnd = onSeekEnd,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TimeLabel(formatDuration(playback.durationMillis))
            IconToggleButton(
                checked = loop,
                onCheckedChange = {
                    onLoopChange(it)
                    onInteraction()
                },
            ) {
                Icon(
                    ViewerIcons.Repeat,
                    contentDescription = stringResource(R.string.viewer_loop),
                    tint = if (loop) Color.White else Color.White.copy(alpha = 0.5f),
                )
            }
            TextButton(
                onClick = {
                    speedPickerOpen = !speedPickerOpen
                    onInteraction()
                },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (speed != 1f || speedPickerOpen) ViewerColors.Glass else Color.Transparent,
                    contentColor = ViewerColors.OnGlass,
                ),
            ) {
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = if (speed != 1f) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** While controls are hidden: a thin line near the bottom, the only thing over the video. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HairlineProgress(playback: PlaybackState, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            // The bars are hidden while this shows, but keep clear of where they'd be (the
            // three-button bar at the bottom, or the side in landscape) and of the screen's
            // rounded corners, which would otherwise cut the line off.
            .windowInsetsPadding(
                WindowInsets.systemBarsIgnoringVisibility
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(2.dp),
    ) {
        // Read while drawing, so progress only redraws the line.
        val fraction = progressFraction(playback.positionMillis, playback.durationMillis)
        val round = CornerRadius(size.height / 2f)
        // Faint, so it blends into the video without disappearing on bright scenes.
        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = round)
        drawRoundRect(Color.White.copy(alpha = 0.5f), size = size.copy(width = size.width * fraction), cornerRadius = round)
    }
}

@Composable
private fun SpeedPicker(selected: Float, onSelect: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlaybackSpeeds.forEach { speed ->
            val isSelected = speed == selected
            // The chosen speed is a squarer white pill; the rest are round glass ones.
            val corner by animateDpAsState(
                targetValue = if (isSelected) 10.dp else 18.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "speedCorner",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(corner))
                    .background(if (isSelected) Color.White else ViewerColors.Glass)
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.Black else Color.White,
                )
            }
        }
    }
}

@Composable
private fun PositionLabel(playback: PlaybackState) {
    TimeLabel(formatDuration(playback.positionMillis))
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        // Even-width digits, so the bar doesn't twitch as the seconds tick.
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = ViewerColors.OnGlass,
    )
}

/** A 3dp track with a small thumb; tap or drag anywhere along it to seek. */
@Composable
private fun ThinSeekBar(
    playback: PlaybackState,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positionMillis = playback.positionMillis
    val durationMillis = playback.durationMillis
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(durationMillis)
    val seek by rememberUpdatedState(onSeek)
    val start by rememberUpdatedState(onSeekStart)
    val end by rememberUpdatedState(onSeekEnd)
    val fraction = dragFraction ?: progressFraction(positionMillis, durationMillis)
    val label = stringResource(R.string.viewer_seek_bar)
    val position = stringResource(
        R.string.viewer_seek_position,
        formatDuration(positionMillis),
        formatDuration(durationMillis),
    )

    Canvas(
        modifier
            // A full 48 dp touch target, though the track itself is thin.
            .height(48.dp)
            // Screen readers announce the time and can move it, by default 5% of the video per step.
            .semantics {
                contentDescription = label
                stateDescription = position
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    val known = duration > 0L
                    if (known) seek((target.coerceIn(0f, 1f) * duration).toLong())
                    known
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val f = (tap.x / size.width).coerceIn(0f, 1f)
                    seek((f * duration).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        start()
                        dragFraction = (at.x / size.width).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragFraction = f
                        seek((f * duration).toLong())
                    },
                    onDragEnd = {
                        dragFraction = null
                        end()
                    },
                    onDragCancel = {
                        dragFraction = null
                        end()
                    },
                )
            },
    ) {
        // Material 3 Expressive slider: a thick active track, an upright handle with a gap on
        // either side, and a thinner, fainter track for what's left.
        val y = size.height / 2f
        val dragging = dragFraction != null
        val handleWidth = (if (dragging) 3.dp else 4.dp).toPx()
        val handleHeight = (if (dragging) 28.dp else 22.dp).toPx()
        val gap = 4.dp.toPx()
        val active = 6.dp.toPx()
        val inactive = 4.dp.toPx()
        val x = (size.width * fraction).coerceIn(handleWidth / 2f, size.width - handleWidth / 2f)
        val activeEnd = x - handleWidth / 2f - gap
        val inactiveStart = x + handleWidth / 2f + gap
        if (activeEnd > 0f) {
            drawRoundRect(
                Color.White,
                topLeft = Offset(0f, y - active / 2f),
                size = Size(activeEnd, active),
                cornerRadius = CornerRadius(active / 2f),
            )
        }
        if (inactiveStart < size.width) {
            drawRoundRect(
                ViewerColors.Track,
                topLeft = Offset(inactiveStart, y - inactive / 2f),
                size = Size(size.width - inactiveStart, inactive),
                cornerRadius = CornerRadius(inactive / 2f),
            )
        }
        drawRoundRect(
            Color.White,
            topLeft = Offset(x - handleWidth / 2f, y - handleHeight / 2f),
            size = Size(handleWidth, handleHeight),
            cornerRadius = CornerRadius(handleWidth / 2f),
        )
    }
}

private fun progressFraction(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f
