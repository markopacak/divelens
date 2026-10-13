package com.markopacak.divelens.export

import android.graphics.Bitmap
import com.markopacak.divelens.media.MediaItem

/** Turns decoded pixels into the pixels to export. The correction pipeline plugs in here. */
fun interface PhotoProcessor {
    /** May return [input] itself. The caller recycles both bitmaps afterwards. */
    suspend fun process(input: Bitmap): Bitmap
}

/** Leaves pixels unchanged. Replaced by the correction pipeline in add-auto-correction. */
object IdentityPhotoProcessor : PhotoProcessor {
    override suspend fun process(input: Bitmap): Bitmap = input
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
