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

    // ---- shake ----

    private val PORTRAIT = floatArrayOf(0f, 9.8f, 0f)
    private val LANDSCAPE = floatArrayOf(9.8f, 0f, 0f)
    private val FLAT = floatArrayOf(0f, 0f, 9.8f)
    private val TILTED = floatArrayOf(0f, 6.93f, 6.93f)

    /** [halfCycles] half-swings of a [hz] sine along [dir]; peak acceleration [amp]. */
    private fun Trace.sine(startMs: Long, halfCycles: Int, hz: Float, amp: Float, dir: FloatArray) {
        val lenMs = (halfCycles * 1000f / (2 * hz)).toLong()
        for (i in 0 until n) {
            val t = i * STEP
            if (t in startMs until startMs + lenMs) {
                val s = sin(2 * PI * hz * (t - startMs) / 1000.0).toFloat() * amp
                x[i] += s * dir[0]; y[i] += s * dir[1]; z[i] += s * dir[2]
            }
        }
    }

    private fun fires(t: Trace, g: FloatArray, d: ShakeDetector = ShakeDetector()): List<Long> {
        d.setGravity(g[0], g[1], g[2])
        return t.run(d::add)
    }

    /** A shake of [halfCycles] swings along a horizontal axis for the given gravity. */
    private fun shakeTrace(g: FloatArray, dir: FloatArray, halfCycles: Int, amp: Float, hz: Float = 3f): Trace =
        Trace(4000).apply {
            sine(1000, halfCycles, hz, amp, dir)
            sine(1000, halfCycles, hz, amp * 0.25f, g.map { it / 9.8f }.toFloatArray()) // a little vertical leak
            noise(0.05f)
        }

    @Test
    fun smallFiveSwingShakeFires() {
        val fired = fires(shakeTrace(PORTRAIT, X, 5, 2.4f), PORTRAIT)
        assertEquals(fired.toString(), 1, fired.size)
    }

    @Test
    fun largeShakeFires() {
        val fired = fires(shakeTrace(PORTRAIT, X, 6, 18f, hz = 2.5f), PORTRAIT)
        assertEquals(fired.toString(), 1, fired.size)
    }

    @Test
    fun fourSwingsAlsoFireFastAndSlow() {
        for (hz in listOf(1.8f, 3f, 5f)) {
            val fired = fires(shakeTrace(PORTRAIT, X, 6, 6f, hz), PORTRAIT)
            assertEquals("hz $hz $fired", 1, fired.size)
        }
    }

    @Test
    fun threeSwingsDoNotFire() {
        assertEquals(0, fires(shakeTrace(PORTRAIT, X, 3, 8f), PORTRAIT).size)
        assertEquals(0, fires(shakeTrace(PORTRAIT, X, 4, 8f), PORTRAIT).size)
    }

    @Test
    fun pauseInTheMiddleResets() {
        val t = Trace(6000).apply {
            sine(1000, 3, 3f, 6f, X)
            sine(2500, 3, 3f, 6f, X) // 1 s of rest, then 3 more: never 5 in a row
            noise(0.05f)
        }
        assertEquals(0, fires(t, PORTRAIT).size)
    }

    @Test
    fun restingPhoneAndTremorDoNotFire() {
        assertEquals(0, fires(Trace(20000).apply { noise(0.08f) }, FLAT).size)
        // tremor: 8 Hz, 0.3 m/s²
        val tremor = Trace(10000).apply { sine(500, 70, 8f, 0.3f, X); noise(0.05f) }
        assertEquals(0, fires(tremor, PORTRAIT).size)
    }

    private fun walkTrace(g: FloatArray, hz: Float, vertical: Float, forward: Float, lateral: Float, ms: Long = 20000): Trace =
        Trace(ms).apply {
            val cycles = (ms / 1000f * hz * 2).toInt()
            sine(0, cycles, hz, vertical, g.map { it / 9.8f }.toFloatArray())
            sine(0, cycles, hz, forward, X)
            sine(0, cycles / 2, hz / 2, lateral, Z)
            noise(0.3f, 5)
        }

    @Test
    fun walkingAndJoggingDoNotFire() {
        // phone upright in a hand: vertical = y
        assertEquals(0, fires(walkTrace(PORTRAIT, 1.9f, 3f, 1.8f, 1.2f), PORTRAIT).size)
        assertEquals(0, fires(walkTrace(PORTRAIT, 2.8f, 12f, 4f, 3f), PORTRAIT).size)
        // phone held flat: vertical = z
        assertEquals(0, fires(walkTrace(FLAT, 2f, 3f, 1.8f, 1.2f), FLAT).size)
    }

    @Test
    fun upAndDownShakeDoesNotFire() {
        assertEquals(0, fires(shakeTrace(PORTRAIT, PORTRAIT.map { it / 9.8f }.toFloatArray(), 8, 8f), PORTRAIT).size)
    }

    @Test
    fun continuousFourSecondShakeTogglesOnce() {
        val t = Trace(7000).apply { sine(1000, 24, 3f, 8f, X); noise(0.05f) }
        val fired = fires(t, PORTRAIT)
        assertEquals(fired.toString(), 1, fired.size)
    }

    @Test
    fun anotherShakeAfterAPauseTogglesAgain() {
        val t = Trace(8000).apply {
            sine(1000, 6, 3f, 6f, X)
            sine(4000, 6, 3f, 6f, X)
            noise(0.05f)
        }
        assertEquals(2, fires(t, PORTRAIT).size)
    }

    @Test
    fun worksInAnyOrientation() {
        val cases = listOf(
            PORTRAIT to X, LANDSCAPE to Y, FLAT to floatArrayOf(0.7f, 0.7f, 0f), TILTED to X,
            PORTRAIT to floatArrayOf(0.7f, 0f, 0.7f), // towards and away from the user's side
        )
        for ((g, dir) in cases) {
            val fired = fires(shakeTrace(g, dir, 5, 3f), g)
            assertEquals("g ${g.toList()} dir ${dir.toList()} $fired", 1, fired.size)
        }
    }

    @Test
    fun pocketAndVibrationAreIgnored() {
        val near = ShakeDetector().apply { blocked = true }
        assertEquals(0, fires(shakeTrace(PORTRAIT, X, 6, 6f), PORTRAIT, near).size)
        val muted = ShakeDetector().apply { mute(5000 * MS) }
        assertEquals(0, fires(shakeTrace(PORTRAIT, X, 6, 6f), PORTRAIT, muted).size)
    }

    @Test
    fun sensitivityAndTrainedFloor() {
        val small = shakeTrace(PORTRAIT, X, 6, 1.3f)
        assertEquals(0, fires(small, PORTRAIT, ShakeDetector(scale = 1.3f)).size)
        assertEquals(1, fires(small, PORTRAIT, ShakeDetector(scale = 0.75f)).size)
        assertEquals(1, fires(small, PORTRAIT, ShakeDetector(base = 0.9f)).size)
    }

    @Test
    fun trainedFloorStaysLowForBigShakers() {
        val v = Calibration.threshold(listOf(20f, 25f, 22f), ShakeDetector.MIN_BASE, ShakeDetector.MAX_BASE, ShakeDetector.TRAIN_FRACTION)!!
        assertEquals(ShakeDetector.MAX_BASE, v, 0.001f)
        val soft = Calibration.threshold(listOf(2f, 2.5f, 2.2f), ShakeDetector.MIN_BASE, ShakeDetector.MAX_BASE, ShakeDetector.TRAIN_FRACTION)!!
        assertEquals(0.9f, soft, 0.05f)
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
