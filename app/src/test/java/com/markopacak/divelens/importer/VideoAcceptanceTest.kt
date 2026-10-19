package com.markopacak.divelens.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class VideoAcceptanceTest {

    /** A device whose codecs handle up to [maxDecode] / [maxEncode] pixels per frame at up to 60 fps. */
    private class FakeCodecs(
        val decoders: Set<String> = setOf(VideoAcceptance.H264, VideoAcceptance.HEVC),
        val encoders: Set<String> = setOf(VideoAcceptance.H264, VideoAcceptance.HEVC),
        val maxDecode: Long = 3840L * 2160,
        val maxEncode: Long = 3840L * 2160,
        val tenBitDecode: Boolean = true,
    ) : CodecSupport {
        override fun hasDecoder(mime: String) = mime in decoders
        override fun canDecode(mime: String, width: Int, height: Int, frameRate: Float, tenBit: Boolean) =
            mime in decoders && width.toLong() * height <= maxDecode && frameRate <= 60.5f && (!tenBit || tenBitDecode)
        override fun hasEncoder(mime: String) = mime in encoders
        override fun canEncode(mime: String, width: Int, height: Int, frameRate: Float) =
            mime in encoders && width.toLong() * height <= maxEncode && frameRate <= 60.5f
    }

    private fun info(codec: String = VideoAcceptance.H264, width: Int = 2704, height: Int = 1520, fps: Float = 59.94f, hdr: Boolean = false) =
        VideoInfo(width, height, 0, 15_000_000, fps, 60_000_000, codec, hdr, null)

    private fun accepted(codec: String) = VideoAcceptance.Decision.Accepted(codec)
    private fun rejected(reason: RejectionReason) = VideoAcceptance.Decision.Rejected(reason)

    @Test fun goProClipIsAcceptedInItsOwnCodec() {
        assertEquals(accepted(VideoAcceptance.H264), VideoAcceptance.check(info(), FakeCodecs()))
        assertEquals(accepted(VideoAcceptance.HEVC), VideoAcceptance.check(info(VideoAcceptance.HEVC), FakeCodecs()))
    }

    @Test fun hevcWithoutAMatchingEncoderFallsBackToH264() {
        val codecs = FakeCodecs(encoders = setOf(VideoAcceptance.H264))
        assertEquals(accepted(VideoAcceptance.H264), VideoAcceptance.check(info(VideoAcceptance.HEVC), codecs))
    }

    @Test fun eightKOnAFourKDeviceIsTooLarge() {
        assertEquals(rejected(RejectionReason.TooLargeForDevice), VideoAcceptance.check(info(width = 7680, height = 4320), FakeCodecs()))
    }

    @Test fun decodableButNotEncodableIsTooLarge() {
        val codecs = FakeCodecs(maxDecode = 7680L * 4320)
        assertEquals(rejected(RejectionReason.TooLargeForDevice), VideoAcceptance.check(info(width = 7680, height = 4320), codecs))
    }

    @Test fun frameRateBeyondTheCodecsIsTooLarge() {
        assertEquals(rejected(RejectionReason.TooLargeForDevice), VideoAcceptance.check(info(fps = 240f), FakeCodecs()))
    }

    @Test fun otherCodecsAreNotSupported() {
        assertEquals(rejected(RejectionReason.UnsupportedFormat), VideoAcceptance.check(info("video/av01"), FakeCodecs()))
        assertEquals(rejected(RejectionReason.UnsupportedFormat), VideoAcceptance.check(info("video/x-vnd.on2.vp9"), FakeCodecs()))
    }

    @Test fun hevcWithoutAnyDecoderIsNotSupported() {
        val codecs = FakeCodecs(decoders = setOf(VideoAcceptance.H264))
        assertEquals(rejected(RejectionReason.UnsupportedFormat), VideoAcceptance.check(info(VideoAcceptance.HEVC), codecs))
    }

    @Test fun hdrNeedsATenBitDecoder() {
        val hdr = info(VideoAcceptance.HEVC, hdr = true)
        assertEquals(accepted(VideoAcceptance.HEVC), VideoAcceptance.check(hdr, FakeCodecs()))
        assertEquals(rejected(RejectionReason.TooLargeForDevice), VideoAcceptance.check(hdr, FakeCodecs(tenBitDecode = false)))
    }

    @Test fun creationTimeIsReadAsUtc() {
        assertEquals(1_784_017_925_000L, AndroidVideoProbe.parseCreationTime("20260714T083205.000Z"))
        assertEquals(1_784_017_925_000L, AndroidVideoProbe.parseCreationTime("20260714T083205Z"))
        assertNull(AndroidVideoProbe.parseCreationTime("19040101T000000.000Z"))
        assertNull(AndroidVideoProbe.parseCreationTime("19700101T000000.000Z"))
        assertNull(AndroidVideoProbe.parseCreationTime(null))
        assertNull(AndroidVideoProbe.parseCreationTime("garbage"))
    }

    @Test fun photoDateTakenIsTheWallClockTime() {
        assertEquals(LocalDateTime.of(2026, 7, 14, 10, 32, 5), MediaImporter.parseExifDate("2026:07:14 10:32:05"))
        assertNull(MediaImporter.parseExifDate("    :  :     :  :  "))
        assertNull(MediaImporter.parseExifDate(null))
    }
}
