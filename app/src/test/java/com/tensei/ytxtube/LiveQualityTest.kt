package com.tensei.ytxtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveQualityTest {
    // A typical YouTube live HLS ladder, in playlist order, with one unusable track (0).
    private val tiers = listOf(144, 240, 360, 480, 720, 1080, 0)

    @Test
    fun offered_isTheRealLadderHighestFirst() {
        assertEquals(listOf(1080, 720, 480, 360, 240, 144), LiveQuality.offered(tiers))
        assertEquals(listOf(720, 360), LiveQuality.offered(listOf(360, 720, 720, 0)))
    }

    @Test
    fun pickTrack_exactThenBelowThenLowestAbove() {
        assertEquals(5, LiveQuality.pickTrack(tiers, 1080))
        assertEquals(4, LiveQuality.pickTrack(tiers, 720))
        // 1440 is not offered: best below it.
        assertEquals(5, LiveQuality.pickTrack(tiers, 1440))
        // Nothing at or below 144 on a ladder starting at 360: lowest above.
        assertEquals(0, LiveQuality.pickTrack(listOf(360, 720), 144))
        assertNull(LiveQuality.pickTrack(listOf(0, 0), 720))
        assertNull(LiveQuality.pickTrack(emptyList(), 720))
    }
}
