package com.dnlfx.gallery.ui.grid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The Material sort icon, which material-icons-core doesn't include. */
internal object GridIcons {
    val Sort: ImageVector = ImageVector.Builder(
        name = "Sort",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).addPath(
        pathData = addPathNodes("M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z"),
        fill = SolidColor(Color.Black),
    ).build()
}
