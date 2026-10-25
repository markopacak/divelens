package com.markopacak.divelens.ui.help

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri

/** The system intents behind Send feedback and the web links (add-help-and-legal D3). */
object HelpIntents {
    /** A draft to the developer in the user's email app: no attachment, nothing sent by DiveLens. */
    fun feedback(appVersion: String): Intent = Intent(Intent.ACTION_SENDTO, "mailto:${Links.FEEDBACK_EMAIL}".toUri())
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(Links.FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, FeedbackMessage.SUBJECT)
        .putExtra(Intent.EXTRA_TEXT, FeedbackMessage.body(appVersion, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL))

    fun view(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())

    /** The actions of the Settings & help screen; [start] is replaceable for tests. */
    fun actions(
        context: Context,
        appVersion: String,
        showTipsAgain: () -> Unit,
        start: (Intent) -> Unit = { context.startActivity(it) },
    ): HelpActions {
        fun tryStart(intent: Intent) = try { start(intent); true } catch (_: ActivityNotFoundException) { false }
        return HelpActions(
            sendFeedback = { tryStart(feedback(appVersion)) },
            openUrl = { tryStart(view(it)) },
            showTipsAgain = showTipsAgain,
        )
    }

    /** The installed version name, e.g. "1.0". */
    fun appVersion(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
