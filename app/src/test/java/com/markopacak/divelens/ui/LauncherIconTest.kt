package com.markopacak.divelens.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

/**
 * The launcher layers are the brand symbol, scaled so it stays inside the adaptive icon's guaranteed safe zone
 * (add-branding-and-watermark D1, task 2.1).
 */
class LauncherIconTest {
    private val module = File(System.getProperty("user.dir")!!)
    private val drawables = module.resolve("src/main/res/drawable")
    private val brand = module.parentFile.resolve("docs/brand/icon")

    @Test fun foregroundAndMonochromeStayInsideTheSafeZone() {
        for (layer in listOf("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml")) {
            val vector = parse(drawables.resolve(layer))
            val group = vector.getElementsByTagName("group").item(0) as Element
            val scale = group.getAttribute("android:scaleX").toDouble()
            assertEquals(layer, scale, group.getAttribute("android:scaleY").toDouble(), 0.0)
            val pivotX = group.getAttribute("android:pivotX").toDouble()
            val pivotY = group.getAttribute("android:pivotY").toDouble()
            val circles = paths(vector).flatMap(::circles)
            assertEquals(layer, 3, circles.size) // outer ring, inner ring, dot
            for ((cx, cy, r) in circles) {
                val x = pivotX + (cx - pivotX) * scale
                val y = pivotY + (cy - pivotY) * scale
                val far = hypot(x - SAFE_CENTRE, y - SAFE_CENTRE) + r * scale
                assertTrue("$layer: circle at ($cx, $cy) r $r reaches $far", far <= SAFE_RADIUS)
            }
        }
    }

    @Test fun layersUseTheBrandGeometryUnchanged() {
        val svgPaths = paths(parse(brand.resolve("ic_launcher_foreground.svg")), "d")
        assertEquals(svgPaths, paths(parse(drawables.resolve("ic_launcher_foreground.xml"))))
        assertEquals(svgPaths, paths(parse(drawables.resolve("ic_launcher_monochrome.xml"))))
    }

    @Test fun withoutTheScaleTheDotWouldLeaveTheSafeZone() {
        // Guards the reason for the scale: the brand geometry reaches 34.3 from the centre.
        val dot = circles(paths(parse(brand.resolve("ic_launcher_foreground.svg")), "d")[1]).single()
        assertTrue(hypot(dot.first - SAFE_CENTRE, dot.second - SAFE_CENTRE) + dot.third > SAFE_RADIUS)
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun paths(root: Element, attribute: String = "android:pathData"): List<String> {
        val nodes = root.getElementsByTagName("path")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute(attribute) }
    }

    /** The circles in path data made of `Mx,y a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0 Z` pieces: (centre x, centre y, radius). */
    private fun circles(data: String): List<Triple<Double, Double, Double>> =
        Regex("""M([\d.]+),([\d.]+) a([\d.]+),[\d.]+ 0 1,0 [\d.]+,0 a[\d.]+,[\d.]+ 0 1,0 -[\d.]+,0 Z""")
            .findAll(data)
            .map { m ->
                val (x, y, r) = m.destructured
                Triple(x.toDouble() + r.toDouble(), y.toDouble(), r.toDouble())
            }
            .toList()

    private companion object {
        const val SAFE_CENTRE = 54.0
        const val SAFE_RADIUS = 33.0
    }
}
