package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.graphics.Rect

/** A corrected bitmap and its uncorrected original, both in display orientation, covering [rect]. */
class Layer(
    val corrected: Bitmap,
    val original: Bitmap,
    /** The area of the photo this layer covers, in full-resolution display coordinates. */
    val rect: Rect,
) {
    /** Where it sits in the sampled photo it was corrected as, to correct it again (set by the renderer). */
    var place: Placement? = null

    /** Bytes held by both bitmaps. */
    val bytes: Int get() = corrected.allocationByteCount + original.allocationByteCount
}

/** Pixels for the viewer (design D4): one [ViewerPhoto] per page. The real one decodes and corrects on the GPU; UI tests use fakes. */
fun interface ViewerRenderer {
    /** Opens [source] for display. Throws if its correction cannot be prepared ("preview unavailable"). */
    suspend fun open(source: String): ViewerPhoto
}

/** One photo in the viewer. Bitmaps it returns stay valid until [close]. */
interface ViewerPhoto : AutoCloseable {
    /** Full-resolution size in display orientation. */
    val width: Int
    val height: Int

    /** The whole photo, decoded at the largest sample size that still covers [fitWidth]×[fitHeight] screen pixels. */
    suspend fun base(fitWidth: Int, fitHeight: Int): Layer

    /**
     * Tiles at [sampleSize] covering [visible] (full-resolution display coordinates). Tiles already made are
     * reused. Returns null if this photo cannot be decoded by region; the viewer then shows only the base.
     */
    suspend fun tiles(sampleSize: Int, visible: Rect): List<Layer>?

    /** Re-renders the base and kept tiles from their originals at [strength], without new decodes or estimates (design D6). */
    suspend fun setStrength(strength: Float)
}

/** Maps rectangles between stored pixel coordinates and display (EXIF-rotated) coordinates. */
class Orientation(val rotation: Int, val storedWidth: Int, val storedHeight: Int) {
    init {
        require(rotation in setOf(0, 90, 180, 270)) { "rotation $rotation" }
    }

    val displayWidth: Int get() = if (rotation % 180 == 0) storedWidth else storedHeight
    val displayHeight: Int get() = if (rotation % 180 == 0) storedHeight else storedWidth

    /** A stored rectangle in display coordinates (rotation clockwise). */
    fun toDisplay(r: Rect): Rect = when (rotation) {
        90 -> Rect(storedHeight - r.bottom, r.left, storedHeight - r.top, r.right)
        180 -> Rect(storedWidth - r.right, storedHeight - r.bottom, storedWidth - r.left, storedHeight - r.top)
        270 -> Rect(r.top, storedWidth - r.right, r.bottom, storedWidth - r.left)
        else -> Rect(r)
    }

    /** A display rectangle in stored coordinates. */
    fun toStored(r: Rect): Rect = when (rotation) {
        90 -> Rect(r.top, storedHeight - r.right, r.bottom, storedHeight - r.left)
        180 -> Rect(storedWidth - r.right, storedHeight - r.bottom, storedWidth - r.left, storedHeight - r.top)
        270 -> Rect(storedWidth - r.bottom, r.left, storedWidth - r.top, r.right)
        else -> Rect(r)
    }
}
