package com.arjenvdw.radioapp

import android.content.Context
import android.net.Uri

/** One playable radio station. */
data class Station(
    val key: String,
    val name: String,
    val country: String,
    val streamUrl: String,
    val mediaId: String,
    val logoResName: String,
) {
    /** Resolves this station's logo drawable id in the given context. */
    fun logoResId(context: Context): Int =
        context.resources.getIdentifier(logoResName, "drawable", context.packageName)

    /**
     * Resource uri for the artwork shown by the media session (notification,
     * lock screen, Android Auto): just the plain station logo. The country is
     * conveyed by the country-folder navigation in Auto, not a badge on the logo.
     *
     * Uses the NAME-based form (android.resource://pkg/drawable/ic_logo_<key>)
     * rather than a numeric resource id: numeric ids are reassigned whenever
     * resources are added or removed, and Android Auto caches artwork by uri
     * string, so a numeric uri could resolve to a different image after a
     * rebuild (e.g. NPO Radio 2 showing Radio 538's artwork). The name-based
     * uri is stable and unique per station.
     */
    fun artworkUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/drawable/$logoResName")

    /** content:// style resource uri for an arbitrary drawable (e.g. a flag). */
    companion object {
        fun drawableUri(context: Context, resName: String): Uri =
            Uri.parse("android.resource://${context.packageName}/drawable/$resName")
    }

    /**
     * Flag emoji derived from the ISO 3166-1 alpha-2 [country] code (e.g. "nl"
     * -> 🇳🇱). Works for any country without needing a per-country asset, so a
     * foreign station only needs its `country` set in stations.json. Returns
     * an empty string if the code isn't a valid two-letter code.
     */
    fun flagEmoji(): String = Countries.flagEmoji(country)
}
