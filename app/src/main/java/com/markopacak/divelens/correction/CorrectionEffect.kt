package com.markopacak.divelens.correction

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.markopacak.divelens.analysis.CorrectionParams
import com.markopacak.divelens.analysis.CorrectionTimeline
import com.markopacak.divelens.analysis.EasedTimeline

/**
 * The video correction as a Media3 effect (add-video-processing D3), shared by the preview player and the export: for
 * each frame it asks [paramsAt] for the correction at the frame's presentation time, then runs [GpuFrameCorrection].
 * [paramsAt] is called on Media3's GL thread for every frame, so a live strength or a timeline that grows while the
 * video plays applies without re-preparing anything.
 */
@UnstableApi
class CorrectionEffect(private val paramsAt: (presentationTimeUs: Long) -> CorrectionParams) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr, paramsAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false

    private class Program(useHdr: Boolean, private val paramsAt: (Long) -> CorrectionParams) :
        BaseGlShaderProgram(useHdr, 1) {
        private val gpu = GpuFrameCorrection()
        private var width = 0
        private var height = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth; height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val params = paramsAt(presentationTimeUs)
                gpu.draw(inputTexId, width, height, params, params.strength)
            } catch (e: RuntimeException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            gpu.close()
        }
    }

    companion object {
        /** For the export: the complete [timeline] at a fixed [strength]. */
        fun forExport(timeline: CorrectionTimeline, strength: Float) = CorrectionEffect { t -> timeline.paramsAt(t, strength) }

        /** For playback: follows [eased] (fed with new timelines as the analysis runs) at the live [strength]. */
        fun forPlayback(eased: EasedTimeline, strength: () -> Float) = CorrectionEffect { t -> eased.paramsAt(t, strength()) }
    }
}
