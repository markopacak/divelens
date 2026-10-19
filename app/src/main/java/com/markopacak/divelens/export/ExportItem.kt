package com.markopacak.divelens.export

import android.system.ErrnoException
import android.system.OsConstants
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlin.coroutines.cancellation.CancellationException

/** True when this failure, or one of its causes, is the storage running full. */
internal fun Throwable.isOutOfSpace(): Boolean =
    generateSequence(this) { it.cause }.any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true ||
            it.message?.contains("No space left", ignoreCase = true) == true
    }

/** Thrown by a [PhotoWriter] or [VideoWriter] with the user-facing reason for the failure. */
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

/** Exports one item: asks the gate, writes it with the writer for its kind, then reports success to the gate. */
class ExportItemUseCase(
    private val gate: ExportGate,
    private val writer: PhotoWriter,
    private val videoWriter: VideoWriter? = null,
) {

    suspend operator fun invoke(item: MediaItem): ItemResult {
        val decision = gate.check(item)
        if (decision is GateDecision.Refused) return ItemResult.Refused(decision.message)
        val uri = try {
            when (item) {
                is MediaItem.Photo -> writer.write(item)
                is MediaItem.Video -> videoWriter?.write(item) ?: return ItemResult.Failed(FailureReason.UnreadableSource)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            return ItemResult.Failed(e.reason)
        }
        gate.onExported(item)
        return ItemResult.Exported(uri)
    }
}
