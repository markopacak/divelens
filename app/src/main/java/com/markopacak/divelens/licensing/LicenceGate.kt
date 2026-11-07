package com.markopacak.divelens.licensing

import com.markopacak.divelens.export.ExportGate
import com.markopacak.divelens.export.GateDecision
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.media.RefusalReason

/**
 * The export check (add-free-limits-and-purchase D5): the full version allows every export, the free version allows
 * them while today's count is below the daily limit. Only successful free exports count; batches run one item at a
 * time, so the check and the count can't race.
 */
class LicenceGate(private val licence: Licence, private val counter: DailyCounter) : ExportGate {
    override suspend fun check(item: MediaItem): GateDecision = when {
        licence.state.value.full -> GateDecision.Allowed
        counter.remaining() > 0 -> GateDecision.Allowed
        else -> GateDecision.Refused(RefusalReason.DailyLimit)
    }

    override suspend fun onExported(item: MediaItem) {
        if (!licence.state.value.full) counter.record()
    }
}
