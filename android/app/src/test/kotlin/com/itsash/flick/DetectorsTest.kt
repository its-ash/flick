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

    /** A sine of [hz] along [dir], [halfCycles] half-waves from [startMs]. */
    fun sine(startMs: Long, halfCycles: Int, hz: Float, amp: Float, dir: FloatArray) {
        val end = startMs + (halfCycles * 500f / hz).toLong()
        for (i in 0 until n) {
            val t = i * STEP
            if (t in startMs until end) {
                val s = (amp * sin(2 * PI * hz * (t - startMs) / 1000.0)).toFloat()
                x[i] += s * dir[0]; y[i] += s * dir[1]; z[i] += s * dir[2]
            }
        }
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

    /** Like [run] but mutes the detector for 290 ms after each fire, as the engine does for its own buzz. */
    fun runWithBuzz(d: ChopDetector): List<Long> {
        val out = ArrayList<Long>()
        for (i in 0 until n) {
            val t = i * STEP
            if (d.add(t * MS, x[i], y[i], z[i])) {
                out.add(t)
                d.mute((t + 290) * MS)
            }
        }
        return out
    }
}

private val X = floatArrayOf(1f, 0f, 0f)
private val Y = floatArrayOf(0f, 1f, 0f)
private val Z = floatArrayOf(0f, 0f, 1f)

class DetectorsTest {

    // ---- chop twice ----

    private val AMP = 18f // a normal chop, linear acceleration m/s²
    private val DOWN_PORTRAIT = floatArrayOf(0f, -1f, 0f) // phone upright, chop downwards
    private val FORWARD_PORTRAIT = floatArrayOf(0f, 0f, -1f) // chop away from the user
    private val DOWN_LANDSCAPE = floatArrayOf(-1f, 0f, 0f)
    private val DOWN_FLAT = floatArrayOf(0f, 0f, -1f)

    private fun doubleChop(dir: FloatArray, amp: Float = AMP, first: Long = 1000, gap: Long = 350, ms: Long = 4000) =
        Trace(ms).apply {
            chop(first, amp, dir)
            chop(first + gap, amp, dir)
            noise(0.1f)
        }

    private fun fires(t: Trace, d: ChopDetector = ChopDetector()): List<Long> = t.run(d::add)

    @Test
    fun doubleChopFiresInAnyDirectionAndOrientation() {
        val dirs = mapOf(
            "down, portrait" to DOWN_PORTRAIT,
            "forward, portrait" to FORWARD_PORTRAIT,
            "down, landscape" to DOWN_LANDSCAPE,
            "down, flat" to DOWN_FLAT,
            "sideways" to X,
            "diagonal" to floatArrayOf(0.6f, -0.6f, 0.53f),
            "up" to Y,
        )
        for ((name, dir) in dirs) {
            val fired = fires(doubleChop(dir))
            assertEquals("$name $fired", 1, fired.size)
        }
    }

    @Test
    fun doubleChopFiresAcrossTimingAndStrength() {
        for (gap in listOf(250L, 350L, 500L, 700L, 780L)) {
            assertEquals("gap $gap", 1, fires(doubleChop(Y, gap = gap)).size)
        }
        for (amp in listOf(14f, 25f, 40f)) {
            assertEquals("amp $amp", 1, fires(doubleChop(Y, amp = amp)).size)
        }
    }

    @Test
    fun singleChopDoesNotFire() {
        val t = Trace(4000).apply { chop(1000, AMP, Y); noise(0.1f) }
        assertEquals(0, fires(t).size)
    }

    @Test
    fun twoChopsFarApartDoNotFire() {
        val t = Trace(5000).apply { chop(1000, AMP, Y); chop(2500, AMP, Y); noise(0.1f) }
        assertEquals(0, fires(t).size)
        val tooFast = Trace(4000).apply { chop(1000, AMP, Y); chop(1100, AMP, Y); noise(0.1f) }
        assertEquals(0, fires(tooFast).size)
    }

    @Test
    fun tripleChopFiresOnceAndDoesNotCancel() {
        val t = Trace(5000).apply { chop(1000, AMP, Y); chop(1350, AMP, Y); chop(1700, AMP, Y); noise(0.1f) }
        val fired = fires(t)
        assertEquals(fired.toString(), 1, fired.size)
        val four = Trace(6000).apply { for (k in 0 until 4) chop(1000 + k * 350L, AMP, Y); noise(0.1f) }
        assertEquals(1, fires(four).size)
    }

