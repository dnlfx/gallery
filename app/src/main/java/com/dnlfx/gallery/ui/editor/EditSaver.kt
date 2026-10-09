package com.dnlfx.gallery.ui.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dnlfx.gallery.data.MediaItem
import com.dnlfx.gallery.ui.viewer.PixelRect
import com.dnlfx.gallery.ui.viewer.RegionSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as PlayerMediaItem

private const val TAG = "EditSaver"

/**
 * Saves [crop] of the photo [item] as a new photo next to it, at full resolution. PNGs (like
 * screenshots) stay lossless PNGs; everything else becomes a high-quality JPEG. The original is
 * never touched. Returns the new photo's uri, or null if it couldn't be saved.
 */
suspend fun saveCroppedPhoto(context: Context, item: MediaItem, crop: CropRect): Uri? = withContext(Dispatchers.IO) {
    val bitmap = try {
        decodeCrop(context, item, crop)
    } catch (e: OutOfMemoryError) {
        null
    } ?: return@withContext null
    try {
        val lossless = item.mimeType in LOSSLESS_TYPES
        insertMedia(
            context = context,
            isVideo = false,
            name = editedCopyName(item.displayName, if (lossless) "png" else "jpg"),
            mimeType = if (lossless) "image/png" else "image/jpeg",
            originalFolder = item.relativePath,
        ) { out ->
            if (lossless) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }
    } finally {
        bitmap.recycle()
    }
}

/** The cropped part of the photo, upright and at full resolution. */
private fun decodeCrop(context: Context, item: MediaItem, crop: CropRect): Bitmap? {
    RegionSource.open(context.contentResolver, item.uri, item.orientationDegrees)?.use { source ->
        // Only the cropped part is decoded, so even a 50 MP photo needs no more memory than that.
        val rect = crop.toPixels(source.width, source.height)
        return source.decode(PixelRect(rect.left, rect.top, rect.right, rect.bottom), sampleSize = 1)
    }
    // Formats the region decoder can't read: decode the whole picture upright, then cut it.
    val whole = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    } ?: return null
    val rect = crop.toPixels(whole.width, whole.height)
    return Bitmap.createBitmap(whole, rect.left, rect.top, rect.width, rect.height).also {
        if (it !== whole) whole.recycle()
    }
}

/** What to change in a video: the part to keep (null for all of it) and the crop. */
data class VideoEdit(val trim: TrimRange?, val crop: CropRect)

/**
 * Exports [edit] of the video [item] as a new MP4 next to it, entirely on the phone, reporting
 * progress from 0 to 1. A trim alone copies the video as it is apart from the first moments,
 * so it's quick and loses nothing; a crop re-encodes the picture on the phone's hardware encoder.
 * The original is never touched. Cancelling the coroutine stops the export. Returns the new
 * video's uri, or null if it couldn't be saved.
 */
suspend fun saveEditedVideo(context: Context, item: MediaItem, edit: VideoEdit, onProgress: (Float) -> Unit): Uri? {
    val folder = File(context.cacheDir, "edits").apply { mkdirs() }
    val output = File(folder, "export-${System.nanoTime()}.mp4")
    try {
        val source = withContext(Dispatchers.IO) { readVideoInfo(context, item.uri) }
        val exported = withContext(Dispatchers.Main) { export(context, item, edit, source, output, onProgress) }
        if (!exported) return null
        return withContext(Dispatchers.IO) {
            insertMedia(
                context = context,
                isVideo = true,
                name = editedCopyName(item.displayName, "mp4"),
                mimeType = MimeTypes.VIDEO_MP4,
                originalFolder = item.relativePath,
            ) { out ->
                output.inputStream().use { it.copyTo(out) }
                true
            }
        }
    } finally {
        output.delete()
    }
}

/** Upright size and overall bitrate of a video file, where known. */
private class VideoInfo(val width: Int, val height: Int, val bitrate: Int?)

private fun readVideoInfo(context: Context, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
        val width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
        val height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
        val sideways = (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0) % 180 != 0
        VideoInfo(
            width = if (sideways) height else width,
            height = if (sideways) width else height,
            bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
        )
    } catch (e: RuntimeException) {
        VideoInfo(0, 0, null)
    } finally {
        retriever.release()
    }
}

