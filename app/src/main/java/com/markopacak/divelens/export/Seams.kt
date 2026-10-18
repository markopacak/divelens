package com.markopacak.divelens.export

import android.graphics.Bitmap
import com.markopacak.divelens.media.MediaItem

/** Turns decoded pixels into the pixels to export. The correction pipeline plugs in here. */
fun interface PhotoProcessor {
    /**
     * [source] is the photo's URI, so the processor can use the correction the preview showed. [input] is its
     * full-resolution decode in stored orientation, a mutable `ARGB_8888` bitmap. May return [input] itself,
     * changed in place. The caller recycles both bitmaps afterwards.
     */
    suspend fun process(source: String, input: Bitmap): Bitmap
}

/** Leaves pixels unchanged. Used by tests that check export without correction. */
object IdentityPhotoProcessor : PhotoProcessor {
    override suspend fun process(source: String, input: Bitmap): Bitmap = input
}

sealed interface GateDecision {
    data object Allowed : GateDecision
    data class Refused(val message: String) : GateDecision
}

/** Decides whether an export may be written. The trial counter and unlock plug in here. */
interface ExportGate {
    suspend fun check(item: MediaItem): GateDecision

    /** Called only after [item] was written successfully. */
    suspend fun onExported(item: MediaItem)
}

/** Allows every export. Replaced by the licensing gate in add-trial-and-purchase. */
object AlwaysAllowExportGate : ExportGate {
    override suspend fun check(item: MediaItem): GateDecision = GateDecision.Allowed
    override suspend fun onExported(item: MediaItem) = Unit
}
