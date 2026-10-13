package com.markopacak.divelens.export

import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlin.coroutines.cancellation.CancellationException

/** Thrown by a [PhotoWriter] with the user-facing reason for the failure. */
class ExportException(val reason: FailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

/** Writes one photo to the gallery and returns the new item's URI string. */
fun interface PhotoWriter {
    suspend fun write(photo: MediaItem.Photo): String
}

sealed interface ItemResult {
    data class Exported(val uri: String) : ItemResult
    data class Refused(val message: String) : ItemResult
    data class Failed(val reason: FailureReason) : ItemResult
}

/** Exports one item: asks the gate, writes, then reports success to the gate. */
class ExportItemUseCase(private val gate: ExportGate, private val writer: PhotoWriter) {

    suspend operator fun invoke(item: MediaItem): ItemResult {
        val decision = gate.check(item)
        if (decision is GateDecision.Refused) return ItemResult.Refused(decision.message)
        val photo = item as? MediaItem.Photo ?: return ItemResult.Failed(FailureReason.UnreadableSource)
        val uri = try {
            writer.write(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            return ItemResult.Failed(e.reason)
        }
        gate.onExported(item)
        return ItemResult.Exported(uri)
    }
}