private suspend fun export(
    context: Context,
    item: MediaItem,
    edit: VideoEdit,
    source: VideoInfo,
    output: File,
    onProgress: (Float) -> Unit,
): Boolean = coroutineScope {
    val clipping = PlayerMediaItem.ClippingConfiguration.Builder().apply {
        edit.trim?.let {
            setStartPositionMs(it.startMillis)
            setEndPositionMs(it.endMillis)
        }
    }.build()
    val media = PlayerMediaItem.Builder().setUri(item.uri).setClippingConfiguration(clipping).build()
    val cropping = !edit.crop.isFull
    // Effects work on the upright picture, which is what the crop is measured on.
    val effects = if (cropping) {
        val ndc = edit.crop.snapToEvenPixels(source.width, source.height).toNdc()
        Effects(emptyList(), listOf(Crop(ndc.left, ndc.right, ndc.bottom, ndc.top)))
    } else {
        Effects.EMPTY
    }
    val edited = EditedMediaItem.Builder(media).setEffects(effects).build()

    val builder = Transformer.Builder(context)
    if (cropping) {
        // Keep roughly the original's quality per pixel rather than the encoder's default.
        val bitrate = source.bitrate?.let { max((it * edit.crop.width * edit.crop.height).roundToInt(), MIN_BITRATE) }
        if (bitrate != null) {
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build(),
            )
        }
    } else {
        // Only the stretch up to the first keyframe after the new start is re-encoded; the rest
        // is copied as it is.
        builder.experimentalSetTrimOptimizationEnabled(true)
    }
    val finished = CompletableDeferred<Boolean>()
    val transformer = builder
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                finished.complete(true)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                Log.w(TAG, "Export failed", exportException)
                finished.complete(false)
            }
        })
        .build()
    transformer.start(edited, output.absolutePath)
    val progress = launch {
        val holder = ProgressHolder()
        while (true) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress / 100f)
            }
            delay(PROGRESS_POLL_MILLIS)
        }
    }
    try {
        finished.await()
    } finally {
        progress.cancel()
        if (!finished.isCompleted) transformer.cancel()
    }
}

/**
 * Adds a new file to MediaStore, next to the original where that's allowed, hidden until [write]
 * has finished filling it. Returns its uri, or null if anything failed (and nothing is left behind).
 */
private fun insertMedia(
    context: Context,
    isVideo: Boolean,
    name: String,
    mimeType: String,
    originalFolder: String?,
    write: (OutputStream) -> Boolean,
): Uri? {
    val resolver = context.contentResolver
    val collection = if (isVideo) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    fun values(folder: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val preferred = editedCopyFolder(originalFolder, isVideo)
    val fallback = editedCopyFolder(null, isVideo)
    val uri = listOf(preferred, fallback).distinct().firstNotNullOfOrNull { folder ->
        try {
            resolver.insert(collection, values(folder))
        } catch (e: IllegalArgumentException) {
            // Some folders (another app's, say) can't take new files; try the next one.
            null
        }
    } ?: return null
    val written = try {
        resolver.openOutputStream(uri)?.use(write) == true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't write $name", e)
        false
    }
    if (!written) {
        resolver.delete(uri, null, null)
        return null
    }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    return uri
}

/**
 * Where the picture is in the photo [item], with bars and app chrome left out, or null if the
 * photo couldn't be read. [CropRect.Full] means there was nothing to trim.
 */
suspend fun detectPhotoContent(context: Context, item: MediaItem): CropRect? = withContext(Dispatchers.IO) {
    val bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > DETECT_SIZE) {
                val scale = DETECT_SIZE / longest.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    } catch (e: Exception) {
        null
    } ?: return@withContext null
    try {
        ContentBounds.combine(listOf(bounds(bitmap))) ?: CropRect.Full
    } finally {
        bitmap.recycle()
    }
}

/**
 * Where the picture is in the video [item] across [range], judged from several frames so a dark
 * scene doesn't decide it, or null if no frame could be read. [CropRect.Full] means there was
 * nothing to trim.
 */
suspend fun detectVideoContent(context: Context, item: MediaItem, range: TrimRange): CropRect? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, item.uri)
        val frames = (0 until DETECT_FRAMES).mapNotNull { i ->
            val millis = range.startMillis + range.lengthMillis * (2 * i + 1) / (2 * DETECT_FRAMES)
            val frame = retriever.getScaledFrameAtTime(
                millis * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                DETECT_SIZE,
                DETECT_SIZE,
            ) ?: return@mapNotNull null
            try {
                bounds(frame)
            } finally {
                frame.recycle()
            }
        }
        if (frames.isEmpty()) null else ContentBounds.combine(frames) ?: CropRect.Full
    } catch (e: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}

private fun bounds(bitmap: Bitmap): ContentBounds {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return ContentBounds.of(pixels, bitmap.width, bitmap.height)
}

/**
 * Frames spread evenly through a video, for the trim bar, each about [height] pixels tall.
 * [onFrame] gets each one on the main thread as soon as it's ready.
 */
suspend fun loadTimelineFrames(
    context: Context,
    uri: Uri,
    durationMillis: Long,
    count: Int,
    height: Int,
    onFrame: (index: Int, frame: Bitmap) -> Unit,
) = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        for (i in 0 until count) {
            val millis = durationMillis * (2 * i + 1) / (2 * count)
            val frame = retriever.getScaledFrameAtTime(
                millis * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                height * 2,
                height,
            ) ?: continue
            withContext(Dispatchers.Main) { onFrame(i, frame) }
        }
    } catch (e: RuntimeException) {
        // A video the platform can't read frames from just gets an empty bar.
    } finally {
        retriever.release()
    }
}

private val LOSSLESS_TYPES = setOf("image/png", "image/webp", "image/gif")
private const val JPEG_QUALITY = 95
private const val MIN_BITRATE = 2_000_000
private const val PROGRESS_POLL_MILLIS = 200L

/** Longest side of the frames auto fit looks at. */
private const val DETECT_SIZE = 480
private const val DETECT_FRAMES = 7
