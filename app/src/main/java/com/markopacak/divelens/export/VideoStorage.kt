package com.markopacak.divelens.export

/** The storage check before a video export (add-video-processing D7, import-export "Storage check before a video"). */
object VideoStorage {
    /** Safety margin on top of the expected size. */
    const val MARGIN_BYTES = 200L * 1024 * 1024

    /**
     * Expected size of the export: the original's bitrate over its duration plus 10 %, or the original's file size plus
     * 10 % when that is larger or the bitrate is unknown (audio and container overhead count there too).
     */
    fun expectedBytes(bitrate: Int, durationUs: Long, fileSize: Long?): Long {
        val fromBitrate = if (bitrate > 0) (bitrate.toDouble() * durationUs / 1_000_000 / 8).toLong() else 0L
        return (maxOf(fromBitrate, fileSize ?: 0L) * 1.1).toLong()
    }

    fun fits(availableBytes: Long, bitrate: Int, durationUs: Long, fileSize: Long?): Boolean =
        availableBytes >= expectedBytes(bitrate, durationUs, fileSize) + MARGIN_BYTES
}
