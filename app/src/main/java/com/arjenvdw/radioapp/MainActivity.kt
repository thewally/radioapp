package com.arjenvdw.radioapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.arjenvdw.radioapp.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.common.util.concurrent.MoreExecutors

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var controller: MediaController? = null
    private lateinit var adapter: StationAdapter

    private val countries = Countries.withStations()
    private val countryViews = mutableMapOf<String, ImageView>()
    private var selectedCountry = "nl"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ forces edge-to-edge: keep the content out of the system bars
        // (and the display cutout / keyboard) so the layout looks as before.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Open on the country of the last played station (default NL).
        val last = Stations.byMediaId(LastStation.load(this))
        selectedCountry = last?.country?.takeIf { it in countries } ?: "nl"

        adapter = StationAdapter(stationsFor(selectedCountry)) { station -> play(station) }
        binding.stationList.layoutManager = LinearLayoutManager(this)
        binding.stationList.adapter = adapter

        buildCountryRow()
        selectCountry(selectedCountry)
        requestBluetoothPermission()

        binding.playPauseButton.setOnClickListener {
            controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }

        val sessionToken = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
            controller?.addListener(playerListener)
            // Start playing the last station on open so the app behaves like a
            // radio, unless something is already playing.
            if (controller?.isPlaying != true) {
                val resume = Stations.byMediaId(LastStation.load(this)) ?: Stations.ALL.first()
                play(resume)
            }
            updateNowPlaying()
        }, MoreExecutors.directExecutor())
    }

    /** On Android 12+ the app only receives Bluetooth ACL_CONNECTED broadcasts
     *  (used to autoplay when the car connects) if BLUETOOTH_CONNECT is granted. */
    private fun requestBluetoothPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val perm = android.Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), 1)
            }
        }
    }

    private fun stationsFor(cc: String): List<Station> = Countries.stationsFor(cc)

    private fun buildCountryRow() {
        binding.countryRow.removeAllViews()
        countryViews.clear()
        for (cc in countries) {
            val size = dp(56)
            val flagRes = resources.getIdentifier("ic_cflag_$cc", "drawable", packageName)
            val iv = ImageView(this).apply {
                setImageResource(flagRes)
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(12)
                }
                setOnClickListener { selectCountry(cc) }
            }
            binding.countryRow.addView(iv)
            countryViews[cc] = iv
        }
    }

    private fun selectCountry(cc: String) {
        selectedCountry = cc
        // colorPrimary exists on the MaterialComponents (M2) theme (colorOutline /
        // colorSurfaceVariant are Material3-only and would crash here). The circular
        // flag fills the button; the selected one gets a colored ring on top.
        val primary = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
        for ((code, view) in countryViews) {
            view.foreground = if (code == cc) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(3), primary)
                }
            } else null
        }
        adapter.submit(stationsFor(cc))
        updateNowPlaying()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun play(station: Station) {
        val c = controller ?: return
        val item = MediaItem.Builder().setMediaId(station.mediaId).build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        updateNowPlaying()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateNowPlaying()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNowPlaying()
        override fun onPlaybackStateChanged(playbackState: Int) = updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val currentId = c.currentMediaItem?.mediaId
        val station = Stations.byMediaId(currentId)

        if (station != null) {
            binding.nowPlayingLogo.setImageResource(station.logoResId(this))
            binding.nowPlayingName.text = station.name
            binding.nowPlayingFlag.text = station.flagEmoji()
        }

        binding.nowPlayingStatus.text = when {
            c.playbackState == Player.STATE_BUFFERING -> getString(R.string.buffering)
            c.isPlaying -> getString(R.string.now_playing)
            else -> getString(R.string.paused)
        }
        binding.playPauseButton.text =
            if (c.isPlaying) getString(R.string.pause) else getString(R.string.play)

        adapter.setNowPlaying(currentId, c.isPlaying)
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
