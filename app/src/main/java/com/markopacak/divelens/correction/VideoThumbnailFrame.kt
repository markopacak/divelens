package com.markopacak.divelens.correction

/**
 * Which frame of a video its thumbnail shows (add-video-processing D5, spec "Video thumbnail"): the frame at 1 s, or
 * the first frame for clips shorter than 2 s; when it is nearly black, the frames at 2, 3 and 4 s in turn.
 */
object VideoThumbnailFrame {
    /** Mean grey (0..1) below which a frame counts as nearly black. */
    const val NEARLY_BLACK = 0.06

    /** Times to try, in order. */
    fun candidateTimesUs(durationUs: Long): List<Long> =
        if (durationUs < 2_000_000L) listOf(0L) else (1..4).map { it * 1_000_000L }.filter { it < durationUs }

    /**
     * The first frame of [candidateTimesUs] that is not nearly black, or the first frame that could be read when all are.
     * [frameAt] returns null for a frame that cannot be read; [release] gets every frame read but not chosen.
     */
    fun <F : Any> choose(durationUs: Long, frameAt: (Long) -> F?, meanGrey: (F) -> Double, release: (F) -> Unit = {}): Pair<Long, F>? {
        var fallback: Pair<Long, F>? = null
        for (t in candidateTimesUs(durationUs)) {
            val frame = frameAt(t) ?: continue
            if (meanGrey(frame) >= NEARLY_BLACK) {
                fallback?.let { release(it.second) }
                return t to frame
            }
            if (fallback == null) fallback = t to frame else release(frame)
        }
        return fallback
    }
}
