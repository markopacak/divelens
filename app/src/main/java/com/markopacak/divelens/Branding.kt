package com.markopacak.divelens

/**
 * Single source for the app name used outside string resources.
 * "DiveLens" is a working name; keep this in sync with `app_name` in strings.xml.
 */
object Branding {
    const val APP_NAME = "DiveLens"

    /** Gallery album that exports are saved in, under Pictures/. */
    const val EXPORT_ALBUM = APP_NAME

    /** Prefix of exported file names, e.g. DiveLens_20260714_103205.jpg. */
    const val EXPORT_PREFIX = APP_NAME
}
