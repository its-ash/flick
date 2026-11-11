package com.itsash.flick

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MS = 1_000_000L
private const val STEP = 10L // 100 Hz

/** A 3-axis trace built from pulses, sampled at 100 Hz. */
private class Trace(val lengthMs: Long) {
    val n = (lengthMs / STEP).toInt() + 1
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)

    /** Adds a half-sine pulse of [amp] along [dir], starting at [atMs], lasting [widthMs]. */
    fun pulse(atMs: Long, widthMs: Long, amp: Float, dir: FloatArray) {
        for (i in 0 until n) {
            val t = i * STEP
            if (t in atMs until atMs + widthMs) {
                val s = sin(PI * (t - atMs) / widthMs).toFloat() * amp
                x[i] += s * dir[0]; y[i] += s * dir[1]; z[i] += s * dir[2]
            }
        }
    }

    /** One chop: push [amp] for 70 ms, then a brake in the opposite direction. */
    fun chop(atMs: Long, amp: Float, dir: FloatArray) {
        pulse(atMs, 70, amp, dir)
        pulse(atMs + 70, 70, -0.8f * amp, dir)
    }

    fun noise(amp: Float, seed: Int = 1) {
        val r = Random(seed)
        for (i in 0 until n) {
            x[i] += (r.nextFloat() - 0.5f) * 2 * amp
            y[i] += (r.nextFloat() - 0.5f) * 2 * amp
            z[i] += (r.nextFloat() - 0.5f) * 2 * amp
        }
    }

    /** Walking: ~2 Hz sway of [amp] on every axis, phase shifted. */
    fun walking(amp: Float) {
        for (i in 0 until n) {
            val s = (2 * PI * 2.0 * i * STEP / 1000.0)
            x[i] += (amp * sin(s)).toFloat()
            y[i] += (amp * 1.4 * sin(s + 1.0)).toFloat()
            z[i] += (amp * 0.8 * sin(s + 2.0)).toFloat()
        }
    }

    /** Runs [add] over the trace, returns the times (ms) it fired. */
    fun run(add: (Long, Float, Float, Float) -> Boolean): List<Long> {
        val out = ArrayList<Long>()
        for (i in 0 until n) if (add(i * STEP * MS, x[i], y[i], z[i])) out.add(i * STEP)
        return out
    }
}

private val X = floatArrayOf(1f, 0f, 0f)
private val Y = floatArrayOf(0f, 1f, 0f)
private val Z = floatArrayOf(0f, 0f, 1f)
private val DIAG = floatArrayOf(0.6f, 0.6f, 0.53f)

class DetectorsTest {

    // ---- chop ----

    private fun doubleChop(dir: FloatArray, amp: Float = 14f, gap: Long = 400): Trace =
        Trace(4000).apply {
            chop(1000, amp, dir)
            chop(1000 + gap, amp, dir)
            noise(0.3f)
        }

    @Test
    fun doubleChopFiresOnAnyAxis() {
        for (dir in listOf(X, Y, Z, DIAG)) {
            val fired = doubleChop(dir).run(ChopDetector()::add)
            assertEquals("dir ${dir.toList()}", 1, fired.size)
        }
    }

    @Test
    fun gentleChopFires() {
        assertEquals(1, doubleChop(Y, amp = 9f).run(ChopDetector()::add).size)
    }

    @Test
    fun singleChopDoesNotFire() {
        val t = Trace(4000).apply { chop(1000, 14f, Y); noise(0.3f) }
        assertEquals(0, t.run(ChopDetector()::add).size)
    }

    @Test
    fun chopsTooFarApartDoNotFire() {
        assertEquals(0, doubleChop(Y, gap = 1500).run(ChopDetector()::add).size)
    }

    @Test
    fun walkingDoesNotTrigger() {
        val t = Trace(20000).apply { walking(4f); noise(0.8f) }
        assertEquals(0, t.run(ChopDetector()::add).size)
    }

    @Test
    fun jogginHardDoesNotTrigger() {
        val t = Trace(20000).apply { walking(9f); noise(1.5f, 7) }
        assertEquals(0, t.run(ChopDetector()::add).size)
    }

    @Test
    fun continuousShakingDoesNotTrigger() {
        val t = Trace(6000).apply {
            for (k in 0 until 12) chop(500L + k * 260, 16f, Y)
            noise(0.3f)
        }
        val fired = t.run(ChopDetector()::add)
        assertEquals(fired.toString(), 0, fired.size)
    }

    /** The reported bug: chop, torch turns on, chop again about a second later. */
    @Test
    fun secondDoubleChopAfterTorchOnFires() {
        val t = Trace(9000).apply {
            chop(1000, 14f, Y); chop(1400, 14f, Y) // torch on
            chop(3000, 14f, Y); chop(3400, 14f, Y) // torch off
            chop(5200, 12f, X); chop(5600, 12f, X) // and on again, other direction
            noise(0.3f)
        }
        val fired = t.run(ChopDetector()::add)
        assertEquals(fired.toString(), 3, fired.size)
    }