    @Test
    fun secondDoubleChopTogglesAgain() {
        // Torch on at ~1.4 s, torch off at ~2.6 s (1.2 s later), and once more later.
        val t = Trace(9000).apply {
            chop(1000, AMP, Y); chop(1350, AMP, Y)
            chop(2200, AMP, Y); chop(2550, AMP, Y)
            chop(5000, AMP, X); chop(5400, AMP, X)
            noise(0.1f)
        }
        val fired = fires(t)
        assertEquals(fired.toString(), 3, fired.size)
    }

    @Test
    fun chopAfterTheBuzzStillWorks() {
        val t = Trace(9000).apply {
            chop(1000, AMP, Y); chop(1350, AMP, Y)
            // our own vibration right after the fire: two sharp ticks
            pulse(1500, 30, 20f, Z); pulse(1600, 30, -20f, Z); pulse(1700, 30, 20f, Z)
            chop(2400, AMP, Y); chop(2750, AMP, Y)
            noise(0.1f)
        }
        val fired = t.runWithBuzz(ChopDetector())
        assertEquals(fired.toString(), 2, fired.size)
    }

    @Test
    fun vibrationFeedbackAloneDoesNotFire() {
        val d = ChopDetector()
        val t = Trace(3000).apply { pulse(1000, 30, 20f, Z); pulse(1100, 30, -20f, Z); pulse(1200, 30, 20f, Z); pulse(1300, 30, -20f, Z) }
        // Muted for 800 ms around it, as after a gesture's own buzz.
        d.mute(1500 * MS)
        assertEquals(0, fires(t, d).size)
    }

    @Test
    fun restingPhoneTableAndTremorDoNotFire() {
        assertEquals(0, fires(Trace(20000).apply { noise(0.05f) }).size)
        // tremor: 8 Hz, 0.3 m/s²
        val tremor = Trace(10000).apply { sine(500, 70, 8f, 0.3f, X); noise(0.05f) }
        assertEquals(0, fires(tremor).size)
        // a phone put down on a table: one soft bump
        val bump = Trace(4000).apply { pulse(1000, 40, 7f, Z); pulse(1040, 40, -4f, Z); noise(0.05f) }
        assertEquals(0, fires(bump).size)
    }

    @Test
    fun walkingDoesNotFire() {
        assertEquals(0, fires(Trace(30000).apply { walking(2f); noise(0.3f, 5) }).size)
        assertEquals(0, fires(Trace(30000).apply { walking(3.5f); noise(0.3f, 6) }).size)
        // in a pocket: stronger vertical sway
        val pocket = Trace(30000).apply { sine(0, 120, 2f, 6f, Y); sine(0, 120, 2f, 3f, Z); noise(0.3f, 7) }
        assertEquals(0, fires(pocket).size)
    }

    @Test
    fun joggingAndStairsDoNotFire() {
        // Jogging: 2.7 Hz heel strikes on top of a strong sway, phone in a pocket.
        val jog = Trace(30000).apply {
            sine(0, 160, 2.7f, 8f, Y)
            var s = 500L
            while (s < 29000) { pulse(s, 60, 18f, Y); pulse(s + 60, 60, -12f, Y); s += 370 }
            noise(0.3f, 8)
        }
        assertEquals(0, fires(jog).size)
        // Stairs: a heel strike every 500 ms.
        val stairs = Trace(30000).apply {
            sine(0, 120, 2f, 7f, Y)
            var s = 500L
            while (s < 29000) { pulse(s, 60, 16f, Y); pulse(s + 60, 60, -10f, Y); s += 500 }
            noise(0.3f, 9)
        }
        assertEquals(0, fires(stairs).size)
    }

    @Test
    fun continuousShakeDoesNotFire() {
        for (hz in listOf(2f, 3f, 5f)) {
            for (amp in listOf(14f, 20f, 30f)) {
                val t = Trace(8000).apply { sine(1000, (5 * 2 * hz).toInt(), hz, amp, X); noise(0.1f) }
                val fired = fires(t)
                assertEquals("shake $hz Hz amp $amp $fired", 0, fired.size)
            }
        }
        // a slow big swing (one long wave) never counts as a chop
        val swing = Trace(6000).apply { sine(1000, 6, 1f, 25f, Y); noise(0.1f) }
        assertEquals(0, fires(swing).size)
    }

