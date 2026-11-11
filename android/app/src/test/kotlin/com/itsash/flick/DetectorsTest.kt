package com.itsash.flick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L

/** Feeds (timeMs, value) samples and reports whether the detector fired. */
private fun fires(samples: List<Pair<Long, Float>>, add: (Long, Float) -> Boolean): Int =
    samples.count { (t, v) -> add(t * MS, v) }

/** A spike: [peak] for [width] ms around [at], 0 elsewhere, sampled every 20 ms. */
private fun spikes(vararg at: Long, peak: Float, width: Long = 40, until: Long = 3000): List<Pair<Long, Float>> =
    (0L..until step 20).map { t -> t to (if (at.any { t in it until it + width }) peak else 0f) }

class DetectorsTest {

    @Test
    fun chopTwiceFires() {
        val d = ChopDetector()
        assertEquals(1, fires(spikes(500, 900, peak = 20f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun singleChopDoesNotFire() {
        val d = ChopDetector()
        assertEquals(0, fires(spikes(500, peak = 20f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun accelerateBrakePairCountsAsOneChop() {
        // 500 + 600 are one chop (within refractory); a second chop never comes.
        val d = ChopDetector()
        assertEquals(0, fires(spikes(500, 600, peak = 20f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun chopsTooFarApartDoNotFire() {
        val d = ChopDetector()
        assertEquals(0, fires(spikes(200, 1600, peak = 20f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun walkingDoesNotChop() {
        val d = ChopDetector()
        assertEquals(0, fires(spikes(100, 600, 1100, 1600, 2100, peak = 6f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun highSensitivityCatchesGentlerChops() {
        assertEquals(0, fires(spikes(500, 900, peak = 13f)) { t, v -> ChopDetector(1f).add(t, v) })
        val high = ChopDetector(0.75f)
        assertEquals(1, fires(spikes(500, 900, peak = 13f)) { t, v -> high.add(t, v) })
    }

    @Test
    fun twistBackAndForthFires() {
        val d = TwistDetector()
        val samples = (0L..2000 step 20).map { t ->
            t to when (t) {
                in 300 until 360 -> 8f
                in 560 until 620 -> -8f
                in 820 until 880 -> 8f
                else -> 0f
            }
        }
        assertEquals(1, fires(samples) { t, v -> d.add(t, v) })
    }

    @Test
    fun oneWayRotationsDoNotTwist() {
        val d = TwistDetector()
        assertEquals(0, fires(spikes(300, 600, 900, peak = 8f)) { t, v -> d.add(t, v) })
    }

    @Test
    fun pickupAfterRestingFires() {
        val d = PickupDetector()
        var fired = 0
        // Flat on a table for 2 s, then lifted towards the face.
        for (t in 0L..2000 step 20) if (d.add(t * MS, 0f, 0f, 9.8f)) fired++
        for (t in 2020L..2600 step 20) if (d.add(t * MS, 0f, 6.5f, 7f)) fired++
        assertEquals(1, fired)
    }

    @Test
    fun pickupWithoutRestDoesNotFire() {
        val d = PickupDetector()
        var fired = 0
        for (t in 0L..3000 step 20) if (d.add(t * MS, 0f, 6.5f, 7f)) fired++
        assertEquals(0, fired)
    }

    @Test
    fun quickWaveFires() {
        val d = WaveDetector()
        assertFalse(d.add(1000 * MS, true))
        assertTrue(d.add(1300 * MS, false))
    }

    @Test
    fun pocketIsNotAWave() {
        val d = WaveDetector()
        assertFalse(d.add(1000 * MS, true))
        assertFalse(d.add(9000 * MS, false))
    }

    @Test
    fun flipDownThenUp() {
        val d = FlipDetector()
        assertNull(d.add(0, -9.8f))
        assertNull(d.add(1000 * MS, -9.8f))
        assertEquals(FlipDetector.Event.FACE_DOWN, d.add(1600 * MS, -9.8f))
        assertNull(d.add(2000 * MS, -9.8f))
        assertEquals(FlipDetector.Event.FACE_UP, d.add(3000 * MS, 9.8f))
    }

    @Test
    fun gravityFilterSeparatesMotion() {
        val f = GravityFilter()
        repeat(50) { f.update(0f, 0f, 9.8f) }
        assertTrue(f.linearMagnitude() < 0.01f)
        f.update(0f, 20f, 9.8f)
        assertTrue(f.linearMagnitude() > 10f)
    }
}
