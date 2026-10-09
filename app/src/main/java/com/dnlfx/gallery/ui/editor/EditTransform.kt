package com.dnlfx.gallery.ui.editor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A 2D affine map, x' = a·x + b·y + tx and y' = c·x + d·y + ty, in screen-style coordinates where
 * y points down, so a positive rotation turns clockwise.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    fun map(x: Float, y: Float): Pair<Float, Float> = Pair(a * x + b * y + tx, c * x + d * y + ty)

    /** This map, then [next]. */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.b * c,
        b = next.a * b + next.b * d,
        c = next.c * a + next.d * c,
        d = next.c * b + next.d * d,
        tx = next.a * tx + next.b * ty + next.tx,
        ty = next.c * tx + next.d * ty + next.ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** The values Android's Matrix.setValues takes. */
    fun toMatrixValues(): FloatArray = floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)

    companion object {
        val Identity = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(x: Float, y: Float) = Affine(1f, 0f, 0f, 1f, x, y)
        fun scale(x: Float, y: Float) = Affine(x, 0f, 0f, y, 0f, 0f)

        /** Clockwise on screen by [degrees]. */
        fun rotate(degrees: Float): Affine {
            val radians = Math.toRadians(degrees.toDouble())
            val cos = cos(radians).toFloat()
            val sin = sin(radians).toFloat()
            return Affine(cos, -sin, sin, cos, 0f, 0f)
        }
    }
}

/**
 * How the picture is turned: mirrored first ([flipHorizontal], [flipVertical], in the picture's
 * own upright terms), then turned clockwise by [quarterTurns] × 90°, then tilted clockwise by
 * [straighten] degrees and zoomed just enough that no corner shows. The result fills a frame the
 * size of the turned picture, and the crop is measured on that frame.
 */
data class Transform(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val straighten: Float = 0f,
) {
    val sideways: Boolean get() = quarterTurns % 2 != 0
    val isIdentity: Boolean
        get() = quarterTurns == 0 && !flipHorizontal && !flipVertical && straighten == 0f

    /** Total clockwise turn in degrees. */
    val rotationDegrees: Float get() = quarterTurns * 90f + straighten

    /** Turned another 90° clockwise, as seen on screen. */
    fun rotatedClockwise(): Transform = copy(quarterTurns = (quarterTurns + 1) % 4)

    /**
     * Mirrored left to right as seen on screen. Mirroring the turned picture is the same as
     * mirroring the original and turning it the other way.
     */
    fun mirroredHorizontally(): Transform =
        copy(flipHorizontal = !flipHorizontal, quarterTurns = (4 - quarterTurns) % 4, straighten = -straighten)

    /** Mirrored top to bottom as seen on screen. */
    fun mirroredVertically(): Transform =
        copy(flipVertical = !flipVertical, quarterTurns = (4 - quarterTurns) % 4, straighten = -straighten)

    /** Width and height of the frame for a [width] by [height] picture. */
    fun frameSize(width: Float, height: Float): Pair<Float, Float> =
        if (sideways) Pair(height, width) else Pair(width, height)

    /** How much the tilted picture is zoomed so it covers the frame. */
    fun zoom(frameWidth: Float, frameHeight: Float): Float = straightenZoom(frameWidth, frameHeight, straighten)

    /** From a point on the [width] by [height] picture to the same point on the frame. */
    fun pictureToFrame(width: Float, height: Float): Affine {
        val (frameWidth, frameHeight) = frameSize(width, height)
        val zoom = zoom(frameWidth, frameHeight)
        return Affine.translate(-width / 2f, -height / 2f)
            .then(Affine.scale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f))
            .then(Affine.rotate(rotationDegrees))
            .then(Affine.scale(zoom, zoom))
            .then(Affine.translate(frameWidth / 2f, frameHeight / 2f))
    }

    /**
     * The same map for Media3's video effects, which work in normalized device coordinates (-1 to
     * 1, y pointing up) on both the input and the frame.
     */
    fun videoMatrix(width: Float, height: Float): Affine {
        val (frameWidth, frameHeight) = frameSize(width, height)
        val flipY = Affine.scale(1f, -1f)
        return Affine.scale(width / 2f, height / 2f)
            .then(flipY)
            .then(Affine.translate(width / 2f, height / 2f))
            .then(pictureToFrame(width, height))
            .then(Affine.translate(-frameWidth / 2f, -frameHeight / 2f))
            .then(flipY)
            .then(Affine.scale(2f / frameWidth, 2f / frameHeight))
    }

    /**
     * Where a rectangle on the original picture lands on the frame, as fractions, for a picture
     * of [aspectRatio]. With a tilt, it's the box around the tilted rectangle, kept inside.
     */
    fun mapCrop(crop: CropRect, aspectRatio: Float): CropRect {
        val map = pictureToFrame(aspectRatio, 1f)
        val (frameWidth, frameHeight) = frameSize(aspectRatio, 1f)
        val corners = listOf(
            map.map(crop.left * aspectRatio, crop.top),
            map.map(crop.right * aspectRatio, crop.top),
            map.map(crop.left * aspectRatio, crop.bottom),
            map.map(crop.right * aspectRatio, crop.bottom),
        )
        return CropRect(
            left = (corners.minOf { it.first } / frameWidth).coerceIn(0f, 1f),
            top = (corners.minOf { it.second } / frameHeight).coerceIn(0f, 1f),
            right = (corners.maxOf { it.first } / frameWidth).coerceIn(0f, 1f),
            bottom = (corners.maxOf { it.second } / frameHeight).coerceIn(0f, 1f),
        )
    }
}

