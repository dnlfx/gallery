package com.dnlfx.gallery.thumbnail

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size as AndroidSize
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Grid thumbnail request. [version] (the item's modified time) busts the cache when a file changes.
 */
data class MediaThumbnail(val uri: Uri, val version: Long)

/**
 * Loads thumbnails through the system's MediaStore thumbnail cache, which uses the platform
 * decoders (HEIC, AVIF, WebP, video frames and so on) and is far cheaper than decoding originals.
 */
class MediaThumbnailFetcher(
    private val data: MediaThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_SIZE_PX }
        val height = options.size.height.pxOrElse { DEFAULT_SIZE_PX }
        // Cells scrolled past during a fling cancel their request; pass that on so the system
        // stops decoding thumbnails nobody will see.
        val signal = CancellationSignal()
        val bitmap = coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    signal.cancel()
                }
            }
            try {
                resolver.loadThumbnail(data.uri, AndroidSize(width, height), signal)
            } finally {
                watcher.cancel()
            }
        }
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options, options.context.contentResolver)
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 384
    }
}

class MediaThumbnailKeyer : Keyer<MediaThumbnail> {
    override fun key(data: MediaThumbnail, options: Options): String = "${data.uri}#${data.version}"
}
