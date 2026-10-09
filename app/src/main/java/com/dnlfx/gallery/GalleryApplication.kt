package com.dnlfx.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import com.dnlfx.gallery.thumbnail.MediaThumbnailFetcher
import com.dnlfx.gallery.thumbnail.MediaThumbnailKeyer
import com.dnlfx.gallery.ui.editor.clearUnfinishedEdits
import kotlin.concurrent.thread

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Nothing can be editing yet, so anything an earlier run left half saved can go.
        thread(name = "clear-edits", priority = Thread.MIN_PRIORITY) { clearUnfinishedEdits(this) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailKeyer())
                add(MediaThumbnailFetcher.Factory())
                // GIFs, animated WebPs and animated HEIFs play in the viewer; stills use the default decoder.
                add(AnimatedImageDecoder.Factory())
            }
            .build()
}
