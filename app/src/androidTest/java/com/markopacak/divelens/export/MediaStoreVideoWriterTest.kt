package com.markopacak.divelens.export

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.analysis.AnalysisImageBuilder
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.analysis.GuidedShift
import com.markopacak.divelens.correction.AndroidTimelineSampler
import com.markopacak.divelens.importer.AndroidVideoProbe
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.media.FailureReason
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/** Video export (add-video-processing D7, task 6.1) on the synthetic fixtures. */
@UnstableApi
class MediaStoreVideoWriterTest {
    private val context = TestMedia.context
    private val resolver = TestMedia.resolver

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    private suspend fun import(uri: Uri) = MediaImporter(resolver).import(listOf(uri)).accepted.single() as MediaItem.Video

    /** The fixture's timeline as the app builds it: sync frames sampled every second, smoothed. */
    private suspend fun timeline(video: MediaItem.Video): CorrectionTimeline {
        val samples = AndroidTimelineSampler(resolver).sample(video.source, CorrectionTimeline.plannedSampleTimes(video.durationUs))
        return samples.fold(CorrectionTimeline(video.durationUs)) { t, (time, p, corner) -> t.withSample(time, p, corner) }.completed()
    }

    private fun writer(
        timeline: CorrectionTimeline,
        strength: Float = 0.78f,
        availableBytes: () -> Long = { Long.MAX_VALUE },
        whileEncoding: suspend () -> Unit = {},
    ) = MediaStoreVideoWriter(context, { timeline }, { strength }, album = TestMedia.TEST_ALBUM, availableBytes = availableBytes, whileEncoding = whileEncoding)

