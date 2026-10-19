package com.markopacak.divelens.batch

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.markopacak.divelens.export.ItemResult
import com.markopacak.divelens.importer.ImportResult
import com.markopacak.divelens.media.ItemStatus
import com.markopacak.divelens.media.MediaItem
import com.markopacak.divelens.ui.BatchUi
import com.markopacak.divelens.ui.ImportExportViewModel
import com.markopacak.divelens.ui.WorkingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The batch in the app-wide controller (add-video-processing D8, task 7.1). */
class ExportControllerTest {
    private val items = (1..3).map { MediaItem.Photo("content://p/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
    private val gates = items.associate { it.source to CompletableDeferred<Unit>() }

    private fun TestScope.controller() = ExportController(BatchExporter { gates.getValue(it.source).await(); ItemResult.Exported("out") }, backgroundScope)

    @Test fun progressRunsAndFinishes() = runTest {
        val controller = controller()
        assertTrue(controller.start(items))
        runCurrent()
        assertEquals(ExportProgress.Running(0, 3), controller.progress.value)
        assertFalse("a second batch does not start", controller.start(items))
        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(ExportProgress.Finished(3, 3, cancelled = false), controller.progress.value)
        controller.acknowledge()
        assertEquals(ExportProgress.Idle, controller.progress.value)
    }

    @Test fun cancelEndsTheBatchAsCancelled() = runTest {
        val controller = controller()
        var ended: Boolean? = null
        controller.start(items, onEnd = { ended = it })
        gates.getValue(items[0].source).complete(Unit)
        runCurrent()
        assertEquals(ExportProgress.Running(1, 3), controller.progress.value)
        controller.cancel()
        runCurrent()
        assertEquals(ExportProgress.Finished(1, 3, cancelled = true), controller.progress.value)
        assertEquals(true, ended)
    }

    @Test fun theBatchSurvivesClearingTheViewModel() = runTest {
        val controller = controller()
        val session = WorkingSession(backgroundScope, { uris -> ImportResult(items.take(uris.size), emptyList()) }, controller)
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ImportExportViewModel(session) } })[ImportExportViewModel::class]
        vm.onPicked(items.map { android.net.Uri.EMPTY })
        runCurrent()
        vm.export()
        runCurrent()
        store.clear() // the activity is gone
        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(ExportProgress.Finished(3, 3, cancelled = false), controller.progress.value)
        val state = session.state.value
        assertEquals(List(3) { ItemStatus.Exported }, state.workingSet.items.map { it.status })
        assertEquals(BatchUi.Finished(3, emptyList(), cancelled = false), state.batch)
        // A new view model (the activity is back) shows the same session.
        assertEquals(state, ImportExportViewModel(session).state.value)
    }
}
