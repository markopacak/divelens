package com.markopacak.divelens.watermark

import kotlin.math.max
import kotlin.math.min

/** An axis-aligned rectangle in pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Where the mark goes in a picture of [displayWidth] × [displayHeight] as it is seen (add-branding-and-watermark D5,
 * BRAND.md): the mark's whole viewBox is [WIDTH_SHARE] of the width, [MARGIN_SHARE] of the width from the right and
 * bottom edges. [measured] is the box grown by [PADDING_SHARE] of its size on each side, clamped to the picture: the
 * area whose brightness picks the light or dark version.
 */
class WatermarkPlacement(val displayWidth: Int, val displayHeight: Int, aspect: Float) {
    val box: Box
    val measured: Box

    init {
        val w = WIDTH_SHARE * displayWidth
        val h = w * aspect
        val margin = MARGIN_SHARE * displayWidth
        box = Box(displayWidth - margin - w, displayHeight - margin - h, displayWidth - margin, displayHeight - margin)
        val padX = PADDING_SHARE * w
        val padY = PADDING_SHARE * h
        measured = Box(
            max(0f, box.left - padX), max(0f, box.top - padY),
            min(displayWidth.toFloat(), box.right + padX), min(displayHeight.toFloat(), box.bottom + padY),
        )
    }

    companion object {
        const val WIDTH_SHARE = 0.12f
        const val MARGIN_SHARE = 0.03f
        const val PADDING_SHARE = 0.10f

        /** The opacity of the whole mark. */
        const val OPACITY = 0.92f
    }
}

/** A 2D affine map: `x' = a·x + b·y + c`, `y' = d·x + e·y + f`. */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float) = a * x + b * y + c
    fun mapY(x: Float, y: Float) = d * x + e * y + f

    /** The box covering [box] after mapping; exact for the 90° rotations and mirrors used here. */
    fun map(box: Box): Box {
        val xs = listOf(mapX(box.left, box.top), mapX(box.right, box.bottom))
        val ys = listOf(mapY(box.left, box.top), mapY(box.right, box.bottom))
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Row-major values for `android.graphics.Matrix.setValues`. */
    fun matrixValues() = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)
}

/**
 * The EXIF orientations 1–8 (add-branding-and-watermark D5): a photo's pixels are stored [width] × [height] and shown
 * rotated or mirrored by its orientation tag. Unknown values count as 1 (normal), as the gallery does.
 */
object ExifOrientation {
    fun swapsAxes(orientation: Int) = orientation in 5..8

    fun displayWidth(width: Int, height: Int, orientation: Int) = if (swapsAxes(orientation)) height else width
    fun displayHeight(width: Int, height: Int, orientation: Int) = if (swapsAxes(orientation)) width else height

    /** Maps a point of the picture as shown to the stored pixel it comes from. */
    fun displayToStored(width: Int, height: Int, orientation: Int): Affine {
        val w = width.toFloat(); val h = height.toFloat()
        return when (orientation) {
            2 -> Affine(-1f, 0f, w, 0f, 1f, 0f) // mirrored left-right
            3 -> Affine(-1f, 0f, w, 0f, -1f, h) // rotated 180°
            4 -> Affine(1f, 0f, 0f, 0f, -1f, h) // mirrored top-bottom
            5 -> Affine(0f, 1f, 0f, 1f, 0f, 0f) // transposed
            6 -> Affine(0f, 1f, 0f, -1f, 0f, h) // rotated 90° clockwise
            7 -> Affine(0f, -1f, w, -1f, 0f, h) // transversed
            8 -> Affine(0f, -1f, w, 1f, 0f, 0f) // rotated 90° counter-clockwise
            else -> Affine(1f, 0f, 0f, 0f, 1f, 0f)
        }
    }
}
