package com.markopacak.divelens.watermark

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/**
 * Draws the brand watermark from its SVG (add-branding-and-watermark D4): each shape becomes a [Path], drawn with an
 * antialiased paint under the map from the viewBox to the target box, so the mark is sharp at any size. The whole mark
 * is drawn through one layer at [WatermarkPlacement.OPACITY], so the shadow doesn't show through the main shape.
 */
class WatermarkRenderer(private val load: (WatermarkVersion) -> MarkSvg) {

    constructor(assets: AssetManager) : this({ version -> assets.open(version.asset).use(MarkSvg::parse) })

    private class Shape(val path: Path, val paint: Paint)
    private class Mark(val svg: MarkSvg, val shapes: List<Shape>)

    private val marks = HashMap<WatermarkVersion, Mark>()

    /** The mark's height over its width (the same for both versions). */
    fun aspect(version: WatermarkVersion = WatermarkVersion.Light): Float = mark(version).svg.aspect

    /** Draws [version] filling [box] on [canvas], in the canvas's current coordinates. */
    fun draw(canvas: Canvas, version: WatermarkVersion, box: Box) {
        val mark = mark(version)
        val svg = mark.svg
        val toBox = Matrix().apply {
            setTranslate(-svg.viewX, -svg.viewY)
            postScale(box.width / svg.viewWidth, box.height / svg.viewHeight)
            postTranslate(box.left, box.top)
        }
        val layer = canvas.saveLayerAlpha(box.left, box.top, box.right, box.bottom, (WatermarkPlacement.OPACITY * 255).roundToInt())
        canvas.concat(toBox)
        mark.shapes.forEach { canvas.drawPath(it.path, it.paint) }
        canvas.restoreToCount(layer)
    }

    /** [version] drawn into a new transparent bitmap of [width] × [height] pixels, for the video overlay (D7). */
    fun bitmap(version: WatermarkVersion, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), version, Box(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    @Synchronized
    private fun mark(version: WatermarkVersion): Mark = marks.getOrPut(version) {
        val svg = load(version)
        Mark(svg, svg.shapes.map { shape ->
            val path = PathParser.createPathFromPathData(shape.pathData).apply {
                fillType = if (shape.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                if (shape.dx != 0f || shape.dy != 0f) offset(shape.dx, shape.dy)
            }
            Shape(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = shape.argb })
        })
    }
}
