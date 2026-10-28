package com.markopacak.divelens.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The brand watermark files read into shapes, and anything outside the supported subset is refused (task 4.2). */
class MarkSvgTest {
    private val brand = File(System.getProperty("user.dir")!!).parentFile.resolve("docs/brand/watermark")

    @Test fun lightMarkIsAShadowUnderAWhiteMark() {
        val mark = brand.resolve("watermark_light.svg").inputStream().use(MarkSvg::parse)
        checkBox(mark)
        assertEquals(6, mark.shapes.size)
        mark.shapes.take(3).forEach { assertShape(it, 0x59000000, 1.2f, 1.6f) } // black at 0.35
        mark.shapes.drop(3).forEach { assertShape(it, 0xFFFFFFFF.toInt(), 0f, 0f) }
        assertEquals(listOf(true, false, false, true, false, false), mark.shapes.map { it.evenOdd }) // ring, dot, wordmark
    }

    @Test fun darkMarkIsAHaloUnderANavyMark() {
        val mark = brand.resolve("watermark_dark.svg").inputStream().use(MarkSvg::parse)
        checkBox(mark)
        assertEquals(6, mark.shapes.size)
        mark.shapes.take(3).forEach { assertShape(it, 0x73FFFFFF, 1.2f, 1.6f) } // white at 0.45
        mark.shapes.drop(3).forEach { assertShape(it, 0xFF0B2E42.toInt(), 0f, 0f) }
    }

    @Test fun unsupportedContentIsRefusedByName() {
        val cases = mapOf(
            """<filter id="f"/>""" to "<filter>",
            """<text>dive</text>""" to "<text>",
            """<linearGradient id="g"/>""" to "<linearGradient>",
            """<g transform="scale(2)"><path d="M0,0h1v1z"/></g>""" to "scale(2)",
            """<path d="M0,0h1v1z" fill="url(#g)"/>""" to "url(#g)",
            """<path d="M0,0h1v1z" stroke="#000"/>""" to "stroke",
        )
        for ((content, named) in cases) {
            val e = assertThrows(MarkSvgException::class.java) { parse(content) }
            assertTrue("${e.message} should name $named", e.message!!.contains(named.trim('<', '>')))
        }
    }

    @Test fun groupsNestTranslateAndInheritFill() {
        val mark = parse("""<g transform="translate(1,2)" fill="#f00" fill-opacity="0.5"><g transform="translate(3 4)"><path d="M0,0h1v1z"/></g></g>""")
        assertEquals(MarkShape("M0,0h1v1z", 0x80FF0000.toInt(), false, 4f, 6f), mark.shapes.single())
    }

    private fun parse(content: String) =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">$content</svg>""".byteInputStream().use(MarkSvg::parse)

    private fun checkBox(mark: MarkSvg) {
        assertEquals(listOf(-3f, -3f, 232.5f, 63f), listOf(mark.viewX, mark.viewY, mark.viewWidth, mark.viewHeight))
    }

    private fun assertShape(shape: MarkShape, argb: Int, dx: Float, dy: Float) {
        assertEquals(Integer.toHexString(argb), Integer.toHexString(shape.argb))
        assertEquals(dx, shape.dx, 1e-6f)
        assertEquals(dy, shape.dy, 1e-6f)
    }
}
