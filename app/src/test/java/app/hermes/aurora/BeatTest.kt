package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatTest {

    /** readings 50 ms apart; returns the times of the beats */
    private fun beats(levels: List<Float>): List<Long> {
        val b = Beat()
        return levels.mapIndexedNotNull { i, v -> if (b.feed(v, 1000L + i * 50L)) i * 50L else null }
    }

    // a kick as the box hears it: quiet, then loud for a quarter of a second with a wobble, then quiet (one every 450 ms)
    private val kick = listOf(0.12f, 0.1f, 0.55f, 0.83f, 0.78f, 0.92f, 0.8f, 0.3f, 0.14f)

    @Test fun oneKickIsOneBeat() {
        val got = beats(List(10) { kick }.flatten())
        assertEquals(10, got.size)
        // a beat may be noticed one reading (50 ms) sooner or later, never twice in a kick
        assertEquals(true, got.zipWithNext { a, b -> b - a }.all { it in 400L..500L })
    }

    @Test fun aLoudSteadyBassHasNoBeat() {
        assertEquals(1, beats(List(100) { 0.9f }).size)          // only the moment it starts
    }

    @Test fun quietMusicHasNoBeat() {
        assertEquals(0, beats(List(100) { if (it % 9 == 0) 0.25f else 0.1f }).size)
    }

    @Test fun veryFastHitsAreNotCountedTwice() {
        // a jump every 100 ms: at most one beat in 170 ms
        val got = beats(List(60) { if (it % 2 == 0) 0.1f else 0.9f })
        assertEquals(true, got.zipWithNext { a, b -> b - a }.all { it > Beat.GAP_MS })
    }
}
