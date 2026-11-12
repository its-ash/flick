package com.itsash.flick

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

private const val MS = 1_000_000L
private const val NEVER = Long.MIN_VALUE / 2

/**
 * Chop twice (Motorola style). Input is linear acceleration (m/s², gravity removed), any
 * orientation. Pure Kotlin, time based (ns timestamps), independent of the sample rate.
 *
 * Tuned on a recording of Motorola's own hardware chop-chop sensor (motorola edge 30,
 * tools/sensorrec): a chop is a whip, a hard swing along one axis (x for a phone held like a
 * knife) stopped just as hard, so the acceleration along that axis swings + then -. A double
 * chop is four such lobes in a row, alternating in sign, peaks 70-280 ms apart, the whole
 * gesture about 350 ms. Lobe peaks were 34-90 m/s² when Motorola fired and at most ~37 when
 * it did not; the accelerometer saturates at 8 g, so strong chops read flat at ~75-80.
 * The wrist also turns, adding an acceleration toward the wrist in every lobe; it has the
 * same sign in + and - lobes, so it cancels out of the chop axis below.
 *
 * Rules:
 * - a motion starts with a sample of |a| >= 0.4 x [threshold]. Its axis is the sum of those
 *   samples weighted by |a|, each flipped to point the same way as the sum (so + and - lobes
 *   add up and the sideways wrist part cancels). Samples are measured along that axis (p);
 * - a lobe is a run of samples with |p| >= 0.4 x threshold and the same sign; it ends when p
 *   changes sign or stays below that floor for 60 ms. It is strong when its peak |p| >=
 *   threshold and it lasted at most 300 ms (a slow swing is wider);
 * - lobes chain while each one has the opposite sign of the one before and peaks at most
 *   300 ms after it; strong lobes count in a row the same way, a weak lobe restarts that count;
 * - the motion ends when no lobe is open and the last peak is 200 ms old. It fires when it had
 *   3 strong lobes in a row inside a chain of at least 4 (Motorola fires when the first or the
 *   last of the four is weaker), at most 8 strong in a row and at most 10 lobes in total: a
 *   continuous shake keeps going and never fires, and three or four chops give one toggle;
 * - with [axis] set (learned direction), the motion axis must be within 45 degrees of it;
 * - after a fire, motions ending within 500 ms are ignored.
 */
class ChopDetector(var threshold: Float = DEFAULT_THRESHOLD) {
    companion object {
        const val DEFAULT_THRESHOLD = 30f
        const val MIN_THRESHOLD = 15f
        const val MAX_THRESHOLD = 60f
        const val LOW = 40f
        const val NORMAL = 30f
        const val HIGH = 22f

        /** Easiest level, used while the user trains. */
        const val TRAIN_THRESHOLD = 15f

        /** Training learns 60% of the weakest lobe of each double chop. */
        const val TRAIN_FRACTION = 0.6f
        const val CONE_COS = 0.7071f
        private const val FLOOR_FRACTION = 0.4f
        private const val LOBE_END = 60 * MS
        private const val MAX_LOBE_WIDTH = 300 * MS
        private const val MAX_LOBE_GAP = 300 * MS
        private const val END_WAIT = 200 * MS
        private const val MIN_STRONG = 3
        private const val MIN_CHAIN = 4
        private const val MAX_STRONG = 8
        private const val MAX_LOBES = 10
        private const val COOLDOWN = 500 * MS
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

    /** Learned chop direction (unit vector, device coordinates) or null for any direction. */
    var axis: FloatArray? = null

    /** Set while the phone is covered (proximity near), when the pocket gate is on. */
    var blocked = false

    private var last = -1L
    private var muteUntil = Long.MIN_VALUE
    private var noiseMs = 0f
    private var cooldownUntil = NEVER

    // Current motion.
    private var active = false
    private var motionStart = 0L
    private val trace = StringBuilder()
    private val sum = FloatArray(3)
    private var lobes = 0
    private var prevSign = 0
    private var prevPeakT = NEVER
    private var run = 0
    private var runMin = 0f
    private var chain = 0
    private var bestRun = 0
    private var bestMin = 0f
    private var qualified = false

    // Current lobe.
    private var lobeSign = 0
    private var lobeStart = 0L
    private var lobePeak = 0f
    private var lobePeakT = 0L
    private var lobeBelowSince = -1L

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

    /** Weaker lobe of the last chop (pair of strong lobes), and a counter. */
    var lastChopPeak = 0f
        private set
    var chopSeq = 0
        private set

    /** Weakest strong lobe of the double chop that fired last, for training. */
    var lastPeak = 0f
        private set

    /** Chop axis (unit) of the double chop that fired last, for training. */
    val lastAxis = FloatArray(3)

    /** Summary of the last finished motion (lobes, strong lobes in a row, weakest), for diagnostics. */
    var lastMotion = ""
        private set
    var motionSeq = 0
        private set

    /** 1 when a first chop is waiting for its partner. */
    val pendingChops: Int get() = if (active && !qualified && run >= 2) 1 else 0

    /** Slow motion level (m/s², RMS over a few seconds), for the Test screen. */
    val noiseLevel: Float get() = sqrt(noiseMs)

    fun mute(untilNs: Long) {
        muteUntil = untilNs
        active = false
    }

    fun add(t: Long, x: Float, y: Float, z: Float): Boolean {
        val mag = sqrt(x * x + y * y + z * z)
        level = mag
        if (t < muteUntil || blocked) {
            active = false
            last = t
            return false
        }
        val dt = if (last < 0) 0.01f else ((t - last) / 1e9f).coerceIn(0.001f, 0.2f)
        last = t
        val thr = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val floor = FLOOR_FRACTION * thr

        trackBurst(t, mag)
        val clip = min(mag, thr)
        noiseMs += (clip * clip - noiseMs) * min(1f, dt / NOISE_TAU)

        if (!active) {
            if (mag < floor) return false
            startMotion(t, x, y, z)
        }
        if (mag >= floor) {
            // Weighted by |a| again, so the big chop lobes set the axis, not the small sideways
            // samples at the start of a motion.
            val s = (if (x * sum[0] + y * sum[1] + z * sum[2] < 0f) -1f else 1f) * mag
            sum[0] += s * x; sum[1] += s * y; sum[2] += s * z
        }
        val n = sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2])
        val p = (x * sum[0] + y * sum[1] + z * sum[2]) / n
        val ap = abs(p)

