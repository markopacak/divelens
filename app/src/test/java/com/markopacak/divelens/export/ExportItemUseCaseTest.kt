package com.markopacak.divelens.export

import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportItemUseCaseTest {
    private val photo = MediaItem.Photo("content://p/1", "IMG_1.jpg", "image/jpeg", 10, 10)
    private val calls = mutableListOf<String>()

    private fun gate(decision: GateDecision) = object : ExportGate {
        override suspend fun check(item: MediaItem) = decision.also { calls += "check" }
        override suspend fun onExported(item: MediaItem) { calls += "onExported" }
    }

    private val okWriter = PhotoWriter { _, _ -> calls += "write"; "content://out/1" }

    @Test fun checksGateBeforeWritingAndReportsSuccessAfter() = runTest {
        val result = ExportItemUseCase(gate(GateDecision.Allowed), okWriter)(photo)
        assertEquals(ItemResult.Exported("content://out/1"), result)
        assertEquals(listOf("check", "write", "onExported"), calls)
    }

    @Test fun refusedItemIsNotWritten() = runTest {
        val result = ExportItemUseCase(gate(GateDecision.Refused("trial over")), okWriter)(photo)
        assertEquals(ItemResult.Refused("trial over"), result)
        assertEquals(listOf("check"), calls)
    }

    @Test fun failedWriteIsNotReportedToGate() = runTest {
        val writer = PhotoWriter { _, _ -> calls += "write"; throw ExportException(FailureReason.InsufficientStorage) }
        val result = ExportItemUseCase(gate(GateDecision.Allowed), writer)(photo)
        assertEquals(ItemResult.Failed(FailureReason.InsufficientStorage), result)
        assertEquals(listOf("check", "write"), calls)
    }

    private val video = MediaItem.Video("content://v/1", "VID_1.mp4", "video/mp4", 1920, 1080, durationUs = 1_000_000, frameRate = 30f, codec = "video/avc")

    @Test fun videosGoToTheVideoWriter() = runTest {
        val videoWriter = VideoWriter { it, _ -> calls += "writeVideo ${it.source}"; "content://out/v1" }
        val result = ExportItemUseCase(gate(GateDecision.Allowed), okWriter, videoWriter)(video)
        assertEquals(ItemResult.Exported("content://out/v1"), result)
        assertEquals(listOf("check", "writeVideo content://v/1", "onExported"), calls)
    }

    @Test fun aFailedVideoIsNotReportedToGate() = runTest {
        val videoWriter = VideoWriter { _, _ -> calls += "writeVideo"; throw ExportException(FailureReason.InsufficientStorage) }
        val result = ExportItemUseCase(gate(GateDecision.Allowed), okWriter, videoWriter)(video)
        assertEquals(ItemResult.Failed(FailureReason.InsufficientStorage), result)
        assertEquals(listOf("check", "writeVideo"), calls)
    }

    @Test fun videosFailWithoutAVideoWriter() = runTest {
        assertEquals(ItemResult.Failed(FailureReason.UnreadableSource), ExportItemUseCase(gate(GateDecision.Allowed), okWriter)(video))
    }
}
