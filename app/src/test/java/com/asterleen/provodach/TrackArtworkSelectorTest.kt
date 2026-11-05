package com.asterleen.provodach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TrackArtworkSelectorTest {
    @Test
    fun unchangedMetadataKeepsArtwork() {
        val selector = TrackArtworkSelector(42, Random(1))
        val artwork = selector.select("Artist", "Track")
        repeat(100) {
            assertEquals(artwork, selector.select("Artist", "Track"))
        }
    }

    @Test
    fun titleOrArtistChangesRotateArtwork() {
        val selector = TrackArtworkSelector(42, Random(1))
        val first = selector.select("Artist", "Track")
        val nextTitle = selector.select("Artist", "Other track")
        assertNotEquals(first, nextTitle)
        assertNotEquals(nextTitle, selector.select("Other artist", "Other track"))
    }

    @Test
    fun successiveTracksNeverRepeatArtworkAndStayInBounds() {
        val selector = TrackArtworkSelector(42, Random(2))
        var previous = selector.select("Artist", "Track 0")
        repeat(1000) {
            val current = selector.select("Artist", "Track ${it + 1}")
            assertTrue(current in 0 until 42)
            assertNotEquals(previous, current)
            previous = current
        }
    }

    @Test
    fun singleArtworkCanBeUsedForAllTracks() {
        val selector = TrackArtworkSelector(1, Random(1))
        assertEquals(0, selector.select("Artist", "Track"))
        assertEquals(0, selector.select("Other artist", "Other track"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyArtworkSetIsRejected() {
        TrackArtworkSelector(0)
    }
}
