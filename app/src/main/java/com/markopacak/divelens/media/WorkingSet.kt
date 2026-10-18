package com.markopacak.divelens.media

/** Status of one item in the working set. */
sealed interface ItemStatus {
    data object Pending : ItemStatus
    data object Exporting : ItemStatus
    data object Exported : ItemStatus
    data class Refused(val message: String) : ItemStatus
    data class Failed(val reason: FailureReason) : ItemStatus
}

enum class FailureReason { InsufficientStorage, UnreadableSource, OutOfMemory, WriteFailed }

data class WorkingItem(val item: MediaItem, val status: ItemStatus = ItemStatus.Pending)

/** The photos the user has selected, in selection order, at most [LIMIT]. Immutable; every change returns a new set. */
data class WorkingSet(val items: List<WorkingItem> = emptyList()) {

    /** How many more photos fit. */
    val room: Int get() = (LIMIT - items.size).coerceAtLeast(0)

    val isFull: Boolean get() = room == 0

    /** Adds [newItems] in order, skipping any whose source is already in the set, up to [LIMIT]. */
    fun add(newItems: List<MediaItem>): WorkingSet = addWithinLimit(newItems).set

    /** Like [add], and also reports how many new photos were left out because the set is full. */
    fun addWithinLimit(newItems: List<MediaItem>): Added {
        val known = items.mapTo(HashSet()) { it.item.source }
        val fresh = newItems.filter { known.add(it.source) }
        val added = fresh.take(room).map { WorkingItem(it) }
        return Added(copy(items = items + added), leftOut = fresh.size - added.size)
    }

    data class Added(val set: WorkingSet, val leftOut: Int)

    fun remove(source: String): WorkingSet = copy(items = items.filterNot { it.item.source == source })

    fun clear(): WorkingSet = WorkingSet()

    fun withStatus(source: String, status: ItemStatus): WorkingSet =
        copy(items = items.map { if (it.item.source == source) it.copy(status = status) else it })

    companion object {
        /** Bounds preview memory and export time; see the import-export spec. */
        const val LIMIT = 50
    }
}
