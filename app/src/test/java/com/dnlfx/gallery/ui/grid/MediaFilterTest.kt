package com.dnlfx.gallery.ui.grid

import com.dnlfx.gallery.data.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterTest {
    private fun MediaFilter.photo(path: String?, name: String? = "PXL_1.jpg", favorite: Boolean = false) =
        matches(MediaType.IMAGE, path, name, favorite)

    private fun MediaFilter.video(path: String?, name: String? = "PXL_1.mp4") =
        matches(MediaType.VIDEO, path, name, false)

    @Test
    fun allMatchesEverything() {
        assertTrue(MediaFilter.ALL.photo(null))
        assertTrue(MediaFilter.ALL.video("Movies/"))
    }

    @Test
    fun photosAndVideosSplitByType() {
        assertTrue(MediaFilter.PHOTOS.photo("DCIM/Camera/"))
        assertFalse(MediaFilter.PHOTOS.video("DCIM/Camera/"))
        assertTrue(MediaFilter.VIDEOS.video("DCIM/Camera/"))
        assertFalse(MediaFilter.VIDEOS.photo("DCIM/Camera/"))
    }

    @Test
    fun cameraIsTheCameraFolderOnly() {
        assertTrue(MediaFilter.CAMERA.photo("DCIM/Camera/"))
        assertTrue(MediaFilter.CAMERA.video("DCIM/Camera/"))
        assertFalse(MediaFilter.CAMERA.photo("DCIM/Screenshots/"))
        assertFalse(MediaFilter.CAMERA.photo(null))
    }

    @Test
    fun screenshotsFromEitherFolder() {
        assertTrue(MediaFilter.SCREENSHOTS.photo("Pictures/Screenshots/"))
        assertTrue(MediaFilter.SCREENSHOTS.photo("DCIM/Screenshots/"))
        assertFalse(MediaFilter.SCREENSHOTS.photo("Pictures/Screenshots old/"))
        assertFalse(MediaFilter.SCREENSHOTS.photo("DCIM/Camera/"))
    }

    @Test
    fun screenRecordingsByFolderOrName() {
        assertTrue(MediaFilter.SCREEN_RECORDINGS.video("Movies/Screen recordings/"))
        assertTrue(MediaFilter.SCREEN_RECORDINGS.video("Movies/", "Screen_Recording_20261009-101500.mp4"))
        assertTrue(MediaFilter.SCREEN_RECORDINGS.video("Movies/", "screen-20261009-101500.mp4"))
        assertFalse(MediaFilter.SCREEN_RECORDINGS.video("Movies/", "holiday.mp4"))
        assertFalse(MediaFilter.SCREEN_RECORDINGS.video("DCIM/Camera/", "screen-1.mp4"))
        assertFalse(MediaFilter.SCREEN_RECORDINGS.photo("Movies/Screen recordings/"))
    }

    @Test
    fun downloadsIncludeSubfolders() {
        assertTrue(MediaFilter.DOWNLOADS.photo("Download/"))
        assertTrue(MediaFilter.DOWNLOADS.video("Download/Telegram/"))
        assertFalse(MediaFilter.DOWNLOADS.photo("Pictures/Download/"))
    }

    @Test
    fun favoritesFollowTheFlag() {
        assertTrue(MediaFilter.FAVORITES.photo("DCIM/Camera/", favorite = true))
        assertFalse(MediaFilter.FAVORITES.photo("DCIM/Camera/", favorite = false))
    }

    @Test
    fun emptyFiltersAreHiddenUnlessSelected() {
        assertEquals(listOf(MediaFilter.ALL), availableFilters(emptyList(), MediaFilter.ALL))
        assertEquals(
            listOf(MediaFilter.ALL, MediaFilter.DOWNLOADS),
            availableFilters(emptyList(), MediaFilter.DOWNLOADS),
        )
    }
}
