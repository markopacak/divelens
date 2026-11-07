package com.markopacak.divelens.licensing

import android.content.Context

/** The release build's store: Google Play Billing (add-free-limits-and-purchase D1, D2). */
object Stores {
    fun create(context: Context): Store = PlayStore(context)
}
