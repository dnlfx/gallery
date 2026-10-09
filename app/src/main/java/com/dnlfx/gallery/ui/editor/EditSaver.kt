package com.dnlfx.gallery.ui.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import coil3.SingletonImageLoader
import com.dnlfx.gallery.data.EXTERNAL_ITEM_ID
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as PlayerMediaItem

private const val TAG = "EditSaver"

/**
 * Whether an edit of the photo [item] can be saved over the original: a library photo (Android 11
 * and later, which asks the user first) in a format the phone can write back. Anything else, like
 * HEIC or RAW, or a file another app handed over, is saved as a new copy instead.
 */
fun canOverwrite(item: MediaItem): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && item.id != EXTERNAL_ITEM_ID && item.mimeType in WRITABLE_TYPES

/**
 * Replaces the photo [item] with its [edit], at full resolution and the highest quality its format
 * allows: lossless for PNG and WebP, JPEG at quality 100. The date taken, camera details and
 * location are carried over. Needs write access to [item] (see [canOverwrite]). The new photo is
 * written out completely before the original is replaced. Returns false if it couldn't be saved.
 */
suspend fun overwriteWithEdit(context: Context, item: MediaItem, edit: PhotoEdit): Boolean = withContext(Dispatchers.IO) {
    val format = when (item.mimeType) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            return@withContext false
        }
        else -> Bitmap.CompressFormat.JPEG
    }
    val bitmap = try {
        renderEdit(context, item, edit)
    } catch (e: OutOfMemoryError) {
        null
    } ?: return@withContext false
    val folder = File(context.cacheDir, "edits").apply { mkdirs() }
    val cropped = File(folder, "crop-${System.nanoTime()}")
    try {
        val encoded = try {
            cropped.outputStream().use { bitmap.compress(format, 100, it) }
        } finally {
            bitmap.recycle()
        }
        if (!encoded) return@withContext false
        copyMetadata(context, item.uri, cropped)
        context.contentResolver.openOutputStream(item.uri, "wt")?.use { out ->
            cropped.inputStream().use { it.copyTo(out) }
        } ?: return@withContext false
        forgetCachedImages(context, item.uri)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't save over ${item.uri}", e)
        false
    } finally {
        cropped.delete()
    }
}

/**
 * Copies the original's photo details (dates, camera, exposure, location) onto the cropped file,
 * which is already upright, so its orientation is reset. Best effort: a photo without them, or a
 * format that can't hold them, keeps going without.
 */
