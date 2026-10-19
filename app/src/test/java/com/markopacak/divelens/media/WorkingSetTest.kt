package com.markopacak.divelens.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSetTest {
    private fun photo(n: Int) = MediaItem.Photo("content://p/$n", "IMG_$n.jpg", "image/jpeg", 4000, 3000)
    private fun video(n: Int) = MediaItem.Video(
        "content://v/$n", "VID_$n.mp4", "video/mp4", 1920, 1080, durationUs = 10_000_000, frameRate = 30f, codec = "video/avc",
    )

    @Test fun limitIsTwentyItems() {
        assertEquals(20, WorkingSet.LIMIT)
    }

    @Test fun addKeepsPickerOrder() {
        val set = WorkingSet().add(listOf(photo(3), photo(1), photo(2)))
        assertEquals(listOf("content://p/3", "content://p/1", "content://p/2"), set.items.map { it.item.source })
        assertTrue(set.items.all { it.status == ItemStatus.Pending })
    }

    @Test fun photosAndVideosShareTheSetInPickerOrder() {
        val set = WorkingSet().add(listOf(photo(1), video(1), photo(2)))
        assertEquals(listOf("content://p/1", "content://v/1", "content://p/2"), set.items.map { it.item.source })
        assertEquals(WorkingSet.LIMIT - 3, set.room)
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
        // Spec: 15 photos plus 8 selected, the first 5 are added and 3 are left out.
        val set15 = WorkingSet().add((1..15).map(::photo))
        val added = set15.addWithinLimit((16..23).map(::photo))
        assertEquals((1..20).map { "content://p/$it" }, added.set.items.map { it.item.source })
        assertEquals(3, added.leftOut)
        assertTrue(added.set.isFull)
    }

    @Test fun videosCountTowardsTheLimit() {
        // Spec: 18 photos plus 3 videos, the first 2 videos are added and 1 item is left out.
        val added = WorkingSet().add((1..18).map(::photo)).addWithinLimit((1..3).map(::video))
        assertEquals(listOf("content://v/1", "content://v/2"), added.set.items.drop(18).map { it.item.source })
        assertEquals(1, added.leftOut)
        assertTrue(added.set.isFull)
    }

    @Test fun duplicatesDoNotCountAsLeftOut() {
        val set = WorkingSet().add((1..19).map(::photo))
        val added = set.addWithinLimit(listOf(photo(1), photo(2), photo(20)))
        assertEquals(20, added.set.items.size)
        assertEquals(0, added.leftOut)
    }

    @Test fun fullSetAddsNothing() {
        val full = WorkingSet().add((1..20).map(::photo))
        val added = full.addWithinLimit(listOf(photo(21), video(1)))
        assertEquals(full, added.set)
        assertEquals(2, added.leftOut)
        assertEquals(0, full.room)
    }

    @Test fun removingFromAFullSetMakesRoom() {
        val full = WorkingSet().add((1..20).map(::photo))
        assertEquals(1, full.remove("content://p/7").room)
    }

    @Test fun videoDisplaySizeFollowsRotation() {
        assertEquals(1080 to 1920, video(1).copy(rotation = 90).let { it.displayWidth to it.displayHeight })
        assertEquals(1920 to 1080, video(1).copy(rotation = 180).let { it.displayWidth to it.displayHeight })
    }
}
