package com.itsash.flick

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure gesture detectors: no Android types, so they run in JVM unit tests
 * (android/app/src/test). Every detector is time based (timestamps in nanoseconds,
 * SensorEvent.timestamp), so it behaves the same at 50, 100 or 200 Hz.
 *
 * Thresholds are adaptive: threshold = max(floor, K x recent motion level). The floor
 * is the built-in default or the user's trained value (Calibration), times the
 * sensitivity `scale` (< 1 = more sensitive). Walking or riding raises the motion
 * level and so the threshold; resting lowers it back to the floor.
 */

private const val MS = 1_000_000L
private const val NEVER = Long.MIN_VALUE / 2

/**
 * Splits raw accelerometer readings into gravity and linear acceleration. It is only
 * the fallback for phones without TYPE_GRAVITY / TYPE_LINEAR_ACCELERATION. The low-pass
 * is time based (time constant [tau] seconds): a fixed alpha of 0.8 at game rate has
 * a time constant of ~80 ms and would swallow a 100 ms chop.
 */
class GravityFilter(private val tau: Float = 0.5f) {
    val gravity = FloatArray(3)
    val linear = FloatArray(3)
    private var primed = false
    private var last = 0L

    fun update(t: Long, x: Float, y: Float, z: Float) {
        if (!primed) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            primed = true
        } else {
            val dt = ((t - last) / 1e9f).coerceIn(0.001f, 0.2f)
            val a = tau / (tau + dt)
            gravity[0] = a * gravity[0] + (1 - a) * x
            gravity[1] = a * gravity[1] + (1 - a) * y
            gravity[2] = a * gravity[2] + (1 - a) * z
        }
        last = t
        linear[0] = x - gravity[0]
        linear[1] = y - gravity[1]
        linear[2] = z - gravity[2]
    }

    fun linearMagnitude(): Float = sqrt(linear[0] * linear[0] + linear[1] * linear[1] + linear[2] * linear[2])
}

/**
 * Slow RMS of recent motion. Single samples are capped (at max([capFloor], 2.5 x the
 * current level)), so a gesture itself barely moves the level but sustained walking
 * or running raises it within a second or two. Time constant [tau] seconds.
 */
class NoiseTracker(private val tau: Float = 3f, private val capFloor: Float = 4f) {
    var rms = 0f
        private set
    private var ms = 0f
    private var last = -1L

    fun update(t: Long, value: Float) {
        val dt = if (last < 0) 0.01f else ((t - last) / 1e9f).coerceIn(0f, 0.2f)
        last = t
        val v = min(value, max(capFloor, 2.5f * rms))
        ms += (v * v - ms) * (dt / tau)
        rms = sqrt(ms)
    }
}

/** Personal calibration: turns the peaks of a few trained gestures into a floor. */
object Calibration {
    const val FRACTION = 0.65f

    fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** About 65% (or [fraction]) of the median peak, kept inside [[min], [max]]. Null when there are no peaks. */
    fun threshold(peaks: List<Float>, min: Float, max: Float, fraction: Float = FRACTION): Float? =
        if (peaks.isEmpty()) null else (median(peaks) * fraction).coerceIn(min, max)
}

/**
 * Shake side to side: a continuous series of direction reversals, any size.
 *
 * Input is linear acceleration (m/s², no gravity) plus the gravity direction
 * ([setGravity]). The gravity-aligned part is removed, the rest is projected on the
 * principal axis of the recent motion (a slowly updated covariance, so it works in
 * portrait, landscape or tilted). Every acceleration lobe on that axis that crosses the
 * threshold with the opposite sign of the one before is one swing (one per end point of
 * L, R, L, ...). [reversals] + 1 swings (default 4 reversals = 5 swings) fire, when:
 * - each swing follows the previous one within 450 ms (any longer pause resets the
 *   count, so the motion must be continuous) and not sooner than 50 ms (jitter);
 * - the mean time between swings is 60-400 ms (about 1.2-8 Hz, a hand shake);
 * - the vertical (gravity-aligned) motion is below 0.7 x the lateral motion, which
 *   rejects walking and jogging (rhythmic, mainly vertical motion);
 * - [blocked] is false (proximity near: pocket or bag).
 * The size of the swings does not matter beyond the low threshold, which is
 * max(floor x sensitivity, 5 x the slow motion level), so a resting phone or a tremor
 * does not count. After a fire the detector stays locked until the motion has stopped
 * for 500 ms and 700 ms have passed, so one long shake toggles once.
 */
