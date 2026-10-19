package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.V1Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** What preparing one photo produces (design D2): its correction, corrected thumbnail and display geometry. */
class Prepared<T>(
    val correction: Correction,
    /** Corrected at the correction's strength. */
    val thumbnail: T,
    /** EXIF rotation in degrees (0, 90, 180, 270); the correction is defined on the stored pixels. */
    val rotation: Int,
    /** Renders the thumbnail again at another strength without the shift maps (add-strength-slider D3); null if it cannot. */
    val renderer: ThumbnailRenderer<T>? = null,
)

/** Renders one photo's thumbnail at a strength, quickly and without decoding or estimating. */
fun interface ThumbnailRenderer<T> {
    fun render(strength: Float): T
}

/** Prepares photos for the store. [T] is the thumbnail type, so the store's logic runs without Android. */
interface PhotoPreparer<T> {
    /** Reduced decode, correction and thumbnail. Throws on failure. */
    suspend fun prepare(source: String): Prepared<T>

    /** Only the correction, when its maps were dropped from the cache. Throws on failure. */
    suspend fun estimate(source: String): Correction
}

/** The preview state of one photo. */
sealed interface PreviewEntry<out T> {
    data object Pending : PreviewEntry<Nothing>
    data class Ready<T>(val params: CorrectionParams, val width: Int, val height: Int, val rotation: Int, val thumbnail: T) :
        PreviewEntry<T>
    data class Failed(val error: Throwable) : PreviewEntry<Nothing>
}

/**
 * Per-photo preview state for the working set (design D2), keyed by source URI. Every photo keeps its
 * parameters, geometry and thumbnail; full corrections with their shift maps (about 25 MB each) are kept for at
 * most [CORRECTIONS_KEPT] photos. One worker prepares photos in selection order; [prioritize]d photos and
 * [correction] requests go first. A failure is kept per photo and does not stop the queue.
 *
 * Each photo also has a strength (add-strength-slider D2), [defaultStrength] unless set: [correction] returns the
 * correction at that strength, and a changed strength re-renders the thumbnail before new photos are prepared.
 */
