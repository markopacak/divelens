package com.markopacak.divelens.media

/** Status of one item in the working set. */
sealed interface ItemStatus {
    data object Pending : ItemStatus
    data object Exporting : ItemStatus
    data object Exported : ItemStatus
    data class Refused(val reason: RefusalReason) : ItemStatus
    data class Failed(val reason: FailureReason) : ItemStatus
}

enum class FailureReason { InsufficientStorage, UnreadableSource, OutOfMemory, WriteFailed }

/** Why the export gate refused an item (add-free-limits-and-purchase D5). */
enum class RefusalReason { DailyLimit }

data class WorkingItem(val item: MediaItem, val status: ItemStatus = ItemStatus.Pending)

/**
 * The photos and videos the user has selected, in selection order; a video counts as one item. The limit depends on the
 * version (add-free-limits-and-purchase D6), so the operations take it; nothing ever trims a set above a lowered limit.
 * Immutable; every change returns a new set.
 */
data class WorkingSet(val items: List<WorkingItem> = emptyList()) {

    /** How many more items fit under [limit]. */
    fun room(limit: Int): Int = (limit - items.size).coerceAtLeast(0)

    /** True when no item can be added: the set holds [limit] items or more. */
    fun isFull(limit: Int): Boolean = items.size >= limit

    /** Adds [newItems] in order, skipping any whose source is already in the set, up to [limit]. */
    fun add(newItems: List<MediaItem>, limit: Int): WorkingSet = addWithinLimit(newItems, limit).set

    /** Like [add], and also reports how many new items were left out because the set is full. */
    fun addWithinLimit(newItems: List<MediaItem>, limit: Int): Added {
        val known = items.mapTo(HashSet()) { it.item.source }
        val fresh = newItems.filter { known.add(it.source) }
        val added = fresh.take(room(limit)).map { WorkingItem(it) }
        return Added(copy(items = items + added), leftOut = fresh.size - added.size)
    }

    data class Added(val set: WorkingSet, val leftOut: Int)

    fun remove(source: String): WorkingSet = copy(items = items.filterNot { it.item.source == source })

    fun clear(): WorkingSet = WorkingSet()

    fun withStatus(source: String, status: ItemStatus): WorkingSet =
        copy(items = items.map { if (it.item.source == source) it.copy(status = status) else it })
}
