package com.dnlfx.gallery.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.ui.theme.ViewerColors
import java.text.DateFormat
import java.util.Date

/**
 * The viewer's top row over a soft scrim: back, the date and name, and the few screen-level
 * actions (save a paused frame, turn the screen). What can be done to the item itself sits in
 * the floating toolbar at the bottom, within reach of a thumb.
 */
@Composable
internal fun ViewerTopBar(
    item: MediaItem,
    onBack: () -> Unit,
    onSaveFrame: (() -> Unit)?,
    onRotate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            description = stringResource(R.string.viewer_back),
            onClick = onBack,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // The same date the grid sorts and groups by; the date taken is in the details.
            val millis = (item.dateModifiedSeconds * 1000).takeIf { it > 0 } ?: item.dateTakenMillis
            if (millis != null) {
                Text(
                    text = remember(millis) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = ViewerColors.OnGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.displayName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = ViewerColors.OnGlassMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSaveFrame != null) {
            GlassIconButton(
                icon = ViewerIcons.PhotoCamera,
                description = stringResource(R.string.viewer_save_frame),
                onClick = onSaveFrame,
            )
        }
        if (onRotate != null) {
            GlassIconButton(
                icon = ViewerIcons.ScreenRotation,
                description = stringResource(R.string.viewer_rotate),
                onClick = onRotate,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Favorite, edit, details and trash in one floating pill. Each is left out when it doesn't apply
 * (files other apps handed over can't be starred or trashed, GIFs aren't edited).
 */
@Composable
internal fun ViewerActionsToolbar(
    isFavorite: Boolean,
    onFavorite: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onInfo: () -> Unit,
    onTrash: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.viewer_actions)
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = ViewerColors.GlassStrong,
            toolbarContentColor = ViewerColors.OnGlass,
        ),
        modifier = modifier.semantics { contentDescription = label },
    ) {
        if (onFavorite != null) {
            IconButton(onClick = onFavorite, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (isFavorite) R.string.favorite_remove else R.string.favorite_add),
                    tint = if (isFavorite) FavoriteRed else ViewerColors.OnGlass,
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.viewer_edit))
            }
        }
        IconButton(onClick = onInfo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.viewer_info))
        }
        if (onTrash != null) {
            IconButton(onClick = onTrash, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.viewer_delete))
            }
        }
    }
}

/** A round button of dark glass, readable over the brightest photo. */
@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = ViewerColors.Glass,
            contentColor = ViewerColors.OnGlass,
        ),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = description)
    }
}

private val FavoriteRed = Color(0xFFFF5A6A)