    @Test
    fun sensitivityLowVersusHigh() {
        val soft = doubleChop(Y, amp = 14f) // gentle chop
        assertEquals(0, fires(soft, ChopDetector(ChopDetector.LOW)).size)
        assertEquals(1, fires(soft, ChopDetector(ChopDetector.NORMAL)).size)
        assertEquals(1, fires(soft, ChopDetector(ChopDetector.HIGH)).size)
        val tiny = doubleChop(Y, amp = 10f)
        assertEquals(0, fires(tiny, ChopDetector(ChopDetector.NORMAL)).size)
        assertEquals(1, fires(tiny, ChopDetector(ChopDetector.HIGH)).size)
        val hard = doubleChop(Y, amp = 25f)
        assertEquals(1, fires(hard, ChopDetector(ChopDetector.LOW)).size)
        assertEquals(ChopDetector.LOW, ChopDetector.forSensitivity("low"), 0f)
        assertEquals(ChopDetector.HIGH, ChopDetector.forSensitivity("high"), 0f)
        assertEquals(ChopDetector.NORMAL, ChopDetector.forSensitivity("normal"), 0f)
    }

    @Test
    fun learnedDirectionConeFiltersOffAxisChops() {
        val onX = ChopDetector().apply { axis = floatArrayOf(1f, 0f, 0f) }
        assertEquals(1, fires(doubleChop(X), onX).size)
        assertEquals(1, fires(doubleChop(floatArrayOf(0.91f, 0.41f, 0f)), ChopDetector().apply { axis = floatArrayOf(1f, 0f, 0f) }).size)
        assertEquals(0, fires(doubleChop(Y), ChopDetector().apply { axis = floatArrayOf(1f, 0f, 0f) }).size)
        assertEquals(0, fires(doubleChop(floatArrayOf(0.5f, 0.87f, 0f)), ChopDetector().apply { axis = floatArrayOf(1f, 0f, 0f) }).size)
    }

    @Test
    fun pocketGateBlocksOnlyWhenSet() {
        val blocked = ChopDetector().apply { blocked = true }
        assertEquals(0, fires(doubleChop(Y), blocked).size)
        assertEquals(1, fires(doubleChop(Y)).size) // default: no gate
    }

    @Test
    fun trainingReportsPeakAndAxis() {
        val d = ChopDetector(ChopDetector.TRAIN_THRESHOLD)
        assertEquals(1, fires(doubleChop(floatArrayOf(0.8f, 0.6f, 0f), amp = 20f), d).size)
        assertTrue("peak ${d.lastPeak}", d.lastPeak in 17f..21f)
        assertEquals(0.8f, Math.abs(d.lastAxis[0]), 0.15f)
        assertEquals(0.6f, Math.abs(d.lastAxis[1]), 0.15f)
        assertTrue(d.chopSeq >= 2)
        assertTrue(d.burstSeq >= 1)
    }

    @Test
    fun fallbackFromRawAccelerometerWorks() {
        // Raw accelerometer = gravity + chop; GravityFilter removes gravity again.
        val t = doubleChop(Y, ms = 4000)
        val f = GravityFilter()
        val d = ChopDetector()
        val out = ArrayList<Long>()
        for (i in 0 until t.n) {
            val ts = i * STEP * MS
            f.update(ts, t.x[i], t.y[i] + 9.8f, t.z[i])
            if (d.add(ts, f.linear[0], f.linear[1], f.linear[2])) out.add(i * STEP)
        }
        assertEquals(out.toString(), 1, out.size)
    }

    @Test
    fun sampleRateDoesNotMatter() {
        // Same double chop sampled at 50 Hz (every other sample).
        val t = doubleChop(Y)
        val d = ChopDetector()
        var fired = 0
        for (i in 0 until t.n step 2) if (d.add(i * STEP * MS, t.x[i], t.y[i], t.z[i])) fired++
        assertEquals(1, fired)
    }

    @Test
    fun calibrationForChop() {
        val v = Calibration.threshold(listOf(20f, 22f, 18f), ChopDetector.MIN_THRESHOLD, ChopDetector.MAX_THRESHOLD, ChopDetector.TRAIN_FRACTION)!!
        assertEquals(12f, v, 0.01f)
        val weak = Calibration.threshold(listOf(8f, 9f, 10f), ChopDetector.MIN_THRESHOLD, ChopDetector.MAX_THRESHOLD, ChopDetector.TRAIN_FRACTION)!!
        assertEquals(ChopDetector.MIN_THRESHOLD, weak, 0.001f)
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
