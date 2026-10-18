package com.markopacak.divelens.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSetTest {
    private fun photo(n: Int) = MediaItem.Photo("content://p/$n", "IMG_$n.jpg", "image/jpeg", 4000, 3000)

    @Test fun addKeepsPickerOrder() {
        val set = WorkingSet().add(listOf(photo(3), photo(1), photo(2)))
        assertEquals(listOf("content://p/3", "content://p/1", "content://p/2"), set.items.map { it.item.source })
        assertTrue(set.items.all { it.status == ItemStatus.Pending })
    }

    @Test fun addSkipsItemsAlreadyInTheSet() {
        val set = WorkingSet().add(listOf(photo(1))).add(listOf(photo(1), photo(2)))
        assertEquals(listOf("content://p/1", "content://p/2"), set.items.map { it.item.source })
    }

    @Test fun addingNothingLeavesSetUnchanged() {
        val set = WorkingSet().add(listOf(photo(1)))
        assertEquals(set, set.add(emptyList()))
    }

    @Test fun removeDropsOnlyThatItem() {
        val set = WorkingSet().add(listOf(photo(1), photo(2))).remove("content://p/1")
        assertEquals(listOf("content://p/2"), set.items.map { it.item.source })
    }

    @Test fun clearEmptiesTheSet() {
        assertTrue(WorkingSet().add(listOf(photo(1), photo(2))).clear().items.isEmpty())
    }

    @Test fun withStatusUpdatesOneItem() {
        val set = WorkingSet().add(listOf(photo(1), photo(2))).withStatus("content://p/2", ItemStatus.Exported)
        assertEquals(listOf(ItemStatus.Pending, ItemStatus.Exported), set.items.map { it.status })
    }

    @Test fun addStopsAtTheLimitAndCountsTheRest() {
        val full45 = WorkingSet().add((1..45).map(::photo))
        val added = full45.addWithinLimit((46..53).map(::photo))
        assertEquals((1..50).map { "content://p/$it" }, added.set.items.map { it.item.source })
        assertEquals(3, added.leftOut)
        assertTrue(added.set.isFull)
    }

    @Test fun duplicatesDoNotCountAsLeftOut() {
        val set = WorkingSet().add((1..49).map(::photo))
        val added = set.addWithinLimit(listOf(photo(1), photo(2), photo(50)))
        assertEquals(50, added.set.items.size)
        assertEquals(0, added.leftOut)
    }

    @Test fun fullSetAddsNothing() {
        val full = WorkingSet().add((1..50).map(::photo))
        val added = full.addWithinLimit(listOf(photo(51), photo(52)))
        assertEquals(full, added.set)
        assertEquals(2, added.leftOut)
        assertEquals(0, full.room)
    }

    @Test fun removingFromAFullSetMakesRoom() {
        val full = WorkingSet().add((1..50).map(::photo))
        assertEquals(1, full.remove("content://p/7").room)
    }
}
