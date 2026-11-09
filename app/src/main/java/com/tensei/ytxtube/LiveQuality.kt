package com.tensei.ytxtube

/**
 * Quality choice for live streams. Live plays YouTube's HLS master playlist, which carries
 * several renditions in one video track group; a manual choice is applied by pinning one of
 * those tracks. Pure logic so it can be unit tested.
 */
object LiveQuality {

    /** Distinct quality tiers offered by the stream, highest first (0 = unusable track). */
    fun offered(tiersByTrack: List<Int>): List<Int> = tiersByTrack.filter { it > 0 }.distinct().sortedDescending()

    /**
     * Index of the track to pin for [wanted]: that exact tier, else the best one below it, else
     * the lowest one above it. Null when there is nothing usable.
     */
    fun pickTrack(tiersByTrack: List<Int>, wanted: Int): Int? {
        val usable = tiersByTrack.withIndex().filter { it.value > 0 }
        if (usable.isEmpty()) return null
        return (usable.filter { it.value <= wanted }.maxByOrNull { it.value }
            ?: usable.minByOrNull { it.value })?.index
    }
}
