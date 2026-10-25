package com.markopacak.divelens.ui.help

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The intents behind Send feedback and the links (add-help-and-legal D3, task 3.3). */
class HelpIntentsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val started = mutableListOf<Intent>()

    @Test fun feedbackIsAnEmailDraftWithoutAttachment() {
        val actions = HelpIntents.actions(context, "1.0", showTipsAgain = {}, start = { started += it })
        assertTrue(actions.sendFeedback())
        val intent = started.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("mailto:dev.markopacak@gmail.com"), intent.data)
        assertArrayEquals(arrayOf("dev.markopacak@gmail.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("DiveLens feedback", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(
            FeedbackMessage.body("1.0", Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL),
            intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertNull("no attachment", intent.extras?.get(Intent.EXTRA_STREAM))
        assertNull(intent.clipData)
    }

    @Test fun linksOpenInTheBrowser() {
        val actions = HelpIntents.actions(context, "1.0", showTipsAgain = {}, start = { started += it })
        assertTrue(actions.openUrl(Links.ISSUES))
        assertTrue(actions.openUrl(Links.REPOSITORY))
        assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_VIEW), started.map { it.action })
        assertEquals(
            listOf("https://github.com/markopacak/divelens/issues", "https://github.com/markopacak/divelens"),
            started.map { it.dataString },
        )
    }

    @Test fun noMatchingAppReportsFalse() {
        val actions = HelpIntents.actions(context, "1.0", showTipsAgain = {}, start = { throw ActivityNotFoundException() })
        assertFalse(actions.sendFeedback())
        assertFalse(actions.openUrl(Links.ISSUES))
    }

    @Test fun theInstalledVersionIsRead() {
        assertEquals(context.packageManager.getPackageInfo(context.packageName, 0).versionName, HelpIntents.appVersion(context))
    }
}
