package com.dnlfx.gallery.ui.viewer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable

/**
 * Decodes parts of a photo at full resolution, for zooming in past what the screen-sized image
 * holds. Works for the formats the platform's region decoder reads: JPEG, PNG, WebP and HEIC,
 * plus AVIF on recent Android versions. [open] returns null for anything else.
 */
class RegionSource private constructor(
    private val file: ParcelFileDescriptor,
    private val decoder: BitmapRegionDecoder,
    /** Clockwise turn the stored pixels need to display upright. */
    private val rotation: Int,
) : Closeable {

    private val sideways = rotation == 90 || rotation == 270

    /** Full size of the upright photo. */
    val width: Int = if (sideways) decoder.height else decoder.width
    val height: Int = if (sideways) decoder.width else decoder.height

    /** Decodes [rect] of the upright photo, shrunk by [sampleSize], already turned upright. */
    @Synchronized
    fun decode(rect: PixelRect, sampleSize: Int): Bitmap? {
        if (decoder.isRecycled) return null
        val stored = uprightToStored(rect, rotation, decoder.width, decoder.height)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val raw = try {
            decoder.decodeRegion(Rect(stored.left, stored.top, stored.right, stored.bottom), options)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (rotation == 0) return raw
        val turn = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, turn, true).also {
            if (it !== raw) raw.recycle()
        }
    }

    @Synchronized
    override fun close() {
        decoder.recycle()
        file.close()
    }

    companion object {
        fun open(resolver: ContentResolver, uri: Uri, rotation: Int): RegionSource? {
            val file = try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: return null
            val decoder = try {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.fileDescriptor, false)
            } catch (e: Exception) {
                null
            }
            if (decoder == null) {
                file.close()
                return null
            }
            return RegionSource(file, decoder, rotation)
        }
    }
}
