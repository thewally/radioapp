package com.arjenvdw.radioapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arjenvdw.radioapp.databinding.ItemStationBinding

/** Renders the station list; highlights the one currently playing. */
class StationAdapter(
    stations: List<Station>,
    private val onClick: (Station) -> Unit,
) : RecyclerView.Adapter<StationAdapter.VH>() {

    private var stations: List<Station> = stations
    private var nowPlayingMediaId: String? = null
    private var isPlaying: Boolean = false

    /** Replace the shown stations (e.g. after switching country filter). */
    fun submit(list: List<Station>) {
        stations = list
        notifyDataSetChanged()
    }

    fun setNowPlaying(mediaId: String?, playing: Boolean) {
        if (mediaId == nowPlayingMediaId && playing == isPlaying) return
        nowPlayingMediaId = mediaId
        isPlaying = playing
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemStationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val station = stations[position]
        val ctx = holder.binding.root.context
        holder.binding.stationLogo.setImageResource(station.logoResId(ctx))
        holder.binding.stationName.text = station.name

        val playing = station.mediaId == nowPlayingMediaId
        holder.binding.nowPlaying.visibility =
            if (playing) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.binding.nowPlaying.text =
            if (isPlaying) ctx.getString(R.string.now_playing) else ctx.getString(R.string.paused)

        holder.binding.root.setOnClickListener { onClick(station) }
    }

    override fun getItemCount(): Int = stations.size
}
