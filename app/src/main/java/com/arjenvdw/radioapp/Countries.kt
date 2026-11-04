package com.arjenvdw.radioapp

/** The country filters shown as circles above the station list, in order. */
object Countries {
    val ORDER = listOf("nl", "be", "fr", "de", "at", "it")

    private val NAMES = mapOf(
        "nl" to "Nederland", "be" to "België", "fr" to "Frankrijk",
        "de" to "Duitsland", "at" to "Oostenrijk", "it" to "Italië",
    )

    /** Human-readable country name for a code. */
    fun name(code: String): String = NAMES[code] ?: code.uppercase()

    /** Stations for a country: NL keeps its full list; others show their top 5. */
    fun stationsFor(code: String): List<Station> {
        val list = Stations.ALL.filter { it.country == code }
        return if (code == "nl") list else list.take(5)
    }

    /** Flag emoji for an ISO 3166-1 alpha-2 code (e.g. "nl" -> 🇳🇱), or "" if invalid. */
    fun flagEmoji(code: String): String {
        val cc = code.trim().uppercase()
        if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return ""
        val base = 0x1F1E6 // regional indicator symbol letter A
        return buildString { for (c in cc) appendCodePoint(base + (c - 'A')) }
    }

    /** Countries that actually have at least one station, keeping ORDER. */
    fun withStations(): List<String> =
        ORDER.filter { cc -> Stations.ALL.any { it.country == cc } }
}
