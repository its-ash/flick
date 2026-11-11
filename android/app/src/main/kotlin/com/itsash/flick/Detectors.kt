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

    /** About 65% of the median peak, kept inside [[min], [max]]. Null when there are no peaks. */
    fun threshold(peaks: List<Float>, min: Float, max: Float): Float? =
        if (peaks.isEmpty()) null else (median(peaks) * FRACTION).coerceIn(min, max)
}

/**
 * Chop twice. One chop is a strong push along one direction followed within 20-250 ms
 * by an opposite-sign stop (checked on 9 directions: 3 axes and 6 diagonals, so a
 * tilted hand works too). Two chops 150-900 ms apart fire, after a short confirm
 * window that rejects continuous shaking. The input is linear acceleration (m/s², no
 * gravity).
 *
 * Rules that keep it natural and safe:
 * - the first chop must be isolated (no other chop within 900 ms before it), so
 *   shaking never fires; a third push inside the confirm window cancels as well;
 * - chops that already fired do not count towards that isolation and the next first
 *   chop may come 600 ms after the last fire, so toggling the flashlight again about
 *   a second later works every time;
 * - samples are ignored while [mute] is active (our own vibration).
 */
class ChopDetector(var scale: Float = 1f, var base: Float = DEFAULT_BASE) {
    companion object {
        const val DEFAULT_BASE = 8f
        const val MIN_BASE = 4f
        const val MAX_BASE = 20f
        const val TRAIN_BASE = 4f
        const val NOISE_K = 3f
        private const val MIN_CHOP_GAP = 150 * MS
        private const val MAX_CHOP_GAP = 900 * MS
        private const val CONFIRM = 250 * MS
        private const val AFTER_FIRE = 600 * MS
        private const val ISOLATION = 900 * MS
        private const val MAX_RUN = 300 * MS
        private const val MIN_BRAKE = 20 * MS
        private const val MAX_BRAKE = 250 * MS
        private const val SETTLE_MAX = 400 * MS
        private const val H = 0.70710678f
        private val DIRS = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
            floatArrayOf(H, H, 0f), floatArrayOf(H, -H, 0f),
            floatArrayOf(H, 0f, H), floatArrayOf(H, 0f, -H),
            floatArrayOf(0f, H, H), floatArrayOf(0f, H, -H),
        )
    }

    private class Chop(val t: Long, val peak: Float) {
        var consumed = false
    }

    private class Pending(val first: Chop, val second: Chop, val fireAt: Long)

    private class Axis {
        var state = IDLE
        var sign = 0
        var runStart = 0L
        var peak = 0f
        var peakT = 0L
        var pushSign = 0
        var pushPeak = 0f
        var pushT = 0L
        var settleSince = 0L

        fun settle(t: Long) { state = SETTLE; settleSince = t }
        fun reset() { state = IDLE }
        fun pushing(thr: Float) = state == RUN && peak >= 0.8f * thr

        /** Returns the push peak when a push + stop completed on this sample, else -1. */
        fun step(t: Long, u: Float, thr: Float): Float {
            val low = 0.35f * thr
            val s = if (u >= low) 1 else if (u <= -low) -1 else 0
            while (true) {
                when (state) {
                    IDLE -> {
                        if (s != 0) { state = RUN; sign = s; runStart = t; peak = abs(u); peakT = t }
                        return -1f
                    }
                    RUN -> {
                        if (s == sign) {
                            if (abs(u) > peak) { peak = abs(u); peakT = t }
                            if (t - runStart > MAX_RUN) settle(t)
                            return -1f
                        }
                        if (peak >= thr) {
                            state = BRAKE; pushSign = sign; pushPeak = peak; pushT = peakT
                        } else {
                            state = IDLE
                        }
                    }
                    BRAKE -> {
                        if (t - pushT > MAX_BRAKE) { state = IDLE; continue }
                        if (s == pushSign) {
                            if (abs(u) > pushPeak) { pushPeak = abs(u); pushT = t }
                            return -1f
                        }
                        if (s == -pushSign && abs(u) >= 0.5f * thr && t - pushT >= MIN_BRAKE) {
                            settle(t)
                            return pushPeak
                        }
                        return -1f
                    }
                    else -> {
                        if (s == 0 || t - settleSince > SETTLE_MAX) state = IDLE
                        return -1f
                    }
                }
            }
        }

        private companion object {
            const val IDLE = 0
            const val RUN = 1
            const val BRAKE = 2
            const val SETTLE = 3
        }
    }

    private val noise = NoiseTracker(capFloor = 4f)
    private val axes = Array(DIRS.size) { Axis() }
    private val chops = ArrayList<Chop>()
    private var pending: Pending? = null
    private var lastFireT = NEVER
    private var muteUntil = Long.MIN_VALUE

    /** Current motion magnitude (m/s²), for the live meter. */
    var level = 0f
        private set

    /** Weakest push of the two chops that fired last, for training. */
    var lastPeak = 0f
        private set

    /** Current trigger level (m/s²): max(floor x sensitivity, K x recent motion). */
    val threshold: Float
        get() = max((base * scale).coerceIn(MIN_BASE, MAX_BASE), NOISE_K * noise.rms)

    /** Ignore samples until this timestamp (ns), e.g. while our own vibration runs. */
    fun mute(untilNs: Long) {
        muteUntil = untilNs
        pending = null
    }

    fun add(t: Long, x: Float, y: Float, z: Float): Boolean {
        level = sqrt(x * x + y * y + z * z)
        if (t < muteUntil) {
            for (a in axes) a.reset()
            return false
        }
        noise.update(t, level)
        val thr = threshold

        pending?.let { p ->
            if (axes.any { it.pushing(thr) }) {
                pending = null // more pushes: this is shaking, not a double chop
            } else if (t >= p.fireAt) {
                pending = null
                p.first.consumed = true
                p.second.consumed = true
                lastFireT = p.second.t
                lastPeak = min(p.first.peak, p.second.peak)
                for (a in axes) a.reset()
                return true
            }
        }

        var found = -1f
        for (i in axes.indices) {
            val d = DIRS[i]
            val r = axes[i].step(t, d[0] * x + d[1] * y + d[2] * z, thr)
            if (r > found) found = r
        }
        if (found >= 0f) {
            for (a in axes) a.settle(t)
            onChop(t, found)
        }
        return false
    }

    private fun onChop(t: Long, peak: Float) {
        val prev = chops.lastOrNull()
        if (prev != null && t - prev.t < MIN_CHOP_GAP) return // same chop seen on another direction
        pending = null // a further chop while waiting: shaking
        chops.removeAll { t - it.t > 3000 * MS }
        val chop = Chop(t, peak)
        chops.add(chop)
        if (prev == null || prev.consumed || chops.size < 2) return
        val gap = t - prev.t
        val isolated = chops.none { it !== prev && it !== chop && !it.consumed && it.t < prev.t && prev.t - it.t < ISOLATION }
        if (gap in MIN_CHOP_GAP..MAX_CHOP_GAP && prev.t - lastFireT >= AFTER_FIRE && isolated) {
            pending = Pending(prev, chop, t + CONFIRM)
        }
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
