package com.dnlfx.gallery.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the viewer needs that are missing from material-icons-core, drawn from the
 * Material path data so the much larger extended icon set isn't pulled in.
 */
internal object ViewerIcons {
    val Pause: ImageVector = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")

    val Brightness: ImageVector = icon(
        "Brightness",
        "M20,15.31L23.31,12 20,8.69V4h-4.69L12,0.69 8.69,4H4v4.69L0.69,12 4,15.31V20h4.69L12,23.31 " +
            "15.31,20H20v-4.69zM12,18V6c3.31,0 6,2.69 6,6s-2.69,6 -6,6z",
    )

    val Repeat: ImageVector = icon("Repeat", "M7,7h10v3l4,-4 -4,-4v3L5,6v6h2L7,7zM17,17L7,17v-3l-4,4 4,4v-3h12v-6h-2v4z")

    val PhotoCamera: ImageVector = icon(
        "PhotoCamera",
        "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0" +
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6" +
            "c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
    )

    val ScreenRotation: ImageVector = icon(
        "ScreenRotation",
        "M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 " +
            "3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11" +
            "c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36" +
            "c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 " +
            "12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13H0.05" +
            "C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z",
    )

    val VolumeUp: ImageVector = icon(
        "VolumeUp",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05" +
            "c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71" +
            "v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )

    val VolumeOff: ImageVector = icon(
        "VolumeOff",
        "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z" +
            "M19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12" +
            "c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4" +
            "l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 " +
            "3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
    )

    val Undo: ImageVector = icon(
        "Undo",
        "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88" +
            " 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    )

    val Compare: ImageVector = icon(
        "Compare",
        "M10,3H5c-1.11,0 -2,0.89 -2,2v14c0,1.1 0.89,2 2,2h5v2h2V1h-2v2zM10,18H5l5,-6v6z" +
            "M19,3h-5v2h5v13l-5,-6v9h5c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z",
    )

    val RotateRight: ImageVector = icon(
        "RotateRight",
        "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91" +
            "s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42" +
            "c0.54,0.75 0.88,1.6 1.02,2.47h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44" +
            "c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02" +
            "c-0.14,0.87 -0.48,1.72 -1.02,2.48z",
    )

    /** Mirror left to right; turned a quarter, it reads as top to bottom. */
    val Flip: ImageVector = icon(
        "Flip",
        "M15,21h2v-2h-2v2zM19,9h2L21,7h-2v2zM3,5v14c0,1.1 0.9,2 2,2h4v-2L5,19L5,5h4L9,3L5,3c-1.1,0 -2,0.9 -2,2z" +
            "M19,3v2h2c0,-1.1 -0.9,-2 -2,-2zM11,23h2L13,1h-2v22zM19,17h2v-2h-2v2zM15,5h2L17,3h-2v2z" +
            "M19,13h2v-2h-2v2zM19,21c1.1,0 2,-0.9 2,-2h-2v2z",
    )

    val Portrait: ImageVector = icon(
        "Portrait",
        "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99z" +
            "M17,19H7V5h10v14z",
    )

    val AutoFix: ImageVector = icon(
        "AutoFix",
        "M7.5,5.6L10,7 8.6,4.5 10,2 7.5,3.4 5,2l1.4,2.5L5,7zM19.5,15.4L17,14l1.4,2.5L17,19l2.5,-1.4L22,19" +
            "l-1.4,-2.5L22,14zM22,2l-2.5,1.4L17,2l1.4,2.5L17,7l2.5,-1.4L22,7l-1.4,-2.5zM14.37,7.29" +
            "c-0.39,-0.39 -1.02,-0.39 -1.41,0L1.29,18.96c-0.39,0.39 -0.39,1.02 0,1.41l2.34,2.34" +
            "c0.39,0.39 1.02,0.39 1.41,0L16.7,11.05c0.39,-0.39 0.39,-1.02 0,-1.41l-2.33,-2.35z" +
            "M13.34,12.78l-2.12,-2.12 2.44,-2.44 2.12,2.12 -2.44,2.44z",
    )

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