    private fun frameAt(uri: Uri, timeUs: Long): Bitmap = MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            release()
        }
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun tracks(uri: Uri): List<MediaFormat> = MediaExtractor().run {
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { setDataSource(it.fileDescriptor) }
            (0 until trackCount).map(::getTrackFormat)
        } finally {
            release()
        }
    }

    private fun audioSamples(uri: Uri): List<ByteArray> = MediaExtractor().run {
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { setDataSource(it.fileDescriptor) }
            selectTrack((0 until trackCount).first { getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            val buffer = java.nio.ByteBuffer.allocate(1 shl 16)
            buildList {
                while (true) {
                    val n = readSampleData(buffer, 0)
                    if (n < 0) break
                    add(ByteArray(n).also { buffer.get(it, 0, n); buffer.clear() })
                    advance()
                }
            }
        } finally {
            release()
        }
    }

    private fun metadata(uri: Uri, key: Int): String? = MediaMetadataRetriever().run {
        try {
            setDataSource(context, uri)
            extractMetadata(key)
        } finally {
            release()
        }
    }

    @Test fun exportKeepsTheFormatAndAudioAndDropsLocationAndTelemetry() = runBlocking {
        val source = TestMedia.driftVideo()
        val video = import(source)
        val out = Uri.parse(writer(timeline(video)).write(video))

        // Same size, rate, duration and display orientation.
        val exported = import(out)
        assertEquals(video.displayWidth to video.displayHeight, exported.displayWidth to exported.displayHeight)
        assertEquals(video.frameRate, exported.frameRate, 0.5f)
        assertEquals(video.durationUs.toDouble(), exported.durationUs.toDouble(), 70_000.0)
        assertEquals("video/avc", exported.codec)

        // Audio passed through sample for sample.
        val a = audioSamples(source); val b = audioSamples(out)
        assertEquals(a.size, b.size)
        assertTrue("audio samples differ", a.zip(b).all { (x, y) -> x.contentEquals(y) })

        // Creation time kept, gallery date set.
        assertEquals(video.creationTimeMillis, AndroidVideoProbe.parseCreationTime(metadata(out, MediaMetadataRetriever.METADATA_KEY_DATE)))
        assertEquals(video.creationTimeMillis, TestMedia.column(out, MediaStore.Video.Media.DATE_TAKEN) { it.getLong(0) })
        assertEquals("DiveLens_20260714_" , TestMedia.column(out, MediaStore.Video.Media.DISPLAY_NAME) { it.getString(0) }.take(18))

        // The fixture has a text track standing in for telemetry; the export has only video and audio. (Location is
        // checked on raw bytes below: MediaStore hides it from apps without the media-location permission.)
        assertEquals(3, tracks(source).size)
        assertEquals(listOf("audio", "video"), tracks(out).map { it.getString(MediaFormat.KEY_MIME)!!.substringBefore('/') }.sorted())

        // The original is untouched and the export is published.
        assertEquals(1, TestMedia.exportedVideoRows().size)
        assertEquals(0, TestMedia.column(out, MediaStore.Video.Media.IS_PENDING) { it.getInt(0) })
    }

    /** The clean muxer on files outside MediaStore, so the raw bytes show what is written. */
    @Test fun cleanMuxerDropsLocationAndTelemetry() = runBlocking {
        val input = java.io.File(context.cacheDir, "fixture.mp4")
        val output = java.io.File(context.cacheDir, "clean.mp4")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic_drift.mp4").use { a -> input.outputStream().use { a.copyTo(it) } }
            val location = "+48.8584+002.2945".toByteArray() // the QuickTime \u00a9xyz box
            val loci = "loci".toByteArray() // the 3GPP location box
            input.readBytes().let { assertTrue("fixture has both location boxes", it.contains(location) && it.contains(loci)) }
            val done = CompletableDeferred<Unit>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                output.outputStream().use { out ->
                    Transformer.Builder(context)
                        .setMuxerFactory(CleanMp4Muxer.Factory(out, 1_784_017_925_000L))
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: androidx.media3.transformer.ExportException) {
                                done.completeExceptionally(exportException)
                            }
                        })
                        .build()
                        .start(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(input)), java.io.File(context.cacheDir, "unused.mp4").absolutePath)
                    done.await()
                }
            }
            val bytes = output.readBytes()
            assertFalse("export contains the location", bytes.contains(location))
            assertFalse("export contains a 3GPP location box", bytes.contains(loci))
            assertFalse("export contains a location atom", bytes.contains(byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())))
            assertFalse("export contains the telemetry text", bytes.contains("GPS 48.8584".toByteArray()))
            val formats = MediaExtractor().run { try { setDataSource(output.path); (0 until trackCount).map(::getTrackFormat) } finally { release() } }
            assertEquals(listOf("audio", "video"), formats.map { it.getString(MediaFormat.KEY_MIME)!!.substringBefore('/') }.sorted())
        } finally {
            input.delete(); output.delete()
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Test fun exportedFramesFollowTheTimeline() = runBlocking {
        val source = TestMedia.driftVideo()
        val video = import(source)
        val timeline = timeline(video)
        val out = Uri.parse(writer(timeline, strength = 0.6f).write(video))
        // At 1.5 s the timeline is between its samples; a wrong time base would apply another moment's correction.
        for (t in listOf(500_000L, 1_500_000L)) {
            val original = frameAt(source, t)
            val w = original.width; val h = original.height
            val px = pixels(original)
            val p = timeline.paramsAt(t, 0.6f)
            val expected = px.copyOf()
            CpuCorrection.applyInPlace(expected, Correction(p, GuidedShift.map(AnalysisImageBuilder.of(px, w, h, GuidedShift.SMOOTH_WIDTH), p), w, h), 0, 0, w, h)
            val got = pixels(frameAt(out, t))
            val means = DoubleArray(3)
            for (i in got.indices) for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
                means[c] += ((got[i] shr shift and 0xff) - (expected[i] shr shift and 0xff)).toDouble() / got.size
            }
            assertTrue("at $t µs the mean colour differs from the CPU correction by ${means.toList()}", means.all { abs(it) <= 1.5 })
        }
    }

    @Test fun hlgClipExportsAsSdr() = runBlocking {
        val result = MediaImporter(resolver).import(listOf(TestMedia.hlgVideo()))
        val video = result.accepted.singleOrNull() as MediaItem.Video? ?: return@runBlocking // no 10-bit decoder on this device
        assertTrue(video.hdr)
        val out = Uri.parse(writer(timeline(video)).write(video))
        val format = tracks(out).single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
        assertFalse("transfer $transfer", transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084)
        val grey = pixels(frameAt(out, 500_000)).map { ((it shr 16 and 0xff) + (it shr 8 and 0xff) + (it and 0xff)) / 765.0 }.average()
        println("HLG export mean grey $grey")
        assertTrue("mean grey $grey is not a natural brightness", grey in 0.1..0.9)
    }

    @Test fun cancelDuringTheEncodeLeavesNoRow() = runBlocking {
        val video = import(TestMedia.driftVideo())
        val timeline = timeline(video)
        val encoding = CompletableDeferred<Unit>()
        val job = launch {
            writer(timeline, whileEncoding = { encoding.complete(Unit); awaitCancellation() }).write(video)
            fail("the export finished despite the cancel")
        }
        encoding.await()
        assertEquals(1, TestMedia.exportedVideoRows().size) // the pending row exists while encoding
        job.cancel()
        job.join()
        assertEquals(emptyList<Uri>(), TestMedia.exportedVideoRows())
    }

    @Test fun notEnoughStorageFailsBeforeWritingAnything() = runBlocking {
        val video = import(TestMedia.driftVideo())
        try {
            writer(timeline(video), availableBytes = { 50L * 1024 * 1024 }).write(video)
            fail("expected InsufficientStorage")
        } catch (e: ExportException) {
            assertEquals(FailureReason.InsufficientStorage, e.reason)
        }
        assertEquals(emptyList<Uri>(), TestMedia.exportedVideoRows())
    }
}
