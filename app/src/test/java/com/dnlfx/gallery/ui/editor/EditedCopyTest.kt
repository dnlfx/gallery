package com.dnlfx.gallery.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditedCopyTest {
    @Test
    fun copiesGoNextToTheOriginalWhereAllowed() {
        assertEquals("DCIM/Camera/", editedCopyFolder("DCIM/Camera/", isVideo = false))
        assertEquals("Pictures/Screenshots/", editedCopyFolder("Pictures/Screenshots", isVideo = false))
        assertEquals("Movies/Screen recordings/", editedCopyFolder("Movies/Screen recordings/", isVideo = true))
    }

    @Test
    fun otherFoldersFallBackToGalleryFolders() {
        assertEquals("Pictures/Gallery/", editedCopyFolder("Download/", isVideo = false))
        assertEquals("Pictures/Gallery/", editedCopyFolder("Movies/", isVideo = false))
        assertEquals("Movies/Gallery/", editedCopyFolder(null, isVideo = true))
    }

    @Test
    fun namesKeepTheOriginalAndAddEdited() {
        assertEquals("PXL_20261008_101500123_edited.mp4", editedCopyName("PXL_20261008_101500123.mp4", "mp4"))
        assertEquals("Screenshot_edited.png", editedCopyName("Screenshot.png", "png"))
        assertEquals("Gallery_edited.jpg", editedCopyName(null, "jpg"))
    }
}
