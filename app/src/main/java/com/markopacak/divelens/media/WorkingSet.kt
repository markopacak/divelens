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

/** The photos the user has selected, in selection order. Immutable; every change returns a new set. */
data class WorkingSet(val items: List<WorkingItem> = emptyList()) {

    /** Adds [newItems] in order, skipping any whose source is already in the set. */
    fun add(newItems: List<MediaItem>): WorkingSet {
        val known = items.mapTo(HashSet()) { it.item.source }
        val added = newItems.filter { known.add(it.source) }.map { WorkingItem(it) }
        return copy(items = items + added)
    }

    fun remove(source: String): WorkingSet = copy(items = items.filterNot { it.item.source == source })

    fun clear(): WorkingSet = WorkingSet()

    fun withStatus(source: String, status: ItemStatus): WorkingSet =
        copy(items = items.map { if (it.item.source == source) it.copy(status = status) else it })
}
