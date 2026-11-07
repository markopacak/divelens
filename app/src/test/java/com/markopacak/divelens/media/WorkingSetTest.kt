package com.markopacak.divelens.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.markopacak.divelens.licensing.Limits

class WorkingSetTest {
    private val FREE = Limits.FREE_WORKING_SET
    private val FULL = Limits.FULL_WORKING_SET
    private fun photo(n: Int) = MediaItem.Photo("content://p/$n", "IMG_$n.jpg", "image/jpeg", 4000, 3000)
    private fun video(n: Int) = MediaItem.Video(
        "content://v/$n", "VID_$n.mp4", "video/mp4", 1920, 1080, durationUs = 10_000_000, frameRate = 30f, codec = "video/avc",
    )

    @Test fun theLimitsAreTenFreeAndTwentyFull() {
        assertEquals(10, FREE)
        assertEquals(20, FULL)
    }

    @Test fun addKeepsPickerOrder() {
        val set = WorkingSet().add(listOf(photo(3), photo(1), photo(2)), FULL)
        assertEquals(listOf("content://p/3", "content://p/1", "content://p/2"), set.items.map { it.item.source })
        assertTrue(set.items.all { it.status == ItemStatus.Pending })
    }

    @Test fun photosAndVideosShareTheSetInPickerOrder() {
        val set = WorkingSet().add(listOf(photo(1), video(1), photo(2)), FULL)
        assertEquals(listOf("content://p/1", "content://v/1", "content://p/2"), set.items.map { it.item.source })
        assertEquals(FULL - 3, set.room(FULL))
    }

    @Test fun addSkipsItemsAlreadyInTheSet() {
        val set = WorkingSet().add(listOf(photo(1)), FULL).add(listOf(photo(1), photo(2)), FULL)
        assertEquals(listOf("content://p/1", "content://p/2"), set.items.map { it.item.source })
    }

    @Test fun addingNothingLeavesSetUnchanged() {
        val set = WorkingSet().add(listOf(photo(1)), FULL)
        assertEquals(set, set.add(emptyList(), FULL))
    }

    @Test fun removeDropsOnlyThatItem() {
        val set = WorkingSet().add(listOf(photo(1), photo(2)), FULL).remove("content://p/1")
        assertEquals(listOf("content://p/2"), set.items.map { it.item.source })
    }

    @Test fun clearEmptiesTheSet() {
        assertTrue(WorkingSet().add(listOf(photo(1), photo(2)), FULL).clear().items.isEmpty())
    }

    @Test fun withStatusUpdatesOneItem() {
        val set = WorkingSet().add(listOf(photo(1), photo(2)), FULL).withStatus("content://p/2", ItemStatus.Exported)
        assertEquals(listOf(ItemStatus.Pending, ItemStatus.Exported), set.items.map { it.status })
    }

    @Test fun addStopsAtTheLimitAndCountsTheRest() {
        // Spec: 15 photos plus 8 selected, the first 5 are added and 3 are left out.
        val set15 = WorkingSet().add((1..15).map(::photo), FULL)
        val added = set15.addWithinLimit((16..23).map(::photo), FULL)
        assertEquals((1..20).map { "content://p/$it" }, added.set.items.map { it.item.source })
        assertEquals(3, added.leftOut)
        assertTrue(added.set.isFull(FULL))
    }

    @Test fun videosCountTowardsTheLimit() {
        // Spec: 18 photos plus 3 videos, the first 2 videos are added and 1 item is left out.
        val added = WorkingSet().add((1..18).map(::photo), FULL).addWithinLimit((1..3).map(::video), FULL)
        assertEquals(listOf("content://v/1", "content://v/2"), added.set.items.drop(18).map { it.item.source })
        assertEquals(1, added.leftOut)
        assertTrue(added.set.isFull(FULL))
    }

    @Test fun duplicatesDoNotCountAsLeftOut() {
        val set = WorkingSet().add((1..19).map(::photo), FULL)
        val added = set.addWithinLimit(listOf(photo(1), photo(2), photo(20)), FULL)
        assertEquals(20, added.set.items.size)
        assertEquals(0, added.leftOut)
    }

    @Test fun fullSetAddsNothing() {
        val full = WorkingSet().add((1..20).map(::photo), FULL)
        val added = full.addWithinLimit(listOf(photo(21), video(1)), FULL)
        assertEquals(full, added.set)
        assertEquals(2, added.leftOut)
        assertEquals(0, full.room(FULL))
    }

    @Test fun removingFromAFullSetMakesRoom() {
        val full = WorkingSet().add((1..20).map(::photo), FULL)
        assertEquals(1, full.remove("content://p/7").room(FULL))
    }

    @Test fun freeVersionAddsUpToTen() {
        // Spec: a free user with 7 photos selects 5 more; the first 3 are added and 2 are left out.
        val added = WorkingSet().add((1..7).map(::photo), FREE).addWithinLimit((8..12).map(::photo), FREE)
        assertEquals((1..10).map { "content://p/$it" }, added.set.items.map { it.item.source })
        assertEquals(2, added.leftOut)
        assertTrue(added.set.isFull(FREE))
    }

    @Test fun aLoweredLimitKeepsEveryItemAndAddsNothing() {
        // Spec: 20 items selected when the app returns to the free version.
        val twenty = WorkingSet().add((1..20).map(::photo), FULL)
        val added = twenty.addWithinLimit(listOf(photo(21)), FREE)
        assertEquals(20, added.set.items.size)
        assertEquals(1, added.leftOut)
        assertTrue(twenty.isFull(FREE))
        assertEquals(0, twenty.room(FREE))
        assertTrue(twenty.remove("content://p/1").isFull(FREE)) // still 19, above 10
    }

    @Test fun videoDisplaySizeFollowsRotation() {
        assertEquals(1080 to 1920, video(1).copy(rotation = 90).let { it.displayWidth to it.displayHeight })
        assertEquals(1920 to 1080, video(1).copy(rotation = 180).let { it.displayWidth to it.displayHeight })
    }
}
