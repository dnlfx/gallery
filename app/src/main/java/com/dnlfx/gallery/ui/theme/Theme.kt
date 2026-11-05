package com.dnlfx.gallery.ui.theme

import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive: wallpaper colours on Android 12+, springy expressive motion, rounder
 * shapes, and the phone's own Google Sans where it has it (every Pixel does), so the app reads
 * like part of the system. No font is bundled or downloaded.
 */
@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    val typography = remember { galleryTypography() }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = GalleryShapes,
        typography = typography,
        content = content,
    )
}

/** Colours for the always-dark viewer and editor, whatever the system theme. */
object ViewerColors {
    val Scrim = Color(0xFF000000)
    /** The floating toolbars and pills over a photo: dark glass that keeps any picture readable. */
    val Glass = Color(0xE01B1B1F)
    val GlassStrong = Color(0xF2202024)
    val OnGlass = Color(0xFFF2F2F5)
    val OnGlassMuted = Color(0xB3F2F2F5)
    val Track = Color(0x4DFFFFFF)
}

private val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Pre-Android 12 only; a calm neutral with a cool blue accent.
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3557C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00164E),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF151B2C),
    surface = Color(0xFFFBF8FD),
    surfaceContainer = Color(0xFFEFEDF4),
)
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF00287D),
    primaryContainer = Color(0xFF173DAF),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondaryContainer = Color(0xFF3E4659),
    onSecondaryContainer = Color(0xFFDDE1F9),
    surface = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
)

/**
 * Material's type scale set in Google Sans (display and titles) and Google Sans Text (body),
 * with slightly tighter headlines. Falls back to the default font on phones without them.
 */
private fun galleryTypography(): Typography {
    val base = Typography()
    val display = systemFamily("google-sans-flex", "google-sans")
    val text = systemFamily("google-sans-text", "google-sans-flex", "google-sans")
    fun TextStyle.display(weight: FontWeight? = null, spacing: Float? = null) = copy(
        fontFamily = display ?: fontFamily,
        fontWeight = weight ?: fontWeight,
        letterSpacing = spacing?.em ?: letterSpacing,
    )
    fun TextStyle.text() = copy(fontFamily = text ?: fontFamily)
    return base.copy(
        displayLarge = base.displayLarge.display(spacing = -0.02f),
        displayMedium = base.displayMedium.display(spacing = -0.02f),
        displaySmall = base.displaySmall.display(spacing = -0.01f),
        headlineLarge = base.headlineLarge.display(FontWeight.Medium, -0.01f),
        headlineMedium = base.headlineMedium.display(FontWeight.Medium, -0.01f),
        headlineSmall = base.headlineSmall.display(FontWeight.Medium),
        titleLarge = base.titleLarge.display(FontWeight.Medium),
        titleMedium = base.titleMedium.display(FontWeight.Medium),
        titleSmall = base.titleSmall.display(FontWeight.Medium),
        bodyLarge = base.bodyLarge.text(),
        bodyMedium = base.bodyMedium.text(),
        bodySmall = base.bodySmall.text(),
        labelLarge = base.labelLarge.display(FontWeight.Medium),
        labelMedium = base.labelMedium.display(FontWeight.Medium),
        labelSmall = base.labelSmall.display(FontWeight.Medium).copy(fontSize = 11.sp),
        displayLargeEmphasized = base.displayLargeEmphasized.display(FontWeight.SemiBold, -0.02f),
        displayMediumEmphasized = base.displayMediumEmphasized.display(FontWeight.SemiBold, -0.02f),
        displaySmallEmphasized = base.displaySmallEmphasized.display(FontWeight.SemiBold, -0.01f),
        headlineLargeEmphasized = base.headlineLargeEmphasized.display(FontWeight.SemiBold, -0.01f),
        headlineMediumEmphasized = base.headlineMediumEmphasized.display(FontWeight.SemiBold, -0.01f),
        headlineSmallEmphasized = base.headlineSmallEmphasized.display(FontWeight.SemiBold),
        titleLargeEmphasized = base.titleLargeEmphasized.display(FontWeight.SemiBold),
        titleMediumEmphasized = base.titleMediumEmphasized.display(FontWeight.SemiBold),
        titleSmallEmphasized = base.titleSmallEmphasized.display(FontWeight.SemiBold),
        labelLargeEmphasized = base.labelLargeEmphasized.display(FontWeight.SemiBold),
        labelMediumEmphasized = base.labelMediumEmphasized.display(FontWeight.SemiBold),
        labelSmallEmphasized = base.labelSmallEmphasized.display(FontWeight.SemiBold),
        bodyLargeEmphasized = base.bodyLargeEmphasized.text(),
        bodyMediumEmphasized = base.bodyMediumEmphasized.text(),
        bodySmallEmphasized = base.bodySmallEmphasized.text(),
    )
}

/** The first of [names] the system has installed, or null when it has none of them. */
private fun systemFamily(vararg names: String): FontFamily? {
    for (name in names) {
        val typeface = Typeface.create(name, Typeface.NORMAL)
        // An unknown name quietly gives back the default font.
        if (typeface != null && typeface != Typeface.DEFAULT) return FontFamily(typeface)
    }
    return null
}
