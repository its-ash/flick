package com.itsash.flick

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Pure gesture detectors: no Android types, so they run in JVM unit tests
 * (android/app/src/test). Each takes timestamps in nanoseconds (SensorEvent.timestamp)
 * and returns true (or an event) when its gesture completes.
 *
 * `scale` comes from the sensitivity setting: < 1 lowers thresholds (more sensitive).
 */

private const val MS = 1_000_000L

/** Splits accelerometer readings into gravity and linear acceleration (low-pass filter). */
class GravityFilter(private val alpha: Float = 0.8f) {
    val gravity = FloatArray(3)
    val linear = FloatArray(3)
    private var primed = false

    fun update(x: Float, y: Float, z: Float) {
        if (!primed) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            primed = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }
        linear[0] = x - gravity[0]
        linear[1] = y - gravity[1]
        linear[2] = z - gravity[2]
    }

    fun linearMagnitude(): Float = sqrt(linear[0] * linear[0] + linear[1] * linear[1] + linear[2] * linear[2])
}

/**
 * Chop twice: two sharp acceleration spikes, 250–900 ms apart. One chop makes an
 * accelerate + brake spike pair within ~150 ms, which the refractory time merges.
 */
class ChopDetector(scale: Float = 1f) {
    private val threshold = 15f * scale
    private val release = threshold * 0.5f
    private val refractory = 250 * MS
    private val window = 900 * MS
    private val cooldown = 1500 * MS

    private var armed = true
    private var lastPeak = Long.MIN_VALUE / 2
    private var firstPeak = Long.MIN_VALUE / 2
    private var count = 0
    private var lastFire = Long.MIN_VALUE / 2

    fun add(t: Long, linearMagnitude: Float): Boolean {
        if (linearMagnitude < release) armed = true
        if (t - lastFire < cooldown) return false
        if (!armed || linearMagnitude < threshold || t - lastPeak < refractory) return false
        armed = false
        lastPeak = t
        if (count == 0 || t - firstPeak > window) {
            firstPeak = t
            count = 1
            return false
        }
        count = 0
        lastFire = t
        return true
    }
}

/**
 * Twist twice: fast wrist rotation around the phone's long axis (gyroscope Y),
 * back and forth. Needs 3 alternating peaks (for example + − +) within 1.5 s.
 */
class TwistDetector(scale: Float = 1f) {
    private val threshold = 5.5f * scale
    private val release = threshold * 0.4f
    private val refractory = 120 * MS
    private val window = 1500 * MS
    private val cooldown = 1500 * MS
    private val needed = 3

    private var armed = true
    private var lastSign = 0
    private var lastPeak = Long.MIN_VALUE / 2
    private var firstPeak = Long.MIN_VALUE / 2
    private var count = 0
    private var lastFire = Long.MIN_VALUE / 2

    fun add(t: Long, rotationY: Float): Boolean {
        if (abs(rotationY) < release) armed = true
        if (t - lastFire < cooldown) return false
        if (!armed || abs(rotationY) < threshold || t - lastPeak < refractory) return false
        armed = false
        val sign = if (rotationY > 0) 1 else -1
        lastPeak = t
        if (count == 0 || t - firstPeak > window || sign == lastSign) {
            firstPeak = t
            count = 1
            lastSign = sign
            return false
        }
        lastSign = sign
        if (++count < needed) return false
        count = 0
        lastFire = t
        return true
    }
}

/**
 * Pick up: the phone rested flat (screen up) for at least 1 s, then is lifted
 * and tilted towards the user within 1.5 s of leaving the table.
 */
class PickupDetector(scale: Float = 1f) {
    private val liftedY = 4.5f * scale
    private val restNeeded = 1000 * MS
    private val liftWindow = 1500 * MS
    private val cooldown = 3000 * MS

    private var flatSince = -1L
    private var leftFlatAt = -1L
    private var rested = false
    private var lastFire = Long.MIN_VALUE / 2

    fun add(t: Long, gx: Float, gy: Float, gz: Float): Boolean {
        val flat = gz > 9.0f && abs(gx) < 2f && abs(gy) < 2f
        if (flat) {
            if (flatSince < 0) flatSince = t
            if (t - flatSince >= restNeeded) rested = true
            leftFlatAt = -1L
            return false
        }
        flatSince = -1L
        if (!rested) return false
        if (leftFlatAt < 0) leftFlatAt = t
        if (t - leftFlatAt > liftWindow) {
            rested = false
            return false
        }
        if (gy > liftedY && gz < 8.5f && t - lastFire >= cooldown) {
            rested = false
            lastFire = t
            return true
        }
        return false
    }
}

/**
 * Wave: the proximity sensor goes near then far within 80–1000 ms. Longer
 * "near" (a pocket, a call at the ear, a face-down phone) is ignored.
 */
class WaveDetector {
    private val minNear = 80 * MS
    private val maxNear = 1000 * MS
    private val cooldown = 2000 * MS

    private var nearSince = -1L
    private var lastFire = Long.MIN_VALUE / 2

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

/** Flip: screen down for 1.5 s → FACE_DOWN; picked back up → FACE_UP. */
class FlipDetector {
    enum class Event { FACE_DOWN, FACE_UP }

    private val holdNeeded = 1500 * MS
    private var downSince = -1L
    var isDown = false
        private set

    fun add(t: Long, gz: Float): Event? {
        if (gz < -8.5f) {
            if (downSince < 0) downSince = t
            if (!isDown && t - downSince >= holdNeeded) {
                isDown = true
                return Event.FACE_DOWN
            }
            return null
        }
        downSince = -1L
        if (isDown && gz > -5f) {
            isDown = false
            return Event.FACE_UP
        }
        return null
    }
}