class PhotoPreviewStore<T>(
    private val preparer: PhotoPreparer<T>,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val defaultStrength: Float = V1Model.DEFAULT_STRENGTH,
) {
    private val _entries = MutableStateFlow<Map<String, PreviewEntry<T>>>(emptyMap())
    val entries: StateFlow<Map<String, PreviewEntry<T>>> = _entries.asStateFlow()

    private val _strengths = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Strengths the user set, by source; photos without an entry are at [defaultStrength]. */
    val strengths: StateFlow<Map<String, Float>> = _strengths.asStateFlow()

    private val mutex = Mutex()
    private var order: List<String> = emptyList()
    private var priority: List<String> = emptyList()
    private val requests = LinkedHashMap<String, MutableList<CompletableDeferred<Correction>>>()
    private val corrections = object : LinkedHashMap<String, Correction>(CORRECTIONS_KEPT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Correction>) = size > CORRECTIONS_KEPT
    }
    private val renderers = HashMap<String, ThumbnailRenderer<T>>() // guarded by mutex
    /** The strength each Ready thumbnail is shown at. Guarded by mutex. */
    private val shownStrength = HashMap<String, Float>()
    private var running: Pair<String, Job>? = null // guarded by mutex
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { work() }
    }

    /** Follows the working set: new photos are queued in this order, removed ones are cancelled and freed. */
    suspend fun setPhotos(sources: List<String>) {
        mutex.withLock {
            val keep = sources.toSet()
            val removed = order.filterNot { it in keep }
            order = sources
            priority = priority.filter { it in keep }
            removed.forEach { source ->
                corrections.remove(source)
                renderers.remove(source)
                shownStrength.remove(source)
                requests.remove(source)?.forEach { it.completeExceptionally(CancellationException("photo removed")) }
            }
            _entries.update { old -> sources.associateWith { old[it] ?: PreviewEntry.Pending } }
            _strengths.update { old -> old.filterKeys { it in keep } }
            running?.let { (source, job) -> if (source in removed) job.cancel() }
        }
        wake.trySend(Unit)
    }

    /** Photos to prepare first, most important first, and whose corrections to keep (the viewer's photo and its neighbours). */
    suspend fun prioritize(sources: List<String>) {
        mutex.withLock { priority = sources.filter { it in order } }
        wake.trySend(Unit)
    }

    /**
     * The photo's correction at its strength, from the cache or estimated ahead of other work. Throws if it cannot
     * be estimated.
     */
    suspend fun correction(source: String): Correction {
        val waiter = mutex.withLock {
            corrections[source]?.let { return it.withStrength(strengthOf(source)) }
            CompletableDeferred<Correction>().also { requests.getOrPut(source) { mutableListOf() } += it }
        }
        wake.trySend(Unit)
        return waiter.await().withStrength(strengthOf(source))
    }

    fun strengthOf(source: String): Float = _strengths.value[source] ?: defaultStrength

    /** Sets the photo's strength, rounded to 0.01 within 0..1 (design D1), and re-renders its thumbnail. */
    fun setStrength(source: String, strength: Float) {
        val s = round(strength)
        _strengths.update { if (s == defaultStrength) it - source else it + (source to s) }
        wake.trySend(Unit)
    }

    /** Sets every photo of the working set to [strength] ("Apply to all"). */
    suspend fun setAllStrengths(strength: Float) {
        val s = round(strength)
        val sources = mutex.withLock { order }
        _strengths.value = if (s == defaultStrength) emptyMap() else sources.associateWith { s }
        wake.trySend(Unit)
    }

    private fun round(strength: Float) = (strength.coerceIn(0f, 1f) * 100).roundToInt() / 100f

    private suspend fun work() {
        while (true) {
            val next = mutex.withLock { nextJob() }
            if (next == null) {
                wake.receive()
                continue
            }
            val (source, job) = next
            val outcome = runCatching { job.await() }
            mutex.withLock {
                running = null
                finish(source, outcome)
            }
        }
    }

    /**
     * Starts the next job: requests first, then thumbnails whose strength changed, then prioritized photos not yet
     * prepared, then the first pending photo in selection order.
     */
    private suspend fun nextJob(): Pair<String, Deferred<Result<T>>>? {
        val entries = _entries.value
        val request = requests.keys.firstOrNull()
        val render = if (request == null) (priority + order).firstOrNull { needsRender(it) } else null
        val source = request ?: render ?: (priority + order).firstOrNull { entries[it] == PreviewEntry.Pending } ?: return null
        val renderer = render?.let { renderers.getValue(it) }
        val strength = strengthOf(source)
        val estimateOnly = request != null && entries[source] is PreviewEntry.Ready
        // A supervisor child: a failed or cancelled photo must not cancel the worker.
        val context = currentCoroutineContext()
        val job = CoroutineScope(context + SupervisorJob(context[Job]) + dispatcher).async {
            when {
                renderer != null -> Result.Rendered(runCatching { renderer.render(strength) }.getOrNull(), strength)
                estimateOnly -> Result.Estimated(preparer.estimate(source))
                else -> Result.Prepared(preparer.prepare(source))
            }
        }
        running = source to job
        return source to job
    }

    private fun needsRender(source: String) =
        source in renderers && shownStrength[source].let { it != null && it != strengthOf(source) }

    private fun finish(source: String, outcome: kotlin.Result<Result<T>>) {
        val result = outcome.getOrNull()
        if (result is Result.Rendered) {
            // Requests that arrived meanwhile stay queued. A failed render keeps the shown thumbnail until the strength changes again.
            if (source !in order) return
            shownStrength[source] = result.strength
            val thumbnail = result.thumbnail ?: return
            _entries.update { old ->
                val ready = old[source] as? PreviewEntry.Ready ?: return@update old
                old + (source to ready.copy(thumbnail = thumbnail))
            }
            return
        }
        val waiters = requests.remove(source).orEmpty()
        if (source !in order) {
            // Not (or no longer) in the working set: answer any request, keep nothing.
            outcome.fold({ r -> waiters.forEach { it.complete(r.correction) } }, { e -> waiters.forEach { it.completeExceptionally(e) } })
            return
        }
        outcome.fold(
            onSuccess = { r ->
                if (r is Result.Prepared) {
                    val p = r.prepared
                    p.renderer?.let { renderers[source] = it }
                    shownStrength[source] = p.correction.params.strength
                    _entries.update {
                        it + (source to PreviewEntry.Ready(p.correction.params, p.correction.width, p.correction.height, p.rotation, p.thumbnail))
                    }
                }
                val correction = r.correction
                if (waiters.isNotEmpty() || source in priority) corrections[source] = correction
                waiters.forEach { it.complete(correction) }
            },
            onFailure = { error ->
                if (_entries.value[source] !is PreviewEntry.Ready) _entries.update { it + (source to PreviewEntry.Failed(error)) }
                waiters.forEach { it.completeExceptionally(error) }
            },
        )
    }

    private sealed interface Result<out T> {
        class Prepared<T>(val prepared: com.markopacak.divelens.correction.Prepared<T>) : Result<T>
        class Estimated(val correction: Correction) : Result<Nothing>
        class Rendered<T>(val thumbnail: T?, val strength: Float) : Result<T>
    }

    /** The correction a prepare or estimate produced. */
    private val Result<T>.correction: Correction
        get() = when (this) {
            is Result.Prepared -> prepared.correction
            is Result.Estimated -> correction
            is Result.Rendered -> error("a render has no correction")
        }

    companion object {
        /** The viewer's photo and its two neighbours, or the photo being exported. */
        const val CORRECTIONS_KEPT = 3
    }
}