class ShakeDetector(var scale: Float = 1f, var base: Float = DEFAULT_BASE, reversals: Int = 4) {
    companion object {
        const val DEFAULT_BASE = 1.5f
        const val MIN_BASE = 0.8f
        const val MAX_BASE = 3f
        const val TRAIN_BASE = 0.8f

        /** Training learns 40% of the typical swing, so small swings still work afterwards. */
        const val TRAIN_FRACTION = 0.4f
        const val NOISE_K = 5f
        const val VERTICAL_RATIO = 0.7f
        private const val MAX_GAP = 450 * MS
        private const val MIN_GAP = 50 * MS
        private const val MIN_HALF = 60 * MS
        private const val MAX_HALF = 400 * MS
        private const val AFTER_FIRE = 700 * MS
        private const val CALM = 500 * MS
        private const val TAU_AXIS = 0.6f
        private const val TAU_ENERGY = 0.8f
    }

    /** Direction reversals needed (4 or 5); swings = reversals + 1. */
    var reversals: Int = reversals.coerceIn(4, 5)
        set(v) {
            field = v.coerceIn(4, 5)
        }

    val swingsNeeded: Int get() = reversals + 1

    /** Set while the phone is covered (proximity near): pocket, bag. */
    var blocked = false

    private val noise = NoiseTracker(tau = 4f, capFloor = 0.4f)
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gravityOk = false
    private val cov = FloatArray(6) // xx yy zz xy xz yz
    private var ux = 1f
    private var uy = 0f
    private var uz = 0f
    private var pE = 0f
    private var vE = 0f
    private var last = -1L

    private val times = ArrayList<Long>()
    private val peaks = ArrayList<Float>()
    private var curSign = 0
    private var locked = false
    private var fireT = NEVER
    private var lastActive = NEVER
    private var muteUntil = Long.MIN_VALUE

    /** Current lateral motion |p| (m/s²), for the live meter. */
    var level = 0f
        private set

    /** Swings counted in the current series, for the live meter. */
    val swings: Int get() = times.size

    /** Mean peak of the swings of the series that fired last, for training. */
    var lastPeak = 0f
        private set

    /** Current trigger level (m/s²): max(floor x sensitivity, K x slow motion level). */
    val threshold: Float
        get() = max((base * scale).coerceIn(MIN_BASE, MAX_BASE), NOISE_K * noise.rms)

    fun setGravity(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        gravityOk = n > 5f
        if (gravityOk) {
            gx = x / n; gy = y / n; gz = z / n
        }
    }

    fun mute(untilNs: Long) {
        muteUntil = untilNs
        resetSeries()
    }

    private fun resetSeries() {
        times.clear(); peaks.clear(); curSign = 0
    }

