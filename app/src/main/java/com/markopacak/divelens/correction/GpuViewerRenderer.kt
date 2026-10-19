package com.markopacak.divelens.correction

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CpuCorrection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The viewer's renderer (design D4, D5): decodes the photo at the sample size the zoom needs, whole for the
 * base layer and by 512 px region for tiles, and corrects each bitmap with the photo's shared [Correction]
 * through one GPU session per photo, falling back to the CPU.
 *
 * Tiles are cut on the stored pixel grid at the sample size, so a tile's position in the sampled photo is
 * exact and at sample size 1 its pixels are the export's. They are rotated for display afterwards.
 */
class GpuViewerRenderer(
    private val resolver: ContentResolver,
    private val corrections: CorrectionSource,
    /** 1024 px GPU tiles: the viewer's bitmaps are small, and up to three sessions are open at once. */
    private val gl: GlCorrectionRenderer = GlCorrectionRenderer(maxTile = 1024),
    private val tiles: TileCache = TileCache(TILE_BUDGET_BYTES),
) : ViewerRenderer {

    override suspend fun open(source: String): ViewerPhoto {
        val start = SystemClock.elapsedRealtime()
        val correction = corrections.correction(source)
        val corrected = SystemClock.elapsedRealtime()
        val rotation = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(source.toUri())?.use { ExifInterface(it).rotationDegrees } }.getOrNull() ?: 0
        }
        val session = withContext(GlThread.dispatcher) {
            try {
                gl.openSession(correction)
            } catch (e: GpuCorrectionException) {
                Log.w(TAG, "no GPU session, correcting on the CPU", e)
                null
            }
        }
        Log.i(TAG, "opened ${correction.width}x${correction.height}: correction ${corrected - start} ms, session ${SystemClock.elapsedRealtime() - corrected} ms")
        return Photo(source, correction, Orientation(rotation, correction.width, correction.height), session)
    }

    private inner class Photo(
        private val source: String,
        private val correction: Correction,
        private val orientation: Orientation,
        private var session: GlCorrectionRenderer.Session?,
    ) : ViewerPhoto {
        override val width get() = orientation.displayWidth
        override val height get() = orientation.displayHeight

        private val lock = Mutex()
        private var strength = correction.params.strength
        private var base: Pair<Int, Layer>? = null
        private var region: BitmapRegionDecoder? = null
        private var regionFailed = false
        private var closed = false

        override suspend fun base(fitWidth: Int, fitHeight: Int): Layer = lock.withLock {
            var n = 1
            while (width / (n * 2) >= fitWidth && height / (n * 2) >= fitHeight) n *= 2
            base?.let { (size, layer) -> if (size == n) return@withLock layer }
            val start = SystemClock.elapsedRealtime()
            val decoded = withContext(Dispatchers.IO) {
                resolver.openInputStream(source.toUri())?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = n
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    })
                } ?: throw IOException("cannot decode $source")
            }
            layer(decoded, 0, 0, decoded.width, decoded.height, Rect(0, 0, orientation.storedWidth, orientation.storedHeight))
                .also { base = n to it }
                .also { Log.i(TAG, "base ${decoded.width}x${decoded.height} (1/$n): ${SystemClock.elapsedRealtime() - start} ms") }
        }

        override suspend fun tiles(sampleSize: Int, visible: Rect): List<Layer>? = lock.withLock {
            val decoder = regionDecoder() ?: return@withLock null
            val stored = orientation.toStored(visible)
            if (!stored.intersect(0, 0, orientation.storedWidth, orientation.storedHeight)) return@withLock emptyList()
            val step = TILE_SIZE * sampleSize
            val keys = (stored.top / step..(stored.bottom - 1) / step).flatMap { row ->
                (stored.left / step..(stored.right - 1) / step).map { col -> TileCache.Key(source, sampleSize, col, row) }
            }
            // A tile made at another strength is corrected again from its kept original (add-strength-slider D5).
            val result = keys.map { key ->
                val kept = tiles.get(key)
                when {
                    kept == null -> makeTile(decoder, key, step).also { tiles.put(key, it) }
                    kept.strength != strength -> rerender(kept).also { tiles.put(key, it) }
                    else -> kept
                }
            }
            tiles.trim(keep = keys.toSet())
            result
        }

        private suspend fun makeTile(decoder: BitmapRegionDecoder, key: TileCache.Key, step: Int): Layer {
            val n = key.sampleSize
            val rect = Rect(key.col * step, key.row * step, minOf((key.col + 1) * step, orientation.storedWidth), minOf((key.row + 1) * step, orientation.storedHeight))
            // JPEG chroma upsampling at a region's border differs from a full decode, so decode with a margin and
            // crop: the tile's pixels then equal the full decode's (and so the export's at sample size 1).
            val margin = REGION_MARGIN * n
            val padded = Rect(maxOf(0, rect.left - margin), maxOf(0, rect.top - margin),
                minOf(orientation.storedWidth, rect.right + margin), minOf(orientation.storedHeight, rect.bottom + margin))
            val decoded = withContext(Dispatchers.IO) {
                val region = decoder.decodeRegion(padded, BitmapFactory.Options().apply {
                    inSampleSize = n
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: throw IOException("cannot decode a region of $source")
                val x = (rect.left - padded.left) / n; val y = (rect.top - padded.top) / n
                val w = minOf(ceilDiv(rect.width(), n), region.width - x); val h = minOf(ceilDiv(rect.height(), n), region.height - y)
                Bitmap.createBitmap(region, x, y, w, h).copy(Bitmap.Config.ARGB_8888, true)
            }
            val sampledWidth = ceilDiv(orientation.storedWidth, n); val sampledHeight = ceilDiv(orientation.storedHeight, n)
            return layer(decoded, key.col * TILE_SIZE, key.row * TILE_SIZE, sampledWidth, sampledHeight, rect)
        }

        /** Corrects [decoded] (stored orientation, mutable) as the region at origin of the sampled photo, and rotates both versions for display. */
        private suspend fun layer(decoded: Bitmap, originX: Int, originY: Int, imageWidth: Int, imageHeight: Int, storedRect: Rect): Layer {
            val stored = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true)
            val original = stored.copy(Bitmap.Config.ARGB_8888, false)
            correct(stored, original, originX, originY, imageWidth, imageHeight)
            return Layer(rotate(stored), rotate(original), orientation.toDisplay(storedRect)).also {
                it.place = Placement(originX, originY, imageWidth, imageHeight)
                it.strength = strength
            }
        }

        /** Corrects [bitmap] in place; [original] holds its uncorrected pixels in case the GPU fails halfway. */
        private suspend fun correct(bitmap: Bitmap, original: Bitmap, originX: Int, originY: Int, imageWidth: Int, imageHeight: Int) {
            val s = session
            if (s != null) {
                try {
                    withContext(GlThread.dispatcher) { s.apply(bitmap, originX, originY, imageWidth, imageHeight, strength) }
                    return
                } catch (e: GpuCorrectionException) {
                    Log.w(TAG, "GPU correction failed, using the CPU for this photo", e)
                    session = null
                    withContext(GlThread.dispatcher + NonCancellable) { s.close() }
                }
            }
            withContext(Dispatchers.Default) {
                val px = IntArray(bitmap.width * bitmap.height)
                original.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val c = Correction(correction.params.withStrength(strength), correction.map, imageWidth, imageHeight)
                CpuCorrection.applyInPlace(px, c, originX, originY, bitmap.width, bitmap.height)
                bitmap.setPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }

        override suspend fun setStrength(strength: Float) = lock.withLock {
            if (strength == this.strength) return@withLock
            val start = SystemClock.elapsedRealtime()
            this.strength = strength
            base = base?.let { (n, layer) -> n to rerender(layer) }
            Log.i(TAG, "strength $strength: base in ${SystemClock.elapsedRealtime() - start} ms")
        }

        private suspend fun rerender(layer: Layer): Layer {
            val place = layer.place ?: return layer
            val original = rotate(layer.original, back = true)
            val stored = original.copy(Bitmap.Config.ARGB_8888, true)
            correct(stored, original, place.originX, place.originY, place.imageWidth, place.imageHeight)
            return Layer(rotate(stored), layer.original, layer.rect).also {
                it.place = place
                it.strength = strength
            }
        }

        private fun regionDecoder(): BitmapRegionDecoder? {
            if (regionFailed) return null
            region?.let { return it }
            return try {
                resolver.openInputStream(source.toUri())?.use {
                    if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it)
                    else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it, false)
                }.also { region = it } ?: run { regionFailed = true; null }
            } catch (e: IOException) {
                Log.w(TAG, "no region decoding for $source", e)
                regionFailed = true
                null
            }
        }

        private fun rotate(bitmap: Bitmap, back: Boolean = false): Bitmap {
            val degrees = if (back) (360 - orientation.rotation) % 360 else orientation.rotation
            if (degrees == 0) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, false)
        }

        override fun close() {
            if (closed) return
            closed = true
            tiles.removeAll(source)
            base = null
            region?.recycle()
            region = null
            val s = session ?: return
            session = null
            // Bitmaps are not recycled: the UI may still draw them until it recomposes; the GC frees them.
            CoroutineScope(GlThread.dispatcher).launch { s.close() }
        }
    }

    companion object {
        private const val TAG = "ViewerRenderer"
        const val TILE_SIZE = 512

        /** Decoded around each tile and cropped; more than a JPEG block (16 px with chroma subsampling). */
        private const val REGION_MARGIN = 32
        const val TILE_BUDGET_BYTES = 64 * 1024 * 1024

        private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
    }
}

/** Where a layer sits in the sampled photo it was corrected as; needed to correct it again. */
class Placement(val originX: Int, val originY: Int, val imageWidth: Int, val imageHeight: Int)

/** Tiles of all open photos, least recently used first, within a byte budget (design D4). */
class TileCache(private val budgetBytes: Int) {
    data class Key(val source: String, val sampleSize: Int, val col: Int, val row: Int)

    private val map = LinkedHashMap<Key, Layer>(64, 0.75f, true)
    private var bytes = 0

    @Synchronized fun get(key: Key): Layer? = map[key]

    @Synchronized fun put(key: Key, layer: Layer) {
        map.put(key, layer)?.let { bytes -= it.bytes }
        bytes += layer.bytes
    }

    /** Evicts the least recently used tiles outside [keep] until the cache fits its budget. */
    @Synchronized fun trim(keep: Set<Key>) {
        val it = map.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (e.key in keep) continue
            bytes -= e.value.bytes
            it.remove()
        }
    }

    @Synchronized fun removeAll(source: String) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.source == source) { bytes -= e.value.bytes; it.remove() }
        }
    }

    @get:Synchronized val size: Int get() = map.size
    @get:Synchronized val usedBytes: Int get() = bytes
}
