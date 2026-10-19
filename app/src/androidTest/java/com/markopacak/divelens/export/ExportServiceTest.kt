package com.markopacak.divelens.export

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.markopacak.divelens.TestMedia
import com.markopacak.divelens.batch.BatchExporter
import com.markopacak.divelens.batch.ExportController
import com.markopacak.divelens.batch.ExportProgress
import com.markopacak.divelens.media.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The export service (add-video-processing D8, task 7.2) against a test controller. */
@UnstableApi
class ExportServiceTest {
    private val context = TestMedia.context
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val scope = MainScope()
    private val items = (1..3).map { MediaItem.Photo("content://p/$it", "IMG_$it.jpg", "image/jpeg", 10, 10) }
    private val gates = items.associate { it.source to CompletableDeferred<Unit>() }
    private val controller = ExportController(BatchExporter { gates.getValue(it.source).await(); com.markopacak.divelens.export.ItemResult.Exported("out") }, scope)

    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
            // Some devices (MIUI/HyperOS) refuse this; the tests then check that the batch runs without notifications.
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        println("ExportServiceTest: notifications allowed = $notificationsAllowed")
        notifications.cancelAll()
        ExportService.testController = controller
        TestMedia.deleteAll()
    }

    @After fun tearDown() {
        controller.cancel()
        waitFor("service stopped") { !serviceRunning() }
        ExportService.testController = null
        notifications.cancelAll()
        scope.cancel()
        TestMedia.deleteAll()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(50)
        }
    }

    private val notificationsAllowed get() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun active(id: Int) = notifications.activeNotifications.any { it.id == id }

    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == ExportService::class.java.name && it.service.packageName == context.packageName }

    private fun startBatch() = onMain {
        controller.start(items)
        ExportService.start(context)
    }

    @Test fun showsProgressThenASummaryAndStops() {
        startBatch()
        waitFor("service running") { serviceRunning() }
        if (notificationsAllowed) waitFor("progress notification") { active(ExportService.PROGRESS_ID) }
        onMain { gates.getValue(items[0].source).complete(Unit) }
        waitFor("progress 1 of 3") { controller.progress.value == ExportProgress.Running(1, 3) }
        onMain { gates.values.forEach { it.complete(Unit) } }
        waitFor("batch finished") { controller.progress.value == ExportProgress.Finished(3, 3, cancelled = false) }
        waitFor("service stopped") { !serviceRunning() }
        if (notificationsAllowed) {
            waitFor("summary notification") { active(ExportService.SUMMARY_ID) }
            assertTrue("progress notification removed", !active(ExportService.PROGRESS_ID))
            val summary = notifications.activeNotifications.single { it.id == ExportService.SUMMARY_ID }.notification
            assertEquals("3 items saved to the gallery.", summary.extras.getCharSequence("android.text").toString())
        } else {
            assertEquals("no notifications without the permission", 0, notifications.activeNotifications.count { it.id == ExportService.SUMMARY_ID })
        }
    }

    @Test fun cancelFromTheNotificationCancelsTheBatch() {
        startBatch()
        waitFor("service running") { serviceRunning() }
        // What the notification's Cancel action sends.
        context.startService(ExportService.cancelIntent(context))
        waitFor("batch cancelled") { controller.progress.value == ExportProgress.Finished(0, 3, cancelled = true) }
        waitFor("service stopped") { !serviceRunning() }
        if (notificationsAllowed) {
            waitFor("summary notification") { active(ExportService.SUMMARY_ID) }
            val summary = notifications.activeNotifications.single { it.id == ExportService.SUMMARY_ID }.notification
            assertEquals("Export cancelled", summary.extras.getCharSequence("android.title").toString())
        }
    }

    @Test fun stalePendingRowsAreRemovedAtStartUp() {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun insert(name: String, pending: Boolean) = TestMedia.resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${TestMedia.TEST_ALBUM}")
            put(MediaStore.Images.Media.IS_PENDING, if (pending) 1 else 0)
        })!!.also { uri -> TestMedia.resolver.openOutputStream(uri)!!.use { it.write(ByteArray(16)) } }
        val stale = insert("stale.jpg", pending = true)
        val done = insert("done.jpg", pending = false)
        assertEquals(1, PendingRows.removeStale(TestMedia.resolver, TestMedia.TEST_ALBUM))
        assertEquals(listOf(done), TestMedia.exportedRows())
        assertTrue(stale !in TestMedia.exportedRows())
    }
}
