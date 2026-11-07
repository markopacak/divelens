package com.markopacak.divelens.licensing

import com.markopacak.divelens.ui.help.HelpPrefs

/** [HelpPrefs.Flags] in memory, with numbers, for the licensing tests. */
class MapFlags : HelpPrefs.Flags {
    val booleans = mutableMapOf<String, Boolean>()
    val longs = mutableMapOf<String, Long>()
    override fun get(key: String, default: Boolean) = booleans[key] ?: default
    override fun set(key: String, value: Boolean) { booleans[key] = value }
    override fun getLong(key: String, default: Long) = longs[key] ?: default
    override fun setLong(key: String, value: Long) { longs[key] = value }
}