/**
 * The zoom a [width] by [height] picture needs when tilted by [degrees] so it still covers its
 * own frame: 1 when level, more the further it's tilted.
 */
fun straightenZoom(width: Float, height: Float, degrees: Float): Float {
    if (degrees == 0f || width <= 0f || height <= 0f) return 1f
    val radians = Math.toRadians(abs(degrees).toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    return cos + max(width / height, height / width) * sin
}

/** [crop] after the frame turns 90° clockwise. */
fun CropRect.rotatedClockwise(): CropRect = CropRect(1f - bottom, left, 1f - top, right)

/** [crop] after the frame is mirrored left to right. */
fun CropRect.mirroredHorizontally(): CropRect = CropRect(1f - right, top, 1f - left, bottom)

/** [crop] after the frame is mirrored top to bottom. */
fun CropRect.mirroredVertically(): CropRect = CropRect(left, 1f - bottom, right, 1f - top)

/** Light and color, each from -1 to 1 with 0 leaving the picture as it is. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isNeutral: Boolean get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f

    /** How much brighter, as a share of full white. */
    val brightnessOffset: Float get() = brightness * BRIGHTNESS_RANGE

    /** How much contrast is stretched around mid gray. */
    val contrastFactor: Float get() = 1f + contrast * CONTRAST_RANGE

    /** How much color is multiplied (0 is gray, 1 unchanged). */
    val saturationFactor: Float get() = 1f + saturation

    /** Red and blue multipliers for warmth. */
    val redFactor: Float get() = 1f + warmth * WARMTH_RANGE
    val blueFactor: Float get() = 1f - warmth * WARMTH_RANGE

    /**
     * A 4×5 color matrix (rows R, G, B, A; offsets in 0–255) in Android's ColorMatrix layout:
     * contrast, then brightness, then saturation, then warmth.
     */
    fun colorMatrix(): FloatArray {
        val f = contrastFactor
        val offset = 127.5f * (1f - f) + brightnessOffset * 255f
        var m = floatArrayOf(
            f, 0f, 0f, 0f, offset,
            0f, f, 0f, 0f, offset,
            0f, 0f, f, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
        // Saturation around the luminance of each pixel (Rec. 709 weights).
        val s = saturationFactor
        val lr = 0.2126f * (1f - s)
        val lg = 0.7152f * (1f - s)
        val lb = 0.0722f * (1f - s)
        m = concat(
            floatArrayOf(
                lr + s, lg, lb, 0f, 0f,
                lr, lg + s, lb, 0f, 0f,
                lr, lg, lb + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            m,
        )
        m = concat(
            floatArrayOf(
                redFactor, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, blueFactor, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            m,
        )
        return m
    }

    companion object {
        const val BRIGHTNESS_RANGE = 0.25f
        const val CONTRAST_RANGE = 0.5f
        const val WARMTH_RANGE = 0.15f
    }
}

/** [outer] applied after [inner], both 4×5 color matrices. */
private fun concat(outer: FloatArray, inner: FloatArray): FloatArray {
    val result = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            var sum = 0f
            for (k in 0 until 4) sum += outer[row * 5 + k] * inner[k * 5 + col]
            if (col == 4) sum += outer[row * 5 + 4]
            result[row * 5 + col] = sum
        }
    }
    return result
}

/** A crop shape: free, or locked to a width-to-height ratio. */
enum class AspectChoice(val width: Float, val height: Float) {
    Free(0f, 0f),

    /** The picture's own shape. */
    Original(0f, 0f),
    Square(1f, 1f),
    FourThree(4f, 3f),
    SixteenNine(16f, 9f),
}

/**
 * The width-to-height ratio [choice] locks the crop to, in pixels on a frame of [frameAspect], or
 * null when it's free. [portrait] stands the shape on end (taller than wide).
 */
fun lockedRatio(choice: AspectChoice, portrait: Boolean, frameAspect: Float): Float? {
    val landscape = when (choice) {
        AspectChoice.Free -> return null
        AspectChoice.Original -> max(frameAspect, 1f / frameAspect)
        else -> choice.width / choice.height
    }
    return if (portrait) 1f / landscape else landscape
}
