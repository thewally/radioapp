package com.arjenvdw.radioapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A currently-playing track resolved for a station. */
data class NowPlayingTrack(val artist: String?, val title: String) {
    /** "Artist - Title", or just the title when the artist is unknown. */
    fun label(): String = if (artist.isNullOrBlank()) title else "$artist - $title"
}

/**
 * Resolves "now playing" (artist + song) for stations that do NOT carry usable
 * inline ICY metadata in their audio stream. ICY-carrying streams are handled
 * directly by ExoPlayer's onMetadata callback and never reach here.
 *
 * Two out-of-band sources are covered, detected from the stream URL so no
 * per-station configuration is needed:
 *  - StreamTheWorld / Triton mounts (playerservices.streamtheworld.com): the
 *    public Triton "nowplaying" XML API. The mount name equals the stream's
 *    filename without extension (e.g. RADIO538.mp3 -> mount RADIO538,
 *    SLAM_AAC.aac -> mount SLAM_AAC).
 *  - Qmusic / Joe (DPG) hosts: their public "tracks/plays" JSON API.
 *
 * All network work is blocking and MUST be called off the main thread. Returns
 * null when the station has no external source or the fetch/parse fails.
 */
object NowPlaying {

    private const val TIMEOUT_MS = 8000

    /** True if this station has an external poll source (no usable inline ICY). */
    fun hasPollSource(station: Station): Boolean = sourceFor(station) != null

    /** Fetches the current track for [station], or null. Blocking; call off-main. */
    fun fetch(station: Station): NowPlayingTrack? {
        val src = sourceFor(station) ?: return null
        return try {
            when (src) {
                is Source.Triton -> fetchTriton(src.mount)
                is Source.Qmusic -> fetchQmusic(src.apiBase)
            }
        } catch (e: Exception) {
            null
        }
    }

    private sealed class Source {
        data class Triton(val mount: String) : Source()
        data class Qmusic(val apiBase: String) : Source()
    }

    private fun sourceFor(station: Station): Source? {
        val url = station.streamUrl
        return when {
            "streamtheworld.com" in url ->
                tritonMount(url)?.let { Source.Triton(it) }
            // Qmusic NL: own plays API carries artist + title (its ICY is empty).
            "qmusicnl" in url ->
                Source.Qmusic("https://api.qmusic.nl")
            // Qmusic BE and Joe BE share the qmusicbe CDN host; the mount path
            // distinguishes them and maps to their respective DPG APIs.
            "qmusicbe" in url && "joe" in url ->
                Source.Qmusic("https://api.joe.be")
            "qmusicbe" in url ->
                Source.Qmusic("https://api.qmusic.be")
            else -> null
        }
    }

    /** The Triton mount for a StreamTheWorld URL: last path segment, sans extension. */
    private fun tritonMount(url: String): String? {
        val lastSegment = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (lastSegment.isEmpty()) return null
        return lastSegment.substringBeforeLast('.', lastSegment)
    }

    private fun fetchTriton(mount: String): NowPlayingTrack? {
        val body = httpGet(
            "https://np.tritondigital.com/public/nowplaying" +
                "?mountName=$mount&numberToFetch=1&eventType=track"
        ) ?: return null
        val artist = cdata(body, "track_artist_name")
        val title = cdata(body, "cue_title") ?: return null
        return NowPlayingTrack(artist, title)
    }

    /** Extracts the CDATA of a Triton <property name="..."> element. */
    private fun cdata(xml: String, propertyName: String): String? {
        val marker = "name=\"$propertyName\">"
        val start = xml.indexOf(marker)
        if (start < 0) return null
        val open = xml.indexOf("<![CDATA[", start)
        if (open < 0) return null
        val end = xml.indexOf("]]>", open)
        if (end < 0) return null
        return xml.substring(open + "<![CDATA[".length, end).trim().ifEmpty { null }
    }

    private fun fetchQmusic(apiBase: String): NowPlayingTrack? {
        val body = httpGet("$apiBase/2.4/tracks/plays?limit=1") ?: return null
        val tracks = JSONObject(body).optJSONArray("played_tracks") ?: return null
        if (tracks.length() == 0) return null
        val track = tracks.getJSONObject(0)
        val title = track.optString("title").ifEmpty { return null }
        val artist = track.optJSONObject("artist")?.optString("name")?.ifEmpty { null }
        return NowPlayingTrack(artist, title)
    }

    private fun httpGet(spec: String): String? {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RadioApp/1.0 (Android)")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
