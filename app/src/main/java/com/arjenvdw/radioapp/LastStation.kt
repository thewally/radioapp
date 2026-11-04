package com.arjenvdw.radioapp

import android.content.Context

/** Remembers the last played station so playback resumes on it. */
object LastStation {
    private const val PREFS = "radio_prefs"
    private const val KEY_LAST_MEDIA_ID = "last_media_id"

    fun save(context: Context, mediaId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MEDIA_ID, mediaId)
            .apply()
    }

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MEDIA_ID, null)
}
