package com.markopacak.divelens.watermark

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/** One filled path of the mark: SVG path data, its colour with opacity, its fill rule and the offset of its groups. */
data class MarkShape(val pathData: String, val argb: Int, val evenOdd: Boolean, val dx: Float, val dy: Float)

/**
 * A watermark SVG from docs/brand/ (add-branding-and-watermark D4): the viewBox and the filled paths in drawing order.
 * Only the subset the brand files use is read: `<svg>` with a viewBox, `<g>` with `translate(...)`, `fill` and
 * `fill-opacity`, and `<path>` with `d`, `fill`, `fill-opacity` and `fill-rule`. Anything else is an error, so a file
 * re-exported with filters, gradients or text fails instead of drawing wrong.
 */
data class MarkSvg(val viewX: Float, val viewY: Float, val viewWidth: Float, val viewHeight: Float, val shapes: List<MarkShape>) {

    /** Height over width of the whole mark, viewBox padding included. */
    val aspect: Float get() = viewHeight / viewWidth

    companion object {
        fun parse(input: InputStream): MarkSvg {
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(input).documentElement
            if (root.tagName != "svg") throw MarkSvgException("root is <${root.tagName}>, not <svg>")
            checkAttributes(root, setOf("xmlns", "viewBox", "width", "height"))
            val box = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).map { it.toFloat() }
            if (box.size != 4 || box[2] <= 0f || box[3] <= 0f) throw MarkSvgException("bad viewBox '${root.getAttribute("viewBox")}'")
            val shapes = mutableListOf<MarkShape>()
            readChildren(root, Style(null, null, 0f, 0f), shapes)
            if (shapes.isEmpty()) throw MarkSvgException("no paths")
            return MarkSvg(box[0], box[1], box[2], box[3], shapes)
        }

        private data class Style(val fill: Int?, val opacity: Float?, val dx: Float, val dy: Float)

        private fun readChildren(parent: Element, style: Style, out: MutableList<MarkShape>) {
            val nodes = parent.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                when (node) {
                    is Element -> readElement(node, style, out)
                    is org.w3c.dom.Text -> if (node.data.isNotBlank()) throw MarkSvgException("text content '${node.data.trim()}'")
                    else -> Unit // comments
                }
            }
        }

        private fun readElement(e: Element, parent: Style, out: MutableList<MarkShape>) {
            when (e.tagName) {
                "g" -> {
                    checkAttributes(e, setOf("transform", "fill", "fill-opacity"))
                    val (tx, ty) = translate(e.getAttribute("transform"))
                    readChildren(e, own(e, parent).copy(dx = parent.dx + tx, dy = parent.dy + ty), out)
                }
                "path" -> {
                    checkAttributes(e, setOf("d", "fill", "fill-opacity", "fill-rule"))
                    if (e.hasChildNodes() && e.textContent.isNotBlank()) throw MarkSvgException("<path> with content")
                    val style = own(e, parent)
                    val d = e.getAttribute("d").trim()
                    if (d.isEmpty()) throw MarkSvgException("<path> without d")
                    val rule = e.getAttribute("fill-rule")
                    if (rule !in setOf("", "nonzero", "evenodd")) throw MarkSvgException("fill-rule '$rule'")
                    val rgb = style.fill ?: 0x000000 // SVG's default fill is black
                    val alpha = ((style.opacity ?: 1f) * 255).roundToInt()
                    out += MarkShape(d, (alpha shl 24) or rgb, rule == "evenodd", style.dx, style.dy)
                }
                else -> throw MarkSvgException("unsupported element <${e.tagName}>")
            }
        }

        /** The element's own fill and fill-opacity over the inherited ones (both are inherited properties in SVG). */
        private fun own(e: Element, parent: Style): Style {
            val fill = if (e.hasAttribute("fill")) colour(e.getAttribute("fill")) else parent.fill
            val opacity = if (e.hasAttribute("fill-opacity")) e.getAttribute("fill-opacity").toFloatOrNull()?.takeIf { it in 0f..1f }
                ?: throw MarkSvgException("fill-opacity '${e.getAttribute("fill-opacity")}'") else parent.opacity
            return parent.copy(fill = fill, opacity = opacity)
        }

        private fun translate(transform: String): Pair<Float, Float> {
            if (transform.isBlank()) return 0f to 0f
            val m = Regex("""translate\(\s*(-?[\d.]+)(?:[\s,]+(-?[\d.]+))?\s*\)""").matchEntire(transform.trim())
                ?: throw MarkSvgException("unsupported transform '$transform'")
            return m.groupValues[1].toFloat() to (m.groupValues[2].ifEmpty { "0" }).toFloat()
        }

        private fun colour(value: String): Int {
            val hex = value.trim().removePrefix("#")
            if (!value.trim().startsWith("#") || hex.any { it !in "0123456789abcdefABCDEF" }) throw MarkSvgException("unsupported fill '$value'")
            return when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("").toInt(16)
                6 -> hex.toInt(16)
                else -> throw MarkSvgException("unsupported fill '$value'")
            }
        }

        private fun checkAttributes(e: Element, allowed: Set<String>) {
            val attributes = e.attributes
            for (i in 0 until attributes.length) {
                val name = attributes.item(i).nodeName
                if (name !in allowed) throw MarkSvgException("unsupported attribute '$name' on <${e.tagName}>")
            }
        }
    }
}

class MarkSvgException(message: String) : IllegalArgumentException("watermark SVG: $message")
