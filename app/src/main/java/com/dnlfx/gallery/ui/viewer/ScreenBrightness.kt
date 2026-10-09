package com.dnlfx.gallery.ui.viewer

import android.provider.Settings
import android.view.Window
import android.view.WindowManager

/**
 * How bright the screen is now, as 0..1: the viewer's own level if a slide has set one, otherwise
 * the phone's brightness setting.
 */
fun Window.currentBrightness(): Float {
    val own = attributes.screenBrightness
    if (own >= 0f) return own
    val system = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, HALF_BRIGHTNESS)
    return (system / MAX_BRIGHTNESS.toFloat()).coerceIn(0f, 1f)
}

/** Sets the brightness for this window only; the phone's own setting is left alone. */
fun Window.setBrightness(fraction: Float) {
    attributes = attributes.apply { screenBrightness = fraction.coerceIn(MIN_FRACTION, 1f) }
}

/** Goes back to the phone's brightness. */
fun Window.resetBrightness() {
    attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
}

private const val MAX_BRIGHTNESS = 255
private const val HALF_BRIGHTNESS = 128

// Zero is the dimmest the backlight goes; keep a sliver above it so the picture stays visible.
private const val MIN_FRACTION = 0.01f