    @Test
    fun secondDoubleChopRightAfterFiringFires() {
        val t = Trace(6000).apply {
            chop(1000, 14f, Y); chop(1400, 14f, Y)
            chop(2400, 14f, Y); chop(2800, 14f, Y) // about 1 s after the first fired
            noise(0.3f)
        }
        assertEquals(2, t.run(ChopDetector()::add).size)
    }

    @Test
    fun mutedVibrationIsIgnored() {
        val d = ChopDetector()
        d.mute(3000 * MS)
        val t = Trace(2500).apply { chop(1000, 14f, Y); chop(1400, 14f, Y) }
        assertEquals(0, t.run(d::add).size)
    }

    @Test
    fun lowSensitivityNeedsHarderChop() {
        val gentle = doubleChop(Y, amp = 9f)
        assertEquals(0, gentle.run(ChopDetector(scale = 1.3f)::add).size)
        assertEquals(1, gentle.run(ChopDetector(scale = 0.75f)::add).size)
    }

    @Test
    fun trainedBaseAdaptsToTheUser() {
        val soft = doubleChop(Y, amp = 7f)
        assertEquals(0, soft.run(ChopDetector()::add).size)
        assertEquals(1, soft.run(ChopDetector(base = 5f)::add).size)
    }

    // ---- twist ----

    /** Back and forth rotation: out, back, out. */
    private fun twistTrace(axis: FloatArray, amp: Float = 7f): Trace = Trace(4000).apply {
        pulse(1000, 120, amp, axis)
        pulse(1250, 120, -amp, axis)
        pulse(1500, 120, amp, axis)
        noise(0.1f)
    }

    @Test
    fun twistFiresAroundLongAxisAndSideways() {
        assertEquals(1, twistTrace(Y).run(TwistDetector()::add).size)
        assertEquals(1, twistTrace(X).run(TwistDetector()::add).size) // phone held sideways
        assertEquals(1, twistTrace(floatArrayOf(0.8f, 0.6f, 0f)).run(TwistDetector()::add).size) // tilted grip
    }

    @Test
    fun twistWorksWhenPhoneIsUpsideDownOrFlat() {
        // The sign flips with orientation, the back-and-forth pattern does not.
        assertEquals(1, twistTrace(floatArrayOf(0f, -1f, 0f)).run(TwistDetector()::add).size)
    }

    @Test
    fun oneWayRotationDoesNotTwist() {
        val t = Trace(4000).apply {
            pulse(1000, 120, 7f, Y); pulse(1400, 120, 7f, Y); pulse(1800, 120, 7f, Y)
        }
        assertEquals(0, t.run(TwistDetector()::add).size)
    }

    @Test
    fun spinningAroundScreenNormalDoesNotTwist() {
        assertEquals(0, twistTrace(Z).run(TwistDetector()::add).size)
    }

    @Test
    fun walkingDoesNotTwist() {
        val t = Trace(20000).apply { walking(1.2f); noise(0.3f) }
        assertEquals(0, t.run(TwistDetector()::add).size)
    }

    @Test
    fun gentleTwistFiresOnHighSensitivity() {
        val t = twistTrace(Y, amp = 3.5f)
        assertEquals(0, t.run(TwistDetector(scale = 1.3f)::add).size)
        assertEquals(1, t.run(TwistDetector(scale = 0.75f)::add).size)
    }

    // ---- helpers ----

    @Test
    fun calibrationUsesMedianFraction() {
        assertEquals(10f, Calibration.threshold(listOf(14f, 16f, 15f), 4f, 20f)!!, 1.0f)
        assertEquals(4f, Calibration.threshold(listOf(1f, 1f, 1f), 4f, 20f)!!, 0.001f)
        assertNull(Calibration.threshold(emptyList(), 4f, 20f))
    }

    @Test
    fun gravityFilterSeparatesMotion() {
        val f = GravityFilter()
        var t = 0L
        repeat(300) { f.update(t, 0f, 0f, 9.8f); t += 10 * MS }
        assertTrue(f.linearMagnitude() < 0.01f)
        f.update(t, 0f, 20f, 9.8f)
        assertTrue(f.linearMagnitude() > 10f)
    }

    // ---- pose detectors ----

    @Test
    fun pickupAfterRestingFires() {
        val d = PickupDetector()
        var fired = 0
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
        assertNull(d.add(3000 * MS, 9.8f))
        assertEquals(FlipDetector.Event.FACE_UP, d.add(3200 * MS, 9.8f))
    }
}
