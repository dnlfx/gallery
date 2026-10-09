package com.dnlfx.gallery.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
        ),
        modifier = modifier.size(64.dp),
    ) {
        Icon(
            imageVector = if (playing) ViewerIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.viewer_pause else R.string.viewer_play),
            modifier = Modifier.size(36.dp),
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
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))),
    ) {
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
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeLabel(formatDuration(playback.positionMillis))
            ThinSeekBar(
                positionMillis = playback.positionMillis,
                durationMillis = playback.durationMillis,
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
            ) {
                Text(
                    text = formatSpeed(speed),
                    color = Color.White,
                    fontWeight = if (speed != 1f) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** While controls are hidden: a 2dp line along the bottom edge, the only thing over the video. */
@Composable
fun HairlineProgress(playback: PlaybackState, modifier: Modifier = Modifier) {
    val fraction = progressFraction(playback.positionMillis, playback.durationMillis)
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(Color.White.copy(alpha = 0.2f))
        drawRect(Color.White.copy(alpha = 0.7f), size = size.copy(width = size.width * fraction))
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
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.15f))
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
private fun TimeLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = Color.White)
}

/** A 3dp track with a small thumb; tap or drag anywhere along it to seek. */
@Composable
private fun ThinSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .height(32.dp)
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
        val y = size.height / 2f
        val track = 3.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y), Offset(size.width * fraction, y), track, StrokeCap.Round)
        val thumb = if (dragFraction != null) 7.dp.toPx() else 5.dp.toPx()
        drawCircle(Color.White, radius = thumb, center = Offset(size.width * fraction, y))
    }
}

private fun progressFraction(positionMillis: Long, durationMillis: Long): Float =
    if (durationMillis > 0) (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f
