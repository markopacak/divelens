package com.markopacak.divelens.correction

import com.markopacak.divelens.analysis.Correction
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CorrectionTimeline
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

/**
 * Reads timeline samples of a video (add-video-processing D1): for each target time, the sync frame nearest to it and the
 * model's parameters estimated on it. Returns (actual frame time, parameters) per frame it could read.
 */
fun interface TimelineSampler {
    suspend fun sample(source: String, targetTimesUs: List<Long>): List<Pair<Long, CorrectionParams>>
}

/** An item of the working set as the store sees it; [videoDurationUs] is set for videos. */
data class StoreItem(val source: String, val videoDurationUs: Long? = null)

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
 *
 * Videos are prepared like photos (their thumbnail frame) and also get a [CorrectionTimeline], analysed by the same
 * worker in chunks of [SAMPLES_PER_JOB] samples (add-video-processing D6), in this order: correction requests,
 * thumbnail re-renders, the video being exported ([completeTimeline]), the video being viewed from its playback
 * position ([viewVideo]), preparing items, then the other videos in selection order.
 */
class PhotoPreviewStore<T>(
    private val preparer: PhotoPreparer<T>,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val defaultStrength: Float = V1Model.DEFAULT_STRENGTH,
    /** Analyses videos over time; without it videos get a thumbnail but no timeline. */
    private val sampler: TimelineSampler? = null,
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

    /** Per video in the working set. Guarded by mutex. */
    private class VideoState(durationUs: Long) {
        val planned = CorrectionTimeline.plannedSampleTimes(durationUs)
        val done = HashSet<Long>()
        val timeline = MutableStateFlow(CorrectionTimeline(durationUs))
        val waiters = mutableListOf<CompletableDeferred<CorrectionTimeline>>()
        var anchorUs = 0L
        val complete get() = done.size == planned.size
    }
    private val videos = HashMap<String, VideoState>()
    private var viewed: String? = null // guarded by mutex
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { work() }
    }

    /** Follows the working set of photos only; see [setItems]. */
    suspend fun setPhotos(sources: List<String>) = setItems(sources.map { StoreItem(it) })

    /** Follows the working set: new items are queued in this order, removed ones are cancelled and freed. */
    suspend fun setItems(items: List<StoreItem>) {
        val sources = items.map { it.source }
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
                videos.remove(source)?.waiters?.forEach { it.completeExceptionally(CancellationException("video removed")) }
            }
            if (viewed !in keep) viewed = null
            if (sampler != null) {
                for (item in items) {
                    val d = item.videoDurationUs ?: continue
                    if (item.source !in videos) videos[item.source] = VideoState(d)
                }
            }
            _entries.update { old -> sources.associateWith { old[it] ?: PreviewEntry.Pending } }
            _strengths.update { old -> old.filterKeys { it in keep } }
            running?.let { (source, job) -> if (source in removed) job.cancel() }
        }
        wake.trySend(Unit)
    }

    /** The video's correction over time as analysed so far, or null for a photo or a video without analysis. */
    fun timeline(source: String): StateFlow<CorrectionTimeline>? = videos[source]?.timeline

    /**
     * The video's whole timeline, analysed ahead of other videos and item preparation; waits for the last sample.
     * Throws if the video leaves the working set, or if no frame of it could be read.
     */
    suspend fun completeTimeline(source: String): CorrectionTimeline {
        val waiter = mutex.withLock {
            val v = videos[source] ?: throw IllegalStateException("no timeline for $source")
            if (v.complete) return finished(source, v)
            CompletableDeferred<CorrectionTimeline>().also { v.waiters += it }
        }
        wake.trySend(Unit)
        return waiter.await()
    }

    private fun finished(source: String, v: VideoState): CorrectionTimeline {
        val t = v.timeline.value
        if (t.sampleCount == 0) throw IllegalStateException("no readable frame in $source")
        return t
    }

    /** The viewer shows [source] at [positionUs] (or nothing, for null): its timeline is analysed from there first. */
    suspend fun viewVideo(source: String?, positionUs: Long = 0) {
        mutex.withLock {
            viewed = source?.takeIf { it in videos }
            viewed?.let { videos.getValue(it).anchorUs = positionUs }
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
            val (source, job, targets) = next
            val outcome = runCatching { job.await() }
            mutex.withLock {
                running = null
                finish(source, outcome, targets)
            }
        }
    }

    /**
     * Starts the next job: requests first, then thumbnails whose strength changed, then the exported video's timeline,
     * the viewed video's timeline, prioritized photos not yet prepared, the first pending item in selection order, and
     * the other videos' timelines in selection order.
     */
    private suspend fun nextJob(): Triple<String, Deferred<Result<T>>, List<Long>?>? {
        val entries = _entries.value
        val request = requests.keys.firstOrNull()
        val render = if (request == null) (priority + order).firstOrNull { needsRender(it) } else null
        val timeline = if (request == null && render == null) {
            order.firstOrNull { videos[it]?.let { v -> v.waiters.isNotEmpty() && !v.complete } == true }
                ?: viewed?.takeIf { !videos.getValue(it).complete }
        } else null
        val prepare = if (request == null && render == null && timeline == null) (priority + order).firstOrNull { entries[it] == PreviewEntry.Pending } else null
        val background = if (request == null && render == null && timeline == null && prepare == null) {
            order.firstOrNull { videos[it]?.complete == false }
        } else null
        val source = request ?: render ?: timeline ?: prepare ?: background ?: return null
        val renderer = render?.let { renderers.getValue(it) }
        val strength = strengthOf(source)
        val estimateOnly = request != null && entries[source] is PreviewEntry.Ready
        val targets = (timeline ?: background)?.let { nextTargets(videos.getValue(it)) }
        // A supervisor child: a failed or cancelled photo must not cancel the worker.
        val context = currentCoroutineContext()
        val job = CoroutineScope(context + SupervisorJob(context[Job]) + dispatcher).async {
            when {
                renderer != null -> Result.Rendered(runCatching { renderer.render(strength) }.getOrNull(), strength)
                targets != null -> Result.Sampled(sampler!!.sample(source, targets))
                estimateOnly -> Result.Estimated(preparer.estimate(source))
                else -> Result.Prepared(preparer.prepare(source))
            }
        }
        running = source to job
        return Triple(source, job, targets)
    }

    /**
     * The next samples to analyse: undone planned times from the anchor onwards, then, once those are done, from the start.
     * A video's first job takes one sample only, so playback has its correction after one sample's time.
     */
    private fun nextTargets(v: VideoState): List<Long> {
        val undone = v.planned.filter { it !in v.done }
        val (after, before) = undone.partition { it >= v.anchorUs }
        return (after.ifEmpty { before }).take(if (v.done.isEmpty()) 1 else SAMPLES_PER_JOB)
    }

    private fun needsRender(source: String) =
        source in renderers && shownStrength[source].let { it != null && it != strengthOf(source) }

    private fun finish(source: String, outcome: kotlin.Result<Result<T>>, targets: List<Long>?) {
        if (targets != null) return finishSamples(source, outcome, targets)
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

    private fun finishSamples(source: String, outcome: kotlin.Result<Result<T>>, targets: List<Long>) {
        val v = videos[source] ?: return
        // A failed chunk counts its targets as done without samples, so one unreadable stretch does not stall the video.
        v.done += targets
        (outcome.getOrNull() as? Result.Sampled)?.samples?.let { samples ->
            var t = v.timeline.value
            for ((time, params) in samples) t = t.withSample(time, params)
            v.timeline.value = t
        }
        if (v.complete) {
            v.timeline.value = v.timeline.value.completed()
            val result = runCatching { finished(source, v) }
            v.waiters.forEach { w -> result.fold(w::complete, w::completeExceptionally) }
            v.waiters.clear()
        }
    }

    private sealed interface Result<out T> {
        class Sampled(val samples: List<Pair<Long, CorrectionParams>>) : Result<Nothing>
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
            is Result.Sampled -> error("samples have no correction")
        }

    companion object {
        /** The viewer's photo and its two neighbours, or the photo being exported. */
        const val CORRECTIONS_KEPT = 3

        /** Timeline samples per worker job, so photo work interleaves (design D6). */
        const val SAMPLES_PER_JOB = 10
    }
}
