package com.itsash.flick

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

private const val MS = 1_000_000L
private const val NEVER = Long.MIN_VALUE / 2

/**
 * Chop twice (Motorola style): two quick karate-chop motions, like holding the phone
 * like a knife. Input is linear acceleration (m/s², gravity already removed), any
 * orientation; no gravity vector is needed. Pure Kotlin, time based (ns timestamps).
 *
 * One chop = a sharp spike (push, |a| >= [threshold]) followed within 180 ms by an
 * opposite-sign spike (stop, at least half the threshold) along the same axis. The
 * axis is the direction of the push, so down, forward and sideways chops all work.
 * Rules:
 * - the push may stay above the threshold for 150 ms at most (a chop is a 50-120 ms
 *   impulse; a swing or sway is wider), and may not exceed 80 m/s² (an impact);
 * - after a chop the signal must dip below 0.35 x threshold for 40 ms before the next
 *   chop can start, so one long swing or a continuous shake never counts twice;
 * - the first chop of a pair needs 600 ms without activity above 0.5 x threshold
 *   before it (walking, jogging, stairs are rhythmic and never calm);
 * - while the slow noise level (RMS of recent motion, clipped at 0.5 x threshold)
 *   is above 0.3 x threshold the phone is being carried and nothing counts;
 * - two chops on (nearly) the same axis, 150-800 ms apart (start to start), fire;
 *   a later or off-axis second chop only becomes the new first chop;
 * - after a fire there is a 1 s cooldown and the signal has to be quiet for 300 ms.
 *   Further chops in that time are lost: a third chop never cancels the toggle.
 * Optional: [axis] (a learned chop direction, device coordinates) restricts pushes to
 * a +-45 degree cone around it. [blocked] (proximity near, optional gate) ignores all.
 */
class ChopDetector(var threshold: Float = DEFAULT_THRESHOLD) {
    companion object {
        const val DEFAULT_THRESHOLD = 12f
        const val MIN_THRESHOLD = 6f
        const val MAX_THRESHOLD = 20f
        const val LOW = 16f
        const val NORMAL = 12f
        const val HIGH = 8f

        /** Easiest level, used while the user trains. */
        const val TRAIN_THRESHOLD = 6f

        /** Training learns 60% of the weakest chop of each double chop. */
        const val TRAIN_FRACTION = 0.6f
        const val MAX_PEAK = 80f
        const val CONE_COS = 0.7071f
        private const val BRAKE_WINDOW = 180 * MS
        private const val MAX_WIDTH = 150 * MS
        private const val BRAKE_FRACTION = 0.5f
        private const val VALLEY_FRACTION = 0.35f
        private const val VALLEY_HOLD = 40 * MS
        private const val CALM_FRACTION = 0.5f
        private const val CALM_BEFORE = 600 * MS
        private const val NOISE_FRACTION = 0.3f
        private const val MIN_GAP = 150 * MS
        private const val MAX_GAP = 800 * MS
        private const val SAME_AXIS = 0.6f
        private const val COOLDOWN = 1000 * MS
        private const val AFTER_FIRE_QUIET = 300 * MS
        private const val NOISE_TAU = 3f
        private const val BURST_FLOOR = 2.5f
        private const val BURST_END = 200 * MS

        /** Maps the sensitivity names to thresholds (m/s²). */
        fun forSensitivity(s: String): Float = when (s) {
            "low" -> LOW
            "high" -> HIGH
            else -> NORMAL
        }
    }

    private enum class S { IDLE, PUSH, WAIT_DIP }

    /** Learned chop direction (unit vector, device coordinates) or null for any direction. */
    var axis: FloatArray? = null

    /** Set while the phone is covered (proximity near), when the pocket gate is on. */
    var blocked = false

    private var state = S.IDLE
    private var last = -1L
    private var muteUntil = Long.MIN_VALUE
    private var noiseMs = 0f

    private var pushT = 0L
    private var aboveT = 0L
    private var peak = 0f
    private var calmOk = false
    private val u = FloatArray(3)

    private var quietSince = -1L
    private var inLoud = false
    private var lastLoudEnd = NEVER
    private var quietBeforeRun = Long.MAX_VALUE / 4
    private var cooldownUntil = Long.MIN_VALUE
    private var quietUntilCalm = false

    private var pendT = NEVER
    private var pendPeak = 0f
    private val pendU = FloatArray(3)
    private var hasPending = false

    private var burstMax = 0f
    private var burstLast = NEVER
    private var inBurst = false

    /** Current |a| (m/s²), for the live meter. */
    var level = 0f
        private set

    /** Peak |a| of the last burst of motion above 2.5 m/s², whatever its shape (live meter). */
    var burstPeak = 0f
        private set

    /** Incremented for every finished burst. */
    var burstSeq = 0
        private set

    /** Push peak of the last accepted chop, and a counter. */
    var lastChopPeak = 0f
        private set
    var chopSeq = 0
        private set

    /** Weakest push peak of the double chop that fired last, for training. */
    var lastPeak = 0f
        private set

    /** Mean push direction (unit) of the double chop that fired last, for training. */
    val lastAxis = FloatArray(3)

    /** 1 when a first chop is waiting for its partner. */
    val pendingChops: Int get() = if (hasPending) 1 else 0

