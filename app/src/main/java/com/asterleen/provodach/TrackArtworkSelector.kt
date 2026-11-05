package com.asterleen.provodach

import java.util.Random

/** Keep art stable for unchanged metadata, and avoid repeating it on consecutive tracks. */
internal class TrackArtworkSelector(
    private val artworkCount: Int,
    private val random: Random = Random()
) {
    init {
        require(artworkCount > 0)
    }

    var index: Int = random.nextInt(artworkCount)
        private set
    private var track: Pair<String, String>? = null

    fun select(artist: String, title: String): Int {
        val nextTrack = artist to title
        if (nextTrack == track) return index
        if (track != null && artworkCount > 1) {
            val candidate = random.nextInt(artworkCount - 1)
            index = if (candidate >= index) candidate + 1 else candidate
        }
        track = nextTrack
        return index
    }
}
