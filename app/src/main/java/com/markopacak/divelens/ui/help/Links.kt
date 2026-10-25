package com.markopacak.divelens.ui.help

/** The addresses the app links to (add-help-and-legal D3). Keep them in one place, like [com.markopacak.divelens.Branding]. */
object Links {
    const val FEEDBACK_EMAIL = "dev.markopacak@gmail.com"
    const val REPOSITORY = "https://github.com/markopacak/divelens"
    const val ISSUES = "$REPOSITORY/issues"
    const val DEVELOPER = "Marko Pacak"
}

/** The feedback email's subject and pre-filled body: the app version, Android version and phone model, nothing else. */
object FeedbackMessage {
    const val SUBJECT = "DiveLens feedback"

    fun body(appVersion: String, androidRelease: String, sdkInt: Int, manufacturer: String, model: String): String {
        val phone = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return "\n\n---\nDiveLens $appVersion\nAndroid $androidRelease (API $sdkInt)\nPhone: $phone\n"
    }
}
