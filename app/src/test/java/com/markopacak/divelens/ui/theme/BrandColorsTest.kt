package com.markopacak.divelens.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The brand schemes are readable and leave nothing of Material's baseline palette (add-branding-and-watermark D3, task 3.1). */
class BrandColorsTest {

    @Test fun textColoursMeetWcagAa() {
        for ((name, s) in schemes) {
            val pairs = listOf(
                "onPrimary/primary" to (s.onPrimary to s.primary),
                "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondary/secondary" to (s.onSecondary to s.secondary),
                "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onBackground/background" to (s.onBackground to s.background),
                "onSurface/surface" to (s.onSurface to s.surface),
                "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
                "onSurfaceVariant/background" to (s.onSurfaceVariant to s.background),
                "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
                "onError/error" to (s.onError to s.error),
                "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
                "primary/surface" to (s.primary to s.surface),
                "primary/background" to (s.primary to s.background),
                "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
                "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
                "inversePrimary/inverseSurface" to (s.inversePrimary to s.inverseSurface),
                "onPrimaryFixed/primaryFixed" to (s.onPrimaryFixed to s.primaryFixed),
                "onPrimaryFixedVariant/primaryFixed" to (s.onPrimaryFixedVariant to s.primaryFixed),
                "onPrimaryFixed/primaryFixedDim" to (s.onPrimaryFixed to s.primaryFixedDim),
                "onSecondaryFixed/secondaryFixed" to (s.onSecondaryFixed to s.secondaryFixed),
                "onSecondaryFixedVariant/secondaryFixed" to (s.onSecondaryFixedVariant to s.secondaryFixed),
                "onSecondaryFixed/secondaryFixedDim" to (s.onSecondaryFixed to s.secondaryFixedDim),
            )
            for ((pair, colours) in pairs) {
                val ratio = contrast(colours.first, colours.second)
                assertTrue("$name $pair is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
            val outline = contrast(s.outline, s.surface)
            assertTrue("$name outline/surface is ${"%.2f".format(outline)}:1", outline >= 3.0)
        }
    }

    @Test fun noBaselineMaterialColourIsLeft() {
        val errorRoles = setOf("Error", "OnError", "ErrorContainer", "OnErrorContainer")
        val baseline = listOf(lightColorScheme(), darkColorScheme())
            .flatMap { roles(it).filterKeys { role -> role !in errorRoles }.values }
            .map { Color(it.toULong()) }
            .filter { chroma(it) > 0.08f }
            .toSet()
        for ((name, s) in schemes) {
            for ((role, value) in roles(s)) {
                val colour = Color(value.toULong())
                assertTrue("$name $role is the baseline colour $colour", colour !in baseline)
            }
        }
    }

    @Test fun brandRolesComeFromThePalette() {
        assertEquals(LensBlue, LightColors.primary)
        assertEquals(CoralDeep, LightColors.secondary)
        assertEquals(Mist, LightColors.background)
        assertEquals(Surf, DarkColors.primary)
        assertEquals(CoralGlow, DarkColors.secondary)
        assertEquals(DeepOcean, DarkColors.background)
        assertEquals(Foam, DarkColors.onBackground)
    }

    private val schemes = listOf("light" to LightColors, "dark" to DarkColors)

    /** Every colour role of [scheme], by name; Compose compiles each `Color` property to a `long` getter. */
    private fun roles(scheme: ColorScheme): Map<String, Long> =
        ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get").substringBefore('-') to it.invoke(scheme) as Long }

    private fun chroma(c: Color) = max(c.red, max(c.green, c.blue)) - min(c.red, min(c.green, c.blue))

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
