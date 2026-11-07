package com.markopacak.divelens.debug

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.AppGraph

/** Release builds have no simulated store and no developer panel (add-free-limits-and-purchase D10). */
@UnstableApi
object DebugTools {
    @Composable
    fun Overlay(graph: AppGraph) = Unit

    val developer: (@Composable (AppGraph) -> Unit)? = null
}
