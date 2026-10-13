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
}
