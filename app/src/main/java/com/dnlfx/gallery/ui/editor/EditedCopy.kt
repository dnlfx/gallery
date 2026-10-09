package com.dnlfx.gallery.ui.editor

/**
 * Where an edited copy goes: next to the original when MediaStore allows that kind of file
 * there (photos in DCIM or Pictures; videos in DCIM, Movies or Pictures), otherwise in
 * Pictures/Gallery or Movies/Gallery.
 */
fun editedCopyFolder(originalFolder: String?, isVideo: Boolean): String {
    val allowed = if (isVideo) listOf("DCIM", "Movies", "Pictures") else listOf("DCIM", "Pictures")
    val top = originalFolder?.substringBefore('/')
    if (originalFolder != null && top in allowed) {
        return if (originalFolder.endsWith('/')) originalFolder else "$originalFolder/"
    }
    return if (isVideo) "Movies/Gallery/" else "Pictures/Gallery/"
}

/**
 * "PXL_20261008_101500123_edited.jpg" for an edited copy of "PXL_20261008_101500123.mp4" saved
 * with [extension]. MediaStore adds a number if the name is taken.
 */
fun editedCopyName(originalName: String?, extension: String): String {
    val base = originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Gallery"
    return "${base}_edited.$extension"
}