    /** Slow motion level (m/s²); the phone counts as carried above 0.3 x threshold. */
    val noiseLevel: Float get() = sqrt(noiseMs)

    fun mute(untilNs: Long) {
        muteUntil = untilNs
        reset()
    }

    private fun reset() {
        state = S.IDLE
        hasPending = false
        inLoud = false
        lastLoudEnd = NEVER
        quietSince = -1L
        quietUntilCalm = false
    }

    fun add(t: Long, x: Float, y: Float, z: Float): Boolean {
        val mag = sqrt(x * x + y * y + z * z)
        level = mag
        if (t < muteUntil || blocked) {
            reset()
            last = t
            return false
        }
        val dt = if (last < 0) 0.01f else ((t - last) / 1e9f).coerceIn(0.001f, 0.2f)
        last = t
        val thr = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        trackBurst(t, mag)
        val clip = min(mag, CALM_FRACTION * thr)
        noiseMs += (clip * clip - noiseMs) * min(1f, dt / NOISE_TAU)

        if (mag >= CALM_FRACTION * thr) {
            if (!inLoud) {
                inLoud = true
                quietBeforeRun = if (lastLoudEnd == NEVER) Long.MAX_VALUE / 4 else t - lastLoudEnd
            }
            lastLoudEnd = t
        } else {
            inLoud = false
        }

        // The gap is measured push to push; the second chop is only confirmed once its stop arrived.
        if (hasPending && t - pendT > MAX_GAP + BRAKE_WINDOW) hasPending = false

        when (state) {
            S.IDLE -> {
                if (quietUntilCalm) {
                    if (mag < CALM_FRACTION * thr) {
                        if (quietSince < 0) quietSince = t
                        if (t - quietSince >= AFTER_FIRE_QUIET) quietUntilCalm = false
                    } else {
                        quietSince = -1L
                    }
                    return false
                }
                if (mag < thr || sqrt(noiseMs) > NOISE_FRACTION * thr) return false
                val ax = axis
                if (ax != null && abs((x * ax[0] + y * ax[1] + z * ax[2]) / mag) < CONE_COS) {
                    toWaitDip()
                    return false
                }
                state = S.PUSH
                pushT = t
                aboveT = 0L
                peak = mag
                calmOk = quietBeforeRun >= CALM_BEFORE
                u[0] = x / mag; u[1] = y / mag; u[2] = z / mag
                return false
            }

            S.PUSH -> {
                val p = x * u[0] + y * u[1] + z * u[2]
                if (mag > peak && p > 0f) {
                    peak = mag
                    u[0] = x / mag; u[1] = y / mag; u[2] = z / mag
                }
                if (p >= thr) aboveT += (dt * 1e9f).toLong()
                if (peak > MAX_PEAK || aboveT > MAX_WIDTH) {
                    toWaitDip()
                    return false
                }
                if (p <= -BRAKE_FRACTION * thr) {
                    toWaitDip()
                    return chopDone(t)
                }
                if (t - pushT > BRAKE_WINDOW) toWaitDip()
                return false
            }

            S.WAIT_DIP -> {
                if (mag < VALLEY_FRACTION * thr) {
                    if (quietSince < 0) quietSince = t
                    if (t - quietSince >= VALLEY_HOLD) {
                        state = S.IDLE
                        quietSince = -1L
                    }
                } else {
                    quietSince = -1L
                }
                return false
            }
        }
    }

    private fun toWaitDip() {
        state = S.WAIT_DIP
        quietSince = -1L
    }

    private fun trackBurst(t: Long, mag: Float) {
        if (mag >= BURST_FLOOR) {
            inBurst = true
            burstLast = t
            if (mag > burstMax) burstMax = mag
        } else if (inBurst && t - burstLast > BURST_END) {
            inBurst = false
            burstPeak = burstMax
            burstMax = 0f
            burstSeq++
        }
    }

    /** A finished chop (push + stop). Returns true when it completes a double chop. */
    private fun chopDone(t: Long): Boolean {
        lastChopPeak = peak
        chopSeq++
        val gap = pushT - pendT
        if (hasPending && gap >= MIN_GAP && gap <= MAX_GAP) {
            val dot = u[0] * pendU[0] + u[1] * pendU[1] + u[2] * pendU[2]
            if (abs(dot) >= SAME_AXIS) {
                hasPending = false
                if (t < cooldownUntil) return false
                lastPeak = min(peak, pendPeak)
                val sign = if (dot >= 0f) 1f else -1f
                for (i in 0..2) lastAxis[i] = pendU[i] + sign * u[i]
                val n = sqrt(lastAxis[0] * lastAxis[0] + lastAxis[1] * lastAxis[1] + lastAxis[2] * lastAxis[2])
                if (n > 1e-6f) for (i in 0..2) lastAxis[i] /= n
                cooldownUntil = t + COOLDOWN
                quietUntilCalm = true
                quietSince = -1L
                return true
            }
        }
        if (hasPending && gap < MIN_GAP) return false
        if (calmOk) {
            hasPending = true
            pendT = pushT
            pendPeak = peak
            for (i in 0..2) pendU[i] = u[i]
        } else {
            hasPending = false
        }
        return false
    }
}
