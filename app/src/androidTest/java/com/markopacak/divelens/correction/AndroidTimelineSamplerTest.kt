package com.markopacak.divelens.correction

import com.markopacak.divelens.TestMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidTimelineSamplerTest {
    @Before @After fun cleanUp() = TestMedia.deleteAll()

    @Test fun samplesSyncFramesAndSeesTheColourDrift() = runBlocking {
        // The fixture has a sync frame every second (0 s, 1 s) and drifts from green-blue to deep blue.
        val samples = AndroidTimelineSampler(TestMedia.resolver).sample(TestMedia.driftVideo().toString(), listOf(0L, 400_000L, 1_000_000L, 2_000_000L))
        assertEquals(listOf(0L, 1_000_000L), samples.map { it.first }.map { (it + 50_000) / 1_000_000 * 1_000_000 })
        val (first, second) = samples.map { it.second }
        // Deeper blue at 1 s: more red is rebuilt (larger alpha in the red row).
        assertTrue("alpha ${first[0, 1]} -> ${second[0, 1]}", second[0, 1] > first[0, 1])
    }
}
