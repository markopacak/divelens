package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionParams
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

/** What preparing one photo produces (design D2): its correction, corrected thumbnail and display geometry. */
class Prepared<T>(
    val correction: Correction,
    val thumbnail: T,
    /** EXIF rotation in degrees (0, 90, 180, 270); the correction is defined on the stored pixels. */
    val rotation: Int,
)

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
 */
class PhotoPreviewStore<T>(
    private val preparer: PhotoPreparer<T>,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _entries = MutableStateFlow<Map<String, PreviewEntry<T>>>(emptyMap())
    val entries: StateFlow<Map<String, PreviewEntry<T>>> = _entries.asStateFlow()

    private val mutex = Mutex()
    private var order: List<String> = emptyList()
    private var priority: List<String> = emptyList()
    private val requests = LinkedHashMap<String, MutableList<CompletableDeferred<Correction>>>()
    private val corrections = object : LinkedHashMap<String, Correction>(CORRECTIONS_KEPT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Correction>) = size > CORRECTIONS_KEPT
    }
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
                requests.remove(source)?.forEach { it.completeExceptionally(CancellationException("photo removed")) }
            }
            _entries.update { old -> sources.associateWith { old[it] ?: PreviewEntry.Pending } }
            running?.let { (source, job) -> if (source in removed) job.cancel() }
        }
        wake.trySend(Unit)
    }

    /** Photos to prepare first, most important first, and whose corrections to keep (the viewer's photo and its neighbours). */
    suspend fun prioritize(sources: List<String>) {
        mutex.withLock { priority = sources.filter { it in order } }
        wake.trySend(Unit)
    }

    /** The photo's correction, from the cache or estimated ahead of other work. Throws if it cannot be estimated. */
    suspend fun correction(source: String): Correction {
        val waiter = mutex.withLock {
            corrections[source]?.let { return it }
            CompletableDeferred<Correction>().also { requests.getOrPut(source) { mutableListOf() } += it }
        }
        wake.trySend(Unit)
        return waiter.await()
    }

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

    /** Starts the next job: requests first, then prioritized photos not yet prepared, then the first pending photo in selection order. */
    private suspend fun nextJob(): Pair<String, Deferred<Result<T>>>? {
        val entries = _entries.value
        val (source, prepare) = requests.keys.firstOrNull()?.let { it to (entries[it] !is PreviewEntry.Ready) }
            ?: (priority + order).firstOrNull { entries[it] == PreviewEntry.Pending }?.let { it to true }
            ?: return null
        // A supervisor child: a failed or cancelled photo must not cancel the worker.
        val context = currentCoroutineContext()
        val job = CoroutineScope(context + SupervisorJob(context[Job]) + dispatcher).async {
            if (prepare) Result.Prepared(preparer.prepare(source)) else Result.Estimated(preparer.estimate(source))
        }
        running = source to job
        return source to job
    }

    private fun finish(source: String, outcome: kotlin.Result<Result<T>>) {
        val waiters = requests.remove(source).orEmpty()
        if (source !in order) {
            // Not (or no longer) in the working set: answer any request, keep nothing.
            outcome.fold({ r -> waiters.forEach { it.complete(r.correction) } }, { e -> waiters.forEach { it.completeExceptionally(e) } })
            return
        }
        outcome.fold(
            onSuccess = { result ->
                if (result is Result.Prepared) {
                    val p = result.prepared
                    _entries.update {
                        it + (source to PreviewEntry.Ready(p.correction.params, p.correction.width, p.correction.height, p.rotation, p.thumbnail))
                    }
                }
                val correction = result.correction
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
        val correction: Correction

        class Prepared<T>(val prepared: com.markopacak.divelens.correction.Prepared<T>) : Result<T> {
            override val correction get() = prepared.correction
        }
        class Estimated(override val correction: Correction) : Result<Nothing>
    }

    companion object {
        /** The viewer's photo and its two neighbours, or the photo being exported. */
        const val CORRECTIONS_KEPT = 3
    }
}
