package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw access to rectangles of an `ARGB_8888` bitmap as RGBA bytes, without colour-space conversion
 * (unlike `getPixels`/`setPixels`, which convert to and from sRGB). The correction works on the
 * bitmap's own encoded values (design D6).
 */
internal class BitmapTiles(private val bitmap: Bitmap, maxTileWidth: Int, maxTileHeight: Int) {

    init {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "ARGB_8888 required" }
    }

    /** Holds one tile of RGBA bytes, row-major, no padding. */
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(maxTileWidth * maxTileHeight * 4).order(ByteOrder.nativeOrder())

    private val canvas by lazy { Canvas(bitmap) }
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** Loads the rectangle into [buffer]. */
    fun read(x: Int, y: Int, w: Int, h: Int) {
        val tile = Bitmap.createBitmap(bitmap, x, y, w, h)
        try {
            buffer.clear()
            tile.copyPixelsToBuffer(buffer)
            buffer.flip()
        } finally {
            if (tile !== bitmap) tile.recycle()
        }
    }

    /** Writes [buffer] (w×h RGBA) back into the rectangle. Requires a mutable bitmap. */
    fun write(x: Int, y: Int, w: Int, h: Int) {
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), bitmap.colorSpace!!)
        try {
            buffer.rewind()
            tile.copyPixelsFromBuffer(buffer)
            canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), copyPaint)
        } finally {
            tile.recycle()
        }
    }

    /** Copies the first [count] pixels of [buffer] into [dst] as ARGB ints. */
    fun toArgb(dst: IntArray, count: Int) {
        buffer.rewind()
        buffer.asIntBuffer().get(dst, 0, count)
        for (i in 0 until count) dst[i] = swapRb(dst[i])
    }

    /** Puts [count] ARGB ints from [src] into [buffer] as RGBA bytes. */
    fun fromArgb(src: IntArray, count: Int) {
        for (i in 0 until count) src[i] = swapRb(src[i])
        buffer.rewind()
        buffer.asIntBuffer().put(src, 0, count)
        for (i in 0 until count) src[i] = swapRb(src[i])
    }

    private companion object {
        init {
            check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) { "little-endian device expected" }
        }

        /** RGBA bytes read as a little-endian int are 0xAABBGGRR; swapping R and B gives ARGB (and back). */
        fun swapRb(p: Int) = (p and 0xff00ff00.toInt()) or (p and 0xff shl 16) or (p shr 16 and 0xff)
    }
}
