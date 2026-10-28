package com.markopacak.divelens

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.batch.ExportController
import com.markopacak.divelens.batch.ExportProgress
import com.markopacak.divelens.correction.AndroidPhotoPreparer
import com.markopacak.divelens.correction.AndroidTimelineSampler
import com.markopacak.divelens.correction.CorrectionPhotoProcessor
import com.markopacak.divelens.correction.GpuViewerRenderer
import com.markopacak.divelens.correction.PhotoPreviewStore
import com.markopacak.divelens.export.AlwaysAllowExportGate
import com.markopacak.divelens.export.ExportItemUseCase
import com.markopacak.divelens.export.ExportPrefs
import com.markopacak.divelens.export.ExportService
import com.markopacak.divelens.export.PendingRows
import com.markopacak.divelens.export.MediaStorePhotoWriter
import com.markopacak.divelens.export.MediaStoreVideoWriter
import com.markopacak.divelens.importer.MediaImporter
import com.markopacak.divelens.ui.WorkingSession
import com.markopacak.divelens.ui.help.HelpPrefs
import com.markopacak.divelens.watermark.PhotoWatermark
import com.markopacak.divelens.watermark.VideoWatermark
import com.markopacak.divelens.watermark.WatermarkRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app-wide objects (add-video-processing D8), created once per process by [DiveLensApp]: the preview store with its
 * strengths and timelines, the importer, the writers and the [ExportController], tied together in the [session].
 * Manual wiring. A later change swaps the gate (licensing).
 */
@UnstableApi
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val watermark = WatermarkRenderer(app.assets)

    // Video samples also keep their watermark corner, for the clip's light or dark mark (add-branding-and-watermark D6).
    val store = PhotoPreviewStore(
        AndroidPhotoPreparer(resolver), CoroutineScope(SupervisorJob()),
        sampler = AndroidTimelineSampler(resolver, markAspect = watermark.aspect()),
    )

    private val flags = HelpPrefs.flags(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** The export settings: the watermark switch (add-branding-and-watermark D8). */
    val exportPrefs = ExportPrefs(flags)

    // The exports apply the correction the preview shows, at the item's strength, then the watermark if it is on.
    private val photoWriter = MediaStorePhotoWriter(resolver, app.cacheDir, CorrectionPhotoProcessor(store::correction, store::strengthOf), watermark = PhotoWatermark(watermark))
    private val videoWriter = MediaStoreVideoWriter(app, store::completeTimeline, store::strengthOf, watermark = VideoWatermark(watermark))
    private val exportItem = ExportItemUseCase(AlwaysAllowExportGate, photoWriter, videoWriter)

    val controller = ExportController(BatchExporter { item, options -> exportItem(item, options) }, scope, exportPrefs::options)

    /** The one-time gesture tips already shown (add-help-and-legal D6). */
    val help = HelpPrefs(flags)

    val session = WorkingSession(scope, MediaImporter(resolver)::import, controller, store, GpuViewerRenderer(resolver, store::correction))

    init {
        // A batch that starts brings up the export service, which follows it until it ends.
        scope.launch { controller.progress.collect { if (it is ExportProgress.Running && it.done == 0) ExportService.start(app) } }
        // Exports left pending by a process that died mid-export.
        scope.launch(Dispatchers.IO) { PendingRows.removeStale(resolver, Branding.EXPORT_ALBUM) }
    }

    companion object {
        /** The app's one preferences file. */
        const val PREFS = "divelens"
    }
}
