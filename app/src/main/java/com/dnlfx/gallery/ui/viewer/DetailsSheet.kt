package com.dnlfx.gallery.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dnlfx.gallery.R
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.data.MediaType
import com.dnlfx.gallery.ui.grid.formatDuration
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything known about the item on screen: its name, then tiles for resolution, size, length
 * and type, then a card with the dates and folder.
 */
@Composable
fun DetailsSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.details_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.displayName ?: stringResource(R.string.details_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val tiles = buildList {
                if (item.width > 0 && item.height > 0) {
                    // MediaStore reports the stored size; a photo shot sideways is shown turned upright.
                    val sideways = item.orientationDegrees == 90 || item.orientationDegrees == 270
                    val width = if (sideways) item.height else item.width
                    val height = if (sideways) item.width else item.height
                    val megapixels = String.format(Locale.getDefault(), "%.1f", width.toLong() * height / 1_000_000.0)
                    add(
                        R.string.details_resolution to
                            context.getString(R.string.details_resolution_value, width, height, megapixels),
                    )
                }
                if (item.sizeBytes > 0) add(R.string.details_size to Formatter.formatFileSize(context, item.sizeBytes))
                if (item.type == MediaType.VIDEO) {
                    item.durationMillis?.takeIf { it > 0 }?.let { add(R.string.details_length to formatDuration(it)) }
                }
                item.mimeType?.let { add(R.string.details_type to it) }
            }
            if (tiles.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tiles.forEach { (label, value) -> DetailTile(stringResource(label), value, Modifier.weight(1f)) }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(vertical = 8.dp),
            ) {
                if (item.dateModifiedSeconds > 0) {
                    DetailRow(
                        stringResource(R.string.details_modified),
                        dateFormat.format(Date(item.dateModifiedSeconds * 1000)),
                    )
                }
                item.dateTakenMillis?.let { DetailRow(stringResource(R.string.details_taken), dateFormat.format(Date(it))) }
                item.relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let {
                    DetailRow(stringResource(R.string.details_folder), it)
                }
            }
        }
    }
}

/** A rounded tile with a small label over a prominent value. */
@Composable
private fun DetailTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
