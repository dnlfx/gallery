package com.dnlfx.gallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dnlfx.gallery.ui.GalleryApp
import com.dnlfx.gallery.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Another app (Files, Messages, the camera) asked to show one photo or video.
        val opened = intent.takeIf { it.action == Intent.ACTION_VIEW || it.action == ACTION_REVIEW }
        setContent {
            GalleryTheme {
                GalleryApp(
                    openedUri = opened?.data,
                    openedMimeType = opened?.type,
                    onFinish = { finish() },
                )
            }
        }
    }

    private companion object {
        /** What camera apps send to show the shot just taken. */
        const val ACTION_REVIEW = "com.android.camera.action.REVIEW"
    }
}
