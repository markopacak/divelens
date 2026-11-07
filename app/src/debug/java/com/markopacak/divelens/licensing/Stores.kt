package com.markopacak.divelens.licensing

import android.content.Context

/** The debug build's store: the fake store, so anyone can build and try purchases without Google Play (D1, D10). */
object Stores {
    fun create(context: Context): Store =
        FakeStore(context.applicationContext.getSharedPreferences(FakeStore.PREFS, Context.MODE_PRIVATE))
}