private fun copyMetadata(context: Context, original: Uri, cropped: File) {
    try {
        val source = context.contentResolver.openInputStream(original)?.use { ExifInterface(it) } ?: return
        val target = ExifInterface(cropped.absolutePath)
        for (tag in KEPT_EXIF_TAGS) {
            source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.saveAttributes()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't carry the photo details over", e)
    }
}

/** Drops images of [uri] decoded before it changed, so the viewer shows the new version. */
private fun forgetCachedImages(context: Context, uri: Uri) {
    val cache = SingletonImageLoader.get(context).memoryCache ?: return
    val key = uri.toString()
    cache.keys.filter { it.key == key }.forEach { cache.remove(it) }
}

/**
 * Saves [edit] of the photo [item] as a new photo next to it, at full resolution. PNGs (like
 * screenshots) stay lossless PNGs; everything else becomes a JPEG at quality 100. The original is
 * never touched. Returns the new photo's uri, or null if it couldn't be saved.
 */
suspend fun saveEditedPhoto(context: Context, item: MediaItem, edit: PhotoEdit): Uri? = withContext(Dispatchers.IO) {
    val bitmap = try {
        renderEdit(context, item, edit)
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

/** What to change in a photo. */
data class PhotoEdit(val crop: CropRect, val transform: Transform, val adjustments: Adjustments)

/** The edited photo at full resolution: turned, mirrored, straightened, cropped and adjusted. */
private fun renderEdit(context: Context, item: MediaItem, edit: PhotoEdit): Bitmap? {
    RegionSource.open(context.contentResolver, item.uri, item.orientationDegrees)?.use { source ->
        // Only the part the crop needs is decoded, so even a 50 MP photo needs no more memory than that.
        return render(source.width, source.height, edit) { rect ->
            source.decode(PixelRect(rect.left, rect.top, rect.right, rect.bottom), sampleSize = 1)
        }
    }
    // Formats the region decoder can't read: decode the whole picture upright, then cut it.
    val whole = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    } ?: return null
    return try {
        render(whole.width, whole.height, edit) { rect ->
            Bitmap.createBitmap(whole, rect.left, rect.top, rect.width, rect.height).let {
                // createBitmap hands back the same bitmap for the whole picture; keep it apart.
                if (it === whole) it.copy(it.config ?: Bitmap.Config.ARGB_8888, true) else it
            }
        }
    } finally {
        whole.recycle()
    }
}

/**
 * Draws [edit] of a [width] by [height] upright picture. [decode] supplies the part of the
 * picture the result needs. A crop alone hands that part straight back, pixel for pixel.
 */
private fun render(width: Int, height: Int, edit: PhotoEdit, decode: (CropPixels) -> Bitmap?): Bitmap? {
    val transform = edit.transform
    val toFrame = transform.pictureToFrame(width.toFloat(), height.toFloat())
    val (frameWidth, frameHeight) = transform.frameSize(width.toFloat(), height.toFloat())
    val output = edit.crop.toPixels(frameWidth.roundToInt(), frameHeight.roundToInt())

    // The part of the picture under the crop. A tilt needs a pixel to spare for smoothing.
    val back = toFrame.inverse()
    val corners = listOf(
        back.map(output.left.toFloat(), output.top.toFloat()),
        back.map(output.right.toFloat(), output.top.toFloat()),
        back.map(output.left.toFloat(), output.bottom.toFloat()),
        back.map(output.right.toFloat(), output.bottom.toFloat()),
    )
    val tilted = transform.straighten != 0f
    val spare = if (tilted) 2 else 0
    fun low(value: Float) = (if (tilted) floor(value) else value.roundToInt().toFloat()).toInt() - spare
    fun high(value: Float) = (if (tilted) ceil(value) else value.roundToInt().toFloat()).toInt() + spare
    val regionLeft = low(corners.minOf { it.first }).coerceIn(0, width - 1)
    val regionTop = low(corners.minOf { it.second }).coerceIn(0, height - 1)
    val region = CropPixels(
        left = regionLeft,
        top = regionTop,
        right = high(corners.maxOf { it.first }).coerceIn(regionLeft + 1, width),
        bottom = high(corners.maxOf { it.second }).coerceIn(regionTop + 1, height),
    )
    val part = decode(region) ?: return null
    if (transform.isIdentity && edit.adjustments.isNeutral) return part

    val result = Bitmap.createBitmap(
        output.width,
        output.height,
        Bitmap.Config.ARGB_8888,
        true,
        part.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
    )
    val matrix = Affine.translate(region.left.toFloat(), region.top.toFloat())
        .then(toFrame)
        .then(Affine.translate(-output.left.toFloat(), -output.top.toFloat()))
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (!edit.adjustments.isNeutral) colorFilter = ColorMatrixColorFilter(edit.adjustments.colorMatrix())
    }
    Canvas(result).drawBitmap(part, Matrix().apply { setValues(matrix.toMatrixValues()) }, paint)
    part.recycle()
    return result
}

/** What to change in a video: the part to keep (null for all of it), the picture, and the sound. */
data class VideoEdit(
    val trim: TrimRange?,
    val crop: CropRect,
    val transform: Transform,
    val adjustments: Adjustments,
    val mute: Boolean,
)

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
    // Effects work on the upright picture, which is what the editor shows.
    val (frameWidth, frameHeight) = edit.transform.frameSize(source.width.toFloat(), source.height.toFloat())
    val videoEffects = buildList<Effect> {
        if (!edit.transform.isIdentity) add(TurnAndTilt(edit.transform))
        if (!edit.crop.isFull) {
            val ndc = edit.crop.snapToEvenPixels(frameWidth.roundToInt(), frameHeight.roundToInt()).toNdc()
            add(Crop(ndc.left, ndc.right, ndc.bottom, ndc.top))
        }
        addAll(colorEffects(edit.adjustments))
    }
    val edited = EditedMediaItem.Builder(media)
        .setEffects(Effects(emptyList(), videoEffects))
        .setRemoveAudio(edit.mute)
        .build()

    val builder = Transformer.Builder(context)
    if (videoEffects.isNotEmpty()) {
        // Re-encoded: keep roughly the original's quality per pixel rather than the encoder's default.
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
 * Turns, mirrors and tilts each frame like [transform], into a frame of the turned size. Media3
 * tells it the upright frame size before the first frame.
 */
private class TurnAndTilt(private val transform: Transform) : MatrixTransformation {
    private val matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        matrix.setValues(transform.videoMatrix(inputWidth.toFloat(), inputHeight.toFloat()).toMatrixValues())
        return if (transform.sideways) Size(inputHeight, inputWidth) else Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix = matrix
}

/** Media3's closest equivalents of the light and color the preview shows. */
private fun colorEffects(adjustments: Adjustments): List<Effect> = buildList {
    if (adjustments.contrast != 0f) {
        // Media3 stretches by (1 + c) / (1 - c); pick c for the same stretch as the preview.
        val factor = adjustments.contrastFactor
        add(Contrast((factor - 1f) / (factor + 1f)))
    }
    if (adjustments.brightness != 0f) add(Brightness(adjustments.brightnessOffset))
    if (adjustments.saturation != 0f) {
        add(HslAdjustment.Builder().adjustSaturation(adjustments.saturation * 100f).build())
    }
    if (adjustments.warmth != 0f) {
        add(
            RgbAdjustment.Builder()
                .setRedScale(adjustments.redFactor)
                .setBlueScale(adjustments.blueFactor)
                .build(),
        )
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

/** Formats a crop can be written back in. */
private val WRITABLE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private const val JPEG_QUALITY = 100

/** Photo details worth keeping when a photo is replaced by its crop. */
private val KEPT_EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
)
private const val MIN_BITRATE = 2_000_000
private const val PROGRESS_POLL_MILLIS = 200L

/** Longest side of the frames auto fit looks at. */
private const val DETECT_SIZE = 480
private const val DETECT_FRAMES = 7
