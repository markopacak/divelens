package com.markopacak.divelens.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.correction.AndroidTimelineSampler
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.watermark.VideoWatermark
import com.markopacak.divelens.watermark.WatermarkPlacement
import com.markopacak.divelens.watermark.WatermarkRenderer
import com.markopacak.divelens.watermark.WatermarkVersion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Exported videos carry the mark in the displayed bottom-right corner of every frame, upright, and keep their format
 * (add-branding-and-watermark task 5.4). Each check compares a watermarked export with a plain one of the same clip.
 */
@UnstableApi
class WatermarkedVideoExportTest {
    private val context = TestMedia.context
    private val resolver = TestMedia.resolver
    private val renderer = WatermarkRenderer(context.assets)

    @Before @After fun cleanUp() = TestMedia.deleteAll()

    private suspend fun import(uri: Uri) = MediaImporter(resolver).import(listOf(uri)).accepted.single() as MediaItem.Video

    private suspend fun timeline(video: MediaItem.Video): CorrectionTimeline {
        val sampler = AndroidTimelineSampler(resolver, markAspect = renderer.aspect())
        val samples = sampler.sample(video.source, CorrectionTimeline.plannedSampleTimes(video.durationUs))
        return samples.fold(CorrectionTimeline(video.durationUs)) { t, (time, p, corner) -> t.withSample(time, p, corner) }.completed()
    }

    private fun writer(timeline: CorrectionTimeline) = MediaStoreVideoWriter(
        context, { timeline }, { 0.78f }, album = TestMedia.TEST_ALBUM, availableBytes = { Long.MAX_VALUE }, watermark = VideoWatermark(renderer),
    )

    @Test fun landscapeClipHasTheMarkInEveryFrameAndKeepsItsFormat() = runBlocking {
        val video = import(TestMedia.longVideo())
        val timeline = timeline(video)
        assertTrue("samples keep their corner", timeline.corners.isNotEmpty())
        val marked = Uri.parse(writer(timeline).write(video, ExportOptions(watermark = true)))
        val plain = Uri.parse(writer(timeline).write(video, ExportOptions.PLAIN))

        val exported = import(marked)
        assertEquals(video.displayWidth to video.displayHeight, exported.displayWidth to exported.displayHeight)
        assertEquals(video.frameRate, exported.frameRate, 0.5f)
        assertEquals(video.durationUs.toDouble(), exported.durationUs.toDouble(), 70_000.0)

        for (t in listOf(0L, video.durationUs / 2, video.durationUs - 200_000)) {
            // This clip is 320 px wide: the mark is 38 px and its dot 1 px, too small to check the orientation, which
            // only a rotated clip puts at risk (the portrait test covers it).
            checkMarkUpright(frame(marked, t, video), frame(plain, t, video), "at $t µs", orientation = false)
        }
    }

    @Test fun portraitClipHasTheMarkUprightInTheDisplayedCorner() = runBlocking {
        val video = import(TestMedia.driftVideo())
        assertEquals(90, video.rotation)
        val timeline = timeline(video)
        val marked = Uri.parse(writer(timeline).write(video, ExportOptions(watermark = true)))
        val plain = Uri.parse(writer(timeline).write(video, ExportOptions.PLAIN))
        assertEquals(90, import(marked).rotation)
        for (t in listOf(300_000L, 1_500_000L)) checkMarkUpright(frame(marked, t, video), frame(plain, t, video), "at $t µs")
    }

    @Test fun watermarkOffLeavesTheFramesAlone() = runBlocking {
        val video = import(TestMedia.driftVideo())
        val timeline = timeline(video)
        val first = Uri.parse(writer(timeline).write(video, ExportOptions.PLAIN))
        val second = Uri.parse(writer(timeline).write(video, ExportOptions.PLAIN))
        val a = frame(first, 1_000_000, video); val b = frame(second, 1_000_000, video)
        val box = box(a)
        assertTrue("no mark", meanDiff(a, b, box.left, box.top, box.width, box.height) < 2.0)
    }

