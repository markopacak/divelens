package com.markopacak.divelens.correction

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionAnalyzer
import com.markopacak.divelens.analysis.CpuCorrection
import com.markopacak.divelens.analysis.V1Model
import com.markopacak.divelens.export.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * The automatic correction for exports: estimates the photo's parameters and shift maps from
 * downscaled copies, then corrects the full-resolution bitmap in place on the GPU. If the GPU path fails, the same
 * correction runs on the CPU for the pixels the GPU did not finish (design D5). Returns [process]'s
 * input, which must be a mutable `ARGB_8888` bitmap.
 */
class CorrectionPhotoProcessor(
    private val strength: Float = V1Model.DEFAULT_STRENGTH,
    private val gpu: (Bitmap, Correction) -> Unit = GlCorrectionRenderer()::apply,
) : PhotoProcessor {

    override suspend fun process(input: Bitmap): Bitmap {
        val start = SystemClock.elapsedRealtime()
        val correction = withContext(Dispatchers.Default) { estimate(input, strength) }
        val estimated = SystemClock.elapsedRealtime()
        var path = "GPU"
        try {
            withContext(glDispatcher) { gpu(input, correction) }
        } catch (e: GpuCorrectionException) {
            Log.w(TAG, "GPU correction failed, finishing on the CPU", e)
            path = "CPU"
            withContext(Dispatchers.Default) { applyOnCpu(input, correction, e.corrected) }
        }
        val end = SystemClock.elapsedRealtime()
        Log.i(TAG, "corrected ${input.width}x${input.height}: analysis ${estimated - start} ms, $path ${end - estimated} ms")
        return input
    }

    companion object {
        private const val TAG = "CorrectionProcessor"
        private const val BAND_ROWS = 64

        /** EGL contexts are bound to a thread, so all GPU work runs on this one. */
        private val glDispatcher = Executors.newSingleThreadExecutor { Thread(it, "divelens-gl").apply { isDaemon = true } }
            .asCoroutineDispatcher()

        /** The correction for [bitmap], from downscaled copies built band by band (no second full-size bitmap). */
        fun estimate(bitmap: Bitmap, strength: Float = V1Model.DEFAULT_STRENGTH): Correction {
            val w = bitmap.width; val h = bitmap.height
            val builder = CorrectionAnalyzer(w, h)
            val tiles = BitmapTiles(bitmap, w, min(BAND_ROWS, h))
            val band = IntArray(w * min(BAND_ROWS, h))
            var y = 0
            while (y < h) {
                val rows = min(BAND_ROWS, h - y)
                tiles.read(0, y, w, rows)
                tiles.toArgb(band, w * rows)
                builder.addRows(band, 0, w, rows)
                y += rows
            }
            return builder.build(strength)
        }

        /** Corrects [bitmap] in place on the CPU, skipping the pixels in [done]. */
        fun applyOnCpu(bitmap: Bitmap, correction: Correction, done: CorrectedRegion = CorrectedRegion.NONE) {
            val w = bitmap.width; val h = bitmap.height
            if (done.bandDoneX > 0 && done.bandBottom > done.bandTop) {
                correctRect(bitmap, correction, done.bandDoneX, done.bandTop, w - done.bandDoneX, done.bandBottom - done.bandTop)
            }
            correctRect(bitmap, correction, 0, maxOf(done.bandTop, done.bandBottom), w, h - maxOf(done.bandTop, done.bandBottom))
        }

        private fun correctRect(bitmap: Bitmap, correction: Correction, x: Int, top: Int, w: Int, height: Int) {
            if (w <= 0 || height <= 0) return
            val tiles = BitmapTiles(bitmap, w, min(BAND_ROWS, height))
            val px = IntArray(w * min(BAND_ROWS, height))
            var y = top
            while (y < top + height) {
                val rows = min(BAND_ROWS, top + height - y)
                val n = w * rows
                tiles.read(x, y, w, rows)
                tiles.toArgb(px, n)
                CpuCorrection.applyInPlace(px, correction, x, y, w, rows)
                tiles.fromArgb(px, n)
                tiles.write(x, y, w, rows)
                y += rows
            }
        }
    }
}