    fun add(t: Long, x: Float, y: Float, z: Float): Boolean {
        if (t < muteUntil || blocked || !gravityOk) {
            level = 0f
            resetSeries()
            return false
        }
        val dt = if (last < 0) 0.01f else ((t - last) / 1e9f).coerceIn(0.001f, 0.2f)
        last = t
        val v = x * gx + y * gy + z * gz
        val hx = x - v * gx
        val hy = y - v * gy
        val hz = z - v * gz
        val a = dt / TAU_AXIS
        cov[0] += (hx * hx - cov[0]) * a
        cov[1] += (hy * hy - cov[1]) * a
        cov[2] += (hz * hz - cov[2]) * a
        cov[3] += (hx * hy - cov[3]) * a
        cov[4] += (hx * hz - cov[4]) * a
        cov[5] += (hy * hz - cov[5]) * a
        repeat(2) {
            val wx = cov[0] * ux + cov[3] * uy + cov[4] * uz
            val wy = cov[3] * ux + cov[1] * uy + cov[5] * uz
            val wz = cov[4] * ux + cov[5] * uy + cov[2] * uz
            val n = sqrt(wx * wx + wy * wy + wz * wz)
            if (n > 1e-9f) {
                ux = wx / n; uy = wy / n; uz = wz / n
            } else {
                val hn = sqrt(hx * hx + hy * hy + hz * hz)
                if (hn > 1e-3f) {
                    ux = hx / hn; uy = hy / hn; uz = hz / hn
                }
            }
        }
        val p = ux * hx + uy * hy + uz * hz
        val e = dt / TAU_ENERGY
        pE += (p * p - pE) * e
        vE += (v * v - vE) * e
        level = abs(p)
        if (!locked) noise.update(t, sqrt(x * x + y * y + z * z))
        val thr = threshold

        if (locked) {
            if (level >= thr) lastActive = t
            if (t - lastActive >= CALM && t - fireT >= AFTER_FIRE) {
                locked = false
                resetSeries()
            }
            return false
        }

        if (times.isNotEmpty() && t - times.last() > MAX_GAP) resetSeries()
        val s = if (p >= thr) 1 else if (p <= -thr) -1 else 0
        if (s == 0) return false
        if (s == curSign) {
            if (level > peaks.last()) peaks[peaks.size - 1] = level
            return false
        }
        if (times.isNotEmpty() && t - times.last() < MIN_GAP) resetSeries() // jitter
        if (sqrt(vE) > VERTICAL_RATIO * sqrt(pE)) { // walking, jogging: mostly vertical
            resetSeries()
            return false
        }
        curSign = s
        times.add(t)
        peaks.add(level)
        val need = swingsNeeded
        if (times.size > need) {
            times.removeAt(0); peaks.removeAt(0)
        }
        if (times.size < need) return false
        val half = (t - times[0]) / (need - 1)
        if (half !in MIN_HALF..MAX_HALF) return false
        lastPeak = peaks.subList(0, need - 1).average().toFloat()
        resetSeries()
        locked = true
        fireT = t
        lastActive = t
        return true
    }
}

/**
 * Twist twice: fast wrist rotation back and forth around the phone's long axis
 * (gyroscope Y), or around X for someone holding the phone sideways. Three alternating
 * peaks (out, back, out) within 1.6 s, each at most 700 ms after the one before. Peaks
 * dominated by rotation around the screen normal (Z) are ignored, and a series must
 * start from at least 500 ms of calm, so continuous rotation fires at most once.
 */
class TwistDetector(var scale: Float = 1f, var base: Float = DEFAULT_BASE) {
    companion object {
        const val DEFAULT_BASE = 4.5f
        const val MIN_BASE = 2.5f
        const val MAX_BASE = 12f
        const val TRAIN_BASE = 2.5f
        const val NOISE_K = 4f
        private const val ISOLATION = 500 * MS
        private const val MAX_PEAK_GAP = 700 * MS
        private const val WINDOW = 1600 * MS
        private const val NEEDED = 3
    }

    private class Counter {
        var armed = true
        var count = 0
        var lastSign = 0
        var firstT = NEVER
        var lastT = NEVER
        var peakNow = 0f
        val peaks = FloatArray(NEEDED)

        fun begin(t: Long, sign: Int, amp: Float) {
            count = 1; lastSign = sign; firstT = t; peakNow = amp; peaks[0] = amp
        }
    }

    private val noise = NoiseTracker(capFloor = 2f)
    private val x = Counter()
    private val y = Counter()
    private var lastAnyPeak = NEVER
    private var muteUntil = Long.MIN_VALUE

    var level = 0f
        private set

    /** Weakest of the first two peaks of the series that fired last, for training. */
    var lastPeak = 0f
        private set

    val threshold: Float
        get() = max((base * scale).coerceIn(MIN_BASE, MAX_BASE), NOISE_K * noise.rms)

    fun mute(untilNs: Long) {
        muteUntil = untilNs
    }

    fun add(t: Long, gx: Float, gy: Float, gz: Float): Boolean {
        level = max(abs(gx), abs(gy))
        if (t < muteUntil) {
            x.armed = false; y.armed = false
            x.count = 0; y.count = 0
            return false
        }
        noise.update(t, sqrt(gx * gx + gy * gy + gz * gz))
        val thr = threshold
        val a = step(x, t, gx, gz, thr)
        val b = step(y, t, gy, gz, thr)
        return a || b
    }