    private data class IntBox(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun box(frame: Bitmap): IntBox {
        val b = WatermarkPlacement(frame.width, frame.height, renderer.aspect()).box
        return IntBox(b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
    }

    /**
     * The difference between [marked] and [plain] is in the bottom-right box and nowhere else, and it matches the
     * upright mark better than the mark mirrored, flipped or turned.
     */
    private fun checkMarkUpright(marked: Bitmap, plain: Bitmap, label: String, orientation: Boolean = true) {
        val box = box(marked)
        val inBox = meanDiff(marked, plain, box.left, box.top, box.width, box.height)
        val bottomLeft = meanDiff(marked, plain, (0.03f * marked.width).roundToInt(), box.top, box.width, box.height)
        val topRight = meanDiff(marked, plain, box.left, (0.03f * marked.width).roundToInt(), box.width, box.height)
        assertTrue("$label: box differs by $inBox", inBox > 4.0) // the mark covers part of its box; on a 320 px clip it is 38 px wide
        assertTrue("$label: bottom-left differs by $bottomLeft", bottomLeft < 2.0)
        assertTrue("$label: top-right differs by $topRight", topRight < 2.0)
        if (!orientation) return

        val diff = DoubleArray(box.width * box.height) { i ->
            abs(luma(marked.getPixel(box.left + i % box.width, box.top + i / box.width)) - luma(plain.getPixel(box.left + i % box.width, box.top + i / box.width)))
        }
        val mask = renderer.bitmap(WatermarkVersion.Light, box.width, box.height)
        fun score(map: (Int, Int) -> Pair<Int, Int>): Double {
            var s = 0.0
            for (y in 0 until box.height) for (x in 0 until box.width) {
                val (mx, my) = map(x, y)
                s += diff[y * box.width + x] * Color.alpha(mask.getPixel(mx, my))
            }
            return s
        }
        val w = box.width - 1; val h = box.height - 1
        val upright = score { x, y -> x to y }
        mapOf("mirrored" to score { x, y -> w - x to y }, "turned 180°" to score { x, y -> w - x to h - y }).forEach { (name, s) ->
            assertTrue("$label: matches the mark $name ($s) as well as upright ($upright)", upright > 1.15 * s)
        }
        // The ring and the wordmark look much the same upside down at video sizes; the dot doesn't. Upright it is at
        // viewBox (49, 6); flipped it would be at (49, 51), where the mark has nothing.
        fun diffAt(vx: Float, vy: Float): Double {
            val cx = ((vx + 3f) / 232.5f * box.width).roundToInt(); val cy = ((vy + 3f) / 63f * box.height).roundToInt()
            var sum = 0.0; var n = 0
            for (y in cy - 1..cy + 1) for (x in cx - 1..cx + 1) { sum += diff[y.coerceIn(0, h) * box.width + x.coerceIn(0, w)]; n++ }
            return sum / n
        }
        val dot = diffAt(49f, 6f)
        val flipped = diffAt(49f, 51f)
        assertTrue("$label: the dot changes the frame by $dot where it belongs and $flipped where it would be upside down", dot > 3 * flipped && dot > 10)
    }

    private fun meanDiff(a: Bitmap, b: Bitmap, left: Int, top: Int, width: Int, height: Int): Double {
        var sum = 0.0
        for (y in top until top + height) for (x in left until left + width) sum += abs(luma(a.getPixel(x, y)) - luma(b.getPixel(x, y)))
        return sum / (width * height)
    }

    private fun luma(c: Int) = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    /** The frame at [timeUs] as it is shown (turned upright if the retriever returns it as stored). */
    private fun frame(uri: Uri, timeUs: Long, video: MediaItem.Video): Bitmap {
        val raw = MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                release()
            }
        }
        if (raw.width == video.displayWidth) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(video.rotation.toFloat()) }, true)
    }
}
