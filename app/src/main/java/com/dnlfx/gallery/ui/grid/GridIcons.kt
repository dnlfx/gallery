package com.dnlfx.gallery.ui.grid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material icons that material-icons-core doesn't include. */
internal object GridIcons {
    val Photo: ImageVector = ImageVector.Builder(
        name = "Photo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        ),
        fill = SolidColor(Color.Black),
    ).build()

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
