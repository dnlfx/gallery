package com.dnlfx.gallery.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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

/** Everything known about the item on screen: dates, name, folder, type, resolution, length and size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.details_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (item.dateModifiedSeconds > 0) {
                DetailRow(
                    stringResource(R.string.details_modified),
                    dateFormat.format(Date(item.dateModifiedSeconds * 1000)),
                )
            }
            item.dateTakenMillis?.let { DetailRow(stringResource(R.string.details_taken), dateFormat.format(Date(it))) }
            item.displayName?.let { DetailRow(stringResource(R.string.details_name), it) }
            item.relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let {
                DetailRow(stringResource(R.string.details_folder), it)
            }
            item.mimeType?.let { DetailRow(stringResource(R.string.details_type), it) }
            if (item.width > 0 && item.height > 0) {
                // MediaStore reports the stored size; a photo shot sideways is shown turned upright.
                val sideways = item.orientationDegrees == 90 || item.orientationDegrees == 270
                val width = if (sideways) item.height else item.width
                val height = if (sideways) item.width else item.height
                val megapixels = String.format(Locale.getDefault(), "%.1f", width.toLong() * height / 1_000_000.0)
                DetailRow(
                    stringResource(R.string.details_resolution),
                    stringResource(R.string.details_resolution_value, width, height, megapixels),
                )
            }
            if (item.type == MediaType.VIDEO) {
                item.durationMillis?.takeIf { it > 0 }?.let {
                    DetailRow(stringResource(R.string.details_length), formatDuration(it))
                }
            }
            if (item.sizeBytes > 0) {
                DetailRow(stringResource(R.string.details_size), Formatter.formatFileSize(context, item.sizeBytes))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
