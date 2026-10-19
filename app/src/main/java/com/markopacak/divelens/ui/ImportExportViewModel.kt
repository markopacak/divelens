package com.markopacak.divelens.ui

import androidx.lifecycle.ViewModel

/**
 * The screen's view of the app-wide [WorkingSession] (add-video-processing D8). It holds no state of its own, so the
 * working set and a running batch survive the view model being cleared.
 */
class ImportExportViewModel(val session: WorkingSession) : ViewModel() {
    val state = session.state
    val previews = session.previews
    val strengths = session.strengths
    val defaultStrength = session.defaultStrength
    val adjustable get() = session.adjustable
    val viewer get() = session.viewer
    val videos get() = session.videos

    fun onPicked(uris: List<android.net.Uri>) = session.onPicked(uris)
    fun openViewer(index: Int) = session.openViewer(index)
    fun closeViewer() = session.closeViewer()
    fun onViewerPage(index: Int) = session.onViewerPage(index)
    fun setStrength(source: String, strength: Float) = session.setStrength(source, strength)
    fun applyStrengthToAll(strength: Float) = session.applyStrengthToAll(strength)
    fun dismissRejections() = session.dismissRejections()
    fun remove(source: String) = session.remove(source)
    fun clear() = session.clear()
    fun export() = session.export()
    fun cancel() = session.cancel()
    fun dismissSummary() = session.dismissSummary()
}