    private fun step(c: Counter, t: Long, w: Float, gz: Float, thr: Float): Boolean {
        val aw = abs(w)
        if (aw < 0.4f * thr) c.armed = true
        if (!c.armed) {
            if (aw > c.peakNow) {
                c.peakNow = aw
                if (c.count > 0) c.peaks[c.count - 1] = aw
            }
            return false
        }
        if (aw < thr || aw < 1.2f * abs(gz)) return false
        c.armed = false
        c.peakNow = aw
        val sign = if (w > 0) 1 else -1
        val isolated = t - lastAnyPeak >= ISOLATION
        var fired = false
        if (c.count == 0) {
            if (isolated) c.begin(t, sign, aw)
        } else if (t - c.lastT > MAX_PEAK_GAP || t - c.firstT > WINDOW) {
            c.count = 0
            if (isolated) c.begin(t, sign, aw)
        } else if (sign == c.lastSign) {
            c.begin(t, sign, aw)
        } else {
            c.lastSign = sign
            c.peaks[c.count] = aw
            c.count++
            if (c.count >= NEEDED) {
                lastPeak = min(c.peaks[0], c.peaks[1])
                x.count = 0; y.count = 0
                fired = true
            }
        }
        c.lastT = t
        lastAnyPeak = t
        return fired
    }
}

/**
 * Pick up: the phone rested (nearly) flat, screen up, for at least 1 s, then is lifted
 * and tilted towards the user within 1.5 s of leaving the table. The tilt must hold for
 * 120 ms so one noisy sample or a bump cannot fire it. Input is the gravity vector.
 */
class PickupDetector(var scale: Float = 1f) {
    private val restNeeded = 1000 * MS
    private val liftWindow = 1500 * MS
    private val liftHold = 120 * MS
    private val cooldown = 3000 * MS

    private var flatSince = -1L
    private var leftFlatAt = -1L
    private var liftedSince = -1L
    private var rested = false
    private var lastFire = NEVER

    fun add(t: Long, gx: Float, gy: Float, gz: Float): Boolean {
        val flat = gz > 8.6f && abs(gx) < 3f && abs(gy) < 3f
        if (flat) {
            if (flatSince < 0) flatSince = t
            if (t - flatSince >= restNeeded) rested = true
            leftFlatAt = -1L
            liftedSince = -1L
            return false
        }
        flatSince = -1L
        if (!rested) return false
        if (leftFlatAt < 0) leftFlatAt = t
        if (t - leftFlatAt > liftWindow) {
            rested = false
            liftedSince = -1L
            return false
        }
        if (gy > 4.5f * scale && gz < 8.5f) {
            if (liftedSince < 0) liftedSince = t
            if (t - liftedSince >= liftHold && t - lastFire >= cooldown) {
                rested = false
                liftedSince = -1L
                lastFire = t
                return true
            }
        } else {
            liftedSince = -1L
        }
        return false
    }
}

/**
 * Wave: the proximity sensor goes near then far within 40-1000 ms. A longer "near"
 * (a pocket, a call at the ear, a face-down phone) is ignored.
 */
class WaveDetector {
    private val minNear = 40 * MS
    private val maxNear = 1000 * MS
    private val cooldown = 1500 * MS

    private var nearSince = -1L
    private var lastFire = NEVER

    fun add(t: Long, near: Boolean): Boolean {
        if (near) {
            if (nearSince < 0) nearSince = t
            return false
        }
        if (nearSince < 0) return false
        val held = t - nearSince
        nearSince = -1L
        if (held in minNear..maxNear && t - lastFire >= cooldown) {
            lastFire = t
            return true
        }
        return false
    }
}

/** Flip: screen down for 1.5 s -> FACE_DOWN; picked back up (for 150 ms) -> FACE_UP. */
class FlipDetector {
    enum class Event { FACE_DOWN, FACE_UP }

    private val holdNeeded = 1500 * MS
    private val upHold = 150 * MS
    private var downSince = -1L
    private var upSince = -1L
    var isDown = false
        private set

    fun add(t: Long, gz: Float): Event? {
        if (gz < -8.5f) {
            upSince = -1L
            if (downSince < 0) downSince = t
            if (!isDown && t - downSince >= holdNeeded) {
                isDown = true
                return Event.FACE_DOWN
            }
            return null
        }
        downSince = -1L
        if (isDown && gz > -5f) {
            if (upSince < 0) upSince = t
            if (t - upSince >= upHold) {
                isDown = false
                upSince = -1L
                return Event.FACE_UP
            }
        } else {
            upSince = -1L
        }
        return null
    }
}
