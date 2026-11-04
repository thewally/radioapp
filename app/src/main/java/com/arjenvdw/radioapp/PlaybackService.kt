package com.arjenvdw.radioapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import java.util.concurrent.Executors
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The single media service backing both the in-app station list and Android
 * Auto. It exposes every station in [Stations] as a browsable list and plays
 * exactly one at a time through a single ExoPlayer. Switching stations swaps
 * the current media item within this one session — there are no sibling apps
 * or cross-app signalling, which is what makes it behave correctly in the car.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks whether Android Auto is currently projecting to a car head unit.
    // Auto-start only happens while this is CONNECTION_TYPE_PROJECTION, so a
    // plain Bluetooth device (headphones, a speaker) never triggers the radio.
    private var carConnectionType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    private lateinit var carConnection: CarConnection
    private val carConnectionObserver = Observer<Int> { type ->
        val wasProjecting = carConnectionType == CarConnection.CONNECTION_TYPE_PROJECTION
        carConnectionType = type
        // Android Auto just started projecting (we entered the car): begin the
        // route-aware autoplay. Only on the transition, so manually stopping the
        // radio mid-drive doesn't immediately restart it.
        if (!wasProjecting && type == CarConnection.CONNECTION_TYPE_PROJECTION) {
            startAutoplayWhenRouted(0)
        }
    }

    // Single background thread for out-of-band "now playing" fetches (Triton /
    // Qmusic APIs). ICY-carrying streams never touch this — ExoPlayer decodes
    // their metadata inline and delivers it on the main thread via onMetadata.
    private val nowPlayingExecutor = Executors.newSingleThreadExecutor()

    // When the audio output route is pulled away (car powers off and its
    // Bluetooth disconnects, headphones unplugged, …) Android broadcasts
    // ACTION_AUDIO_BECOMING_NOISY just before it would reroute to the phone
    // speaker. Turn the radio fully off instead of continuing on the phone.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (::player.isInitialized) {
                    player.pause()
                    player.stop()
                }
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioApp/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { LastStation.save(this@PlaybackService, it) }
                // New station: any external (polled) source should refresh
                // immediately rather than waiting a full poll interval. ICY
                // stations refresh themselves via onMetadata as tags arrive.
                pollNowPlayingOnce()
            }

            // Inline ICY metadata ("StreamTitle") for Icecast/Shoutcast streams,
            // decoded by ExoPlayer and delivered here on the main thread. Most
            // stations carry the current song this way (e.g. "Artist - Title").
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) {
                        applyIcyTitle(entry.title)
                    }
                }
            }
        })

        startNowPlayingPolling()

        // Observe Android Auto projection state. observeForever must run on the
        // main thread (we're in onCreate, which does). The observer fires with
        // the current value immediately, so if AA is already projecting when the
        // service is created (AA binds this MediaLibraryService on connect) the
        // radio auto-starts.
        carConnection = CarConnection(this)
        carConnection.type.observeForever(carConnectionObserver)

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = sessionActivityIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .apply { sessionActivityPendingIntent?.let(::setSessionActivity) }
            .build()

        // Preload the last played station (default: first) so the notification
        // and Android Auto "now playing" have content, without auto-starting
        // audio. Clients (the app UI, Auto, Assistant) trigger play.
        val initial = Stations.byMediaId(LastStation.load(this)) ?: Stations.ALL.first()
        player.setMediaItem(mediaItemFor(initial))
        player.prepare()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Autoplay trigger from BtAutoPlayReceiver when the car's Bluetooth
        // connects: resume the last station once audio actually routes to the
        // Bluetooth output (see startAutoplayWhenRouted).
        if (intent?.action == ACTION_AUTOPLAY) {
            // Only honour an explicit autoplay request while Android Auto is
            // projecting; otherwise a stray trigger could start audio outside
            // the car.
            mainHandler.post {
                if (carConnectionType == CarConnection.CONNECTION_TYPE_PROJECTION) {
                    startAutoplayWhenRouted(0)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Plays the last station, but *only* once media audio is actually routed to
     * a Bluetooth output — i.e. the phone is connected to the car's Bluetooth,
     * not just projecting Android Auto. This is the second half of the "am I
     * really in the car?" test: Android Auto projection alone isn't enough,
     * because when stepping out of a car parked near the house the projection
     * state can briefly flicker back on while the car's Bluetooth has already
     * dropped. Requiring the audio route to be Bluetooth means that case never
     * starts the radio on the phone speaker.
     *
     * The A2DP-connected transition also lags the projection signal, so we can't
     * check once and give up: isBluetoothA2dpOn() only flips true after the
     * system finishes switching the media route to the car. Poll briefly (well
     * within the ~5s foreground-service start window). If it becomes Bluetooth,
     * play; if it never does within the window, give up *without* playing, so we
     * never leak audio through the phone speaker.
     */
    private fun startAutoplayWhenRouted(attempt: Int) {
        if (player.isPlaying) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // isBluetoothA2dpOn() reflects whether media audio is *actively* routed
        // to A2DP right now (not merely that a BT output exists), so it only
        // becomes true once the route has finished switching to the car.
        @Suppress("DEPRECATION")
        val btRouted = audioManager.isBluetoothA2dpOn
        when {
            btRouted -> {
                if (player.currentMediaItem == null) {
                    val station = Stations.byMediaId(LastStation.load(this)) ?: Stations.ALL.first()
                    player.setMediaItem(mediaItemFor(station))
                }
                player.prepare()
                player.play()
            }
            attempt < MAX_ROUTE_WAIT_ATTEMPTS -> {
                mainHandler.postDelayed({ startAutoplayWhenRouted(attempt + 1) }, ROUTE_WAIT_INTERVAL_MS)
            }
            // Window elapsed and audio never routed to Bluetooth: we're not
            // actually connected to the car's Bluetooth, so don't auto-start.
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaSession
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        nowPlayingExecutor.shutdownNow()
        carConnection.type.removeObserver(carConnectionObserver)
        unregisterReceiver(becomingNoisyReceiver)
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    /** Builds a fully playable media item (stream uri + logo artwork) for a station. */
    private fun mediaItemFor(station: Station): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setStation(station.name)
            .setArtworkUri(station.artworkUri(this))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

        return MediaItem.Builder()
            .setMediaId(station.mediaId)
            .setUri(station.streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    // ---- Now playing (current song) -------------------------------------

    /**
     * Repeating poll for stations whose current song is only available from an
     * out-of-band API (StreamTheWorld/Triton, Qmusic/Joe). Runs on the main
     * thread but hands the blocking fetch to [nowPlayingExecutor]. It is a
     * no-op for ICY stations (which update via onMetadata) and while paused.
     */
    private fun startNowPlayingPolling() {
        val tick = object : Runnable {
            override fun run() {
                pollNowPlayingOnce()
                mainHandler.postDelayed(this, NOW_PLAYING_POLL_MS)
            }
        }
        mainHandler.postDelayed(tick, NOW_PLAYING_POLL_MS)
    }

    /** Fetches the current song for the playing station once, if it has an API source. */
    private fun pollNowPlayingOnce() {
        if (!player.isPlaying) return
        val station = Stations.byMediaId(player.currentMediaItem?.mediaId) ?: return
        if (!NowPlaying.hasPollSource(station)) return
        nowPlayingExecutor.execute {
            val np = NowPlaying.fetch(station) ?: return@execute
            mainHandler.post { applyNowPlaying(station.mediaId, np.artist, np.title) }
        }
    }

    /** Parses an ICY StreamTitle ("Artist - Title" or "Title") and applies it. */
    private fun applyIcyTitle(streamTitle: String?) {
        val raw = streamTitle?.trim().orEmpty()
        if (raw.isEmpty()) return
        val station = Stations.byMediaId(player.currentMediaItem?.mediaId) ?: return
        // Ignore ICY on stations we poll instead (their StreamTitle is empty or
        // just the station name), so the API result isn't clobbered.
        if (NowPlaying.hasPollSource(station)) return
        val (artist, title) = raw.split(" - ", limit = 2).let {
            if (it.size == 2) it[0].trim() to it[1].trim() else null to raw
        }
        applyNowPlaying(station.mediaId, artist, title)
    }

    /**
     * Updates the currently-playing item's metadata with the live song so it
     * shows in the notification, lock screen and Android Auto. Replacing the
     * media item with the same uri only swaps metadata; ExoPlayer does not
     * re-buffer, so audio is uninterrupted. Guarded by [mediaId] so a slow
     * fetch that resolves after the user switched stations is discarded.
     */
    private fun applyNowPlaying(mediaId: String, artist: String?, title: String?) {
        val current = player.currentMediaItem ?: return
        if (current.mediaId != mediaId) return
        val station = Stations.byMediaId(mediaId) ?: return
        val song = title?.trim().orEmpty()
        val updated = MediaMetadata.Builder()
            // Title carries the song; the station stays visible via setStation
            // (and as the artwork). Empty song falls back to the station name.
            .setTitle(if (song.isEmpty()) station.name else song)
            .setArtist(artist?.trim()?.ifEmpty { null })
            .setStation(station.name)
            .setArtworkUri(station.artworkUri(this))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        val newItem = current.buildUpon().setMediaMetadata(updated).build()
        player.replaceMediaItem(player.currentMediaItemIndex, newItem)
    }

    private val flagDataCache = mutableMapOf<String, ByteArray>()

    /** Raw PNG bytes of a country flag, embedded directly in browse items so
     *  Android Auto renders them reliably (it does not consistently load
     *  android.resource:// uris for browse thumbnails). */
    private fun flagData(cc: String): ByteArray? {
        flagDataCache[cc]?.let { return it }
        val resId = resources.getIdentifier("ic_cflag_$cc", "drawable", packageName)
        if (resId == 0) return null
        return try {
            resources.openRawResource(resId).use { it.readBytes() }.also { flagDataCache[cc] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Content-style hint: browsable items as large grid tiles (flag with name
     *  centered underneath), playable items as a list. Must be sent on every
     *  node whose children should render this way, not just the root. */
    private fun gridStyleExtras(): Bundle = Bundle().apply {
        putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2) // grid
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1) // list
    }

    /** The single root-level "Landen" folder that holds the country tiles. */
    private fun countriesFolderItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("Landen")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            .build()
        return MediaItem.Builder()
            .setMediaId(COUNTRIES_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    /** A browsable country "folder" shown in Android Auto as a flag tile (no label). */
    private fun countryFolderItem(cc: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            // No title: the flag alone identifies the country (no name label).
            // Android Auto only tolerates an empty title here when the tile has
            // loadable artwork — hence the content:// flag uri below, which Auto
            // loads reliably (unlike android.resource://) even for the root tabs.
            .setTitle(Countries.name(cc))
            .setArtworkUri(FlagProvider.uriFor(cc))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            .apply { flagData(cc)?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        return MediaItem.Builder()
            .setMediaId("$COUNTRY_PREFIX$cc")
            .setMediaMetadata(metadata)
            .build()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Android Auto derives its "queue" button from whether the session
            // publishes a non-empty timeline, which Media3 always populates from
            // the current media item. We play one station at a time with no
            // queue, so removing COMMAND_GET_TIMELINE for the system's media
            // notification controller hides that button in Auto's wide view.
            if (session.isMediaNotificationController(controller)) {
                val playerCommands = Player.Commands.Builder()
                    .addAllCommands()
                    .remove(Player.COMMAND_GET_TIMELINE)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }
            return super.onConnect(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        .build()
                )
                .build()
            // Tell Android Auto to render browsable items (the countries) as
            // large grid tiles (their flags), and playable items (stations) as
            // a list.
            val resultParams = LibraryParams.Builder().setExtras(gridStyleExtras()).build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, resultParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // The content-style hint is per-node: without repeating it on the
            // "Landen" folder its children fall back to a plain list (name shown
            // left of a small icon). Sending the grid hint again makes Android
            // Auto render each country as a large flag tile with the name
            // centered underneath.
            var resultParams = params
            val children: ImmutableList<MediaItem> = when {
                // Root: a single "Landen" folder. Android Auto promotes root
                // browsable items to grey top tabs (which can't show the flags),
                // so we keep the countries one level down where they render as a
                // proper flag list instead.
                parentId == ROOT_ID ->
                    ImmutableList.of(countriesFolderItem())
                // The "Landen" folder: the country flag tiles, as a grid.
                parentId == COUNTRIES_ID -> {
                    resultParams = LibraryParams.Builder().setExtras(gridStyleExtras()).build()
                    ImmutableList.copyOf(Countries.withStations().map(::countryFolderItem))
                }
                // A country folder: that country's stations.
                parentId.startsWith(COUNTRY_PREFIX) -> {
                    val cc = parentId.removePrefix(COUNTRY_PREFIX)
                    ImmutableList.copyOf(Countries.stationsFor(cc).map(::mediaItemFor))
                }
                else -> ImmutableList.of()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(children, resultParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = Stations.byMediaId(mediaId)
            return when {
                station != null ->
                    Futures.immediateFuture(LibraryResult.ofItem(mediaItemFor(station), null))
                mediaId == COUNTRIES_ID ->
                    Futures.immediateFuture(LibraryResult.ofItem(countriesFolderItem(), null))
                mediaId.startsWith(COUNTRY_PREFIX) ->
                    Futures.immediateFuture(
                        LibraryResult.ofItem(countryFolderItem(mediaId.removePrefix(COUNTRY_PREFIX)), null)
                    )
                else ->
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        // Called whenever a controller (the app UI, Android Auto, Assistant)
        // asks to play something: a browsed/tapped item carries a mediaId, a
        // voice command carries a search query. Resolve either to a full,
        // playable station item so the single player just swaps streams.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { requested ->
                val station = Stations.byMediaId(requested.mediaId)
                    ?: Stations.byQuery(requested.requestMetadata.searchQuery)
                    ?: Stations.byQuery(requested.mediaMetadata.title?.toString())
                    ?: Stations.byMediaId(LastStation.load(this@PlaybackService))
                    ?: Stations.ALL.first()
                mediaItemFor(station)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val station = Stations.byMediaId(LastStation.load(this@PlaybackService))
                ?: Stations.ALL.first()
            val itemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(mediaItemFor(station)),
                0,
                0L
            )
            return Futures.immediateFuture(itemsWithStartPosition)
        }
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val COUNTRIES_ID = "countries"
        private const val COUNTRY_PREFIX = "country:"
        const val ACTION_AUTOPLAY = "com.arjenvdw.radioapp.AUTOPLAY"
        private const val ROUTE_WAIT_INTERVAL_MS = 250L
        private const val MAX_ROUTE_WAIT_ATTEMPTS = 16 // ~4s to route to BT, else don't play
        private const val NOW_PLAYING_POLL_MS = 25_000L
    }
}
