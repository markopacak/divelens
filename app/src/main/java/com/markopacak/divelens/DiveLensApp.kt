package com.markopacak.divelens

import android.app.Application
import androidx.media3.common.util.UnstableApi

@UnstableApi
class DiveLensApp : Application() {
    /** The app-wide objects; see [AppGraph]. */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph // created at start-up, so stale pending exports are removed before anything else runs
    }
}
