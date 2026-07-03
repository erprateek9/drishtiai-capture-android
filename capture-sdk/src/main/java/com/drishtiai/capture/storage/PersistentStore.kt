package com.drishtiai.capture.storage

import android.content.Context

interface PersistentStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** SharedPreferences-backed store - mirrors sdk-web's LocalStorageStore. */
class SharedPreferencesStore(context: Context) : PersistentStore {
    private val prefs = context.getSharedPreferences("drishtiai_capture", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