        if (ap >= floor) {
            val sign = if (p > 0f) 1 else -1
            if (lobeSign == 0) {
                startLobe(t, sign, ap)
            } else if (sign == lobeSign) {
                lobeBelowSince = -1L
                if (ap > lobePeak) { lobePeak = ap; lobePeakT = t }
            } else {
                closeLobe(t, thr)
                startLobe(t, sign, ap)
            }
        } else if (lobeSign != 0) {
            if (lobeBelowSince < 0) lobeBelowSince = t
            if (t - lobeBelowSince >= LOBE_END) closeLobe(t, thr)
        }
        if (lobeSign == 0 && t - prevPeakT > END_WAIT) return endMotion(t)
        return false
    }

    private fun startMotion(t: Long, x: Float, y: Float, z: Float) {
        active = true
        motionStart = t
        trace.setLength(0)
        // Seeded so the first sample has a direction; it is added again as a strong sample.
        sum[0] = x * 1e-3f; sum[1] = y * 1e-3f; sum[2] = z * 1e-3f
        lobes = 0
        prevSign = 0
        prevPeakT = NEVER
        run = 0
        chain = 0
        bestRun = 0
        bestMin = 0f
        qualified = false
        lobeSign = 0
    }

    private fun startLobe(t: Long, sign: Int, ap: Float) {
        lobeSign = sign
        lobeStart = t
        lobePeak = ap
        lobePeakT = t
        lobeBelowSince = -1L
    }

    private fun closeLobe(t: Long, thr: Float) {
        val alternates = prevSign != 0 && lobeSign != prevSign && lobePeakT - prevPeakT <= MAX_LOBE_GAP
        lobes++
        chain = if (alternates) chain + 1 else 1
        prevSign = lobeSign
        prevPeakT = lobePeakT
        val strong = lobePeak >= thr && t - lobeStart <= MAX_LOBE_WIDTH
        // Lobe trace for diagnostics: sign+peak, peak time from motion start, width (ms).
        trace.append(if (lobeSign > 0) '+' else '-').append(lobePeak.toInt())
            .append('@').append((lobePeakT - motionStart) / MS).append(" w").append((t - lobeStart) / MS).append(' ')
        lobeSign = 0
        if (!strong) {
            // A weak follow-through lobe still completes 3 strong ones (Motorola fires on those).
            if (alternates && run >= MIN_STRONG && chain >= MIN_CHAIN) qualify()
            run = 0
            return
        }
        if (run > 0 && alternates) {
            run++
            runMin = min(runMin, lobePeak)
        } else {
            run = 1
            runMin = lobePeak
        }
        if (run % 2 == 0) {
            chopSeq++
            lastChopPeak = runMin
        }
        if (run > bestRun) bestRun = run
        if (run >= MIN_STRONG && chain >= MIN_CHAIN) qualify()
    }

    private fun qualify() {
        if (!qualified || runMin > bestMin) bestMin = runMin
        qualified = true
    }

    /** The motion ended: fires when it was a double chop. */
    private fun endMotion(t: Long): Boolean {
        active = false
        val n = sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2])
        val u = floatArrayOf(sum[0] / n, sum[1] / n, sum[2] / n)
        val ax = axis
        val onAxis = ax == null || abs(u[0] * ax[0] + u[1] * ax[1] + u[2] * ax[2]) >= CONE_COS
        val fire = qualified && bestRun <= MAX_STRONG && lobes <= MAX_LOBES && onAxis && t >= cooldownUntil
        motionSeq++
        lastMotion = "lobes $lobes, strong in a row $bestRun, weakest %.1f, axis (%.2f, %.2f, %.2f)%s"
            .format(Locale.US, bestMin, u[0], u[1], u[2], if (fire) ", fired" else "") + "; lobes: $trace"
        if (fire) {
            lastPeak = bestMin
            for (i in 0..2) lastAxis[i] = u[i]
            cooldownUntil = t + COOLDOWN
        }
        return fire
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
}
