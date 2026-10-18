package com.markopacak.divelens.correction

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** EGL contexts are bound to a thread, so all GPU work (export and viewer sessions) runs on this one. */
object GlThread {
    val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "divelens-gl").apply { isDaemon = true } }
        .asCoroutineDispatcher()
}
