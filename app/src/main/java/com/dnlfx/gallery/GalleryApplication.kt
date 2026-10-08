package com.dnlfx.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.dnlfx.gallery.thumbnail.MediaThumbnailFetcher
import com.dnlfx.gallery.thumbnail.MediaThumbnailKeyer

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailKeyer())
                add(MediaThumbnailFetcher.Factory())
            }
            .build()
}
