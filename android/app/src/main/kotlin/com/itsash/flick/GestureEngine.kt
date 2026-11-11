package com.itsash.flick

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Owns the sensor registrations and the detectors. Used by EventsService (real use)
 * and GestureProbe (test / train screen), so both behave exactly the same.
 *
 * Input choice (steadier than raw accelerometer data):
 * - shake: TYPE_LINEAR_ACCELERATION (gravity already removed by the platform fusion),
 *   else raw accelerometer through [GravityFilter]; plus the gravity direction and the
 *   proximity sensor (near = pocket / bag, the shake is ignored);
 * - pickup / flip: TYPE_GRAVITY, else the same filter's gravity;
 * - twist: gyroscope (all 3 axes).
 * Sensors are only re-registered when the set of needed sensors changes, so editing a
 * setting never drops samples in the middle of a gesture.
 */
class GestureEngine(
    context: Context,
    private val prefs: EventsPrefs,
    private val listener: Listener,
) : SensorEventListener {

    interface Listener {
        fun onGesture(gesture: Gesture)
        fun onFlip(event: FlipDetector.Event) = Unit
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    val shake = ShakeDetector()
    val twist = TwistDetector()
    private val pickup = PickupDetector()
    private val wave = WaveDetector()
    private val flip = FlipDetector()
    private val filter = GravityFilter()

    private var enabled: Set<Gesture> = emptySet()
    private var registered: Set<Int> = emptySet()
    private var accelForShake = false
    private var accelForPose = false
    private var proximityRange = 5f

    /** While set, this gesture uses its easiest threshold, to learn the user's strength. */
    var training: Gesture? = null
        set(v) {
            field = v
            applyTuning()
        }

    /** (Re)applies settings. Cheap to call on every preference change. */
    fun configure(gestures: Set<Gesture>) {
        enabled = gestures
        applyTuning()
        val needShake = Gesture.SHAKE in gestures
        val needPose = Gesture.PICKUP in gestures || Gesture.FLIP in gestures || needShake
        val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelForShake = needShake && linear == null
        accelForPose = needPose && gravity == null
        val want = buildSet {
            if (needShake && linear != null) add(Sensor.TYPE_LINEAR_ACCELERATION)
            if (needPose && gravity != null) add(Sensor.TYPE_GRAVITY)
            if (accelForShake || accelForPose) add(Sensor.TYPE_ACCELEROMETER)
            if (Gesture.TWIST in gestures) add(Sensor.TYPE_GYROSCOPE)
            if (Gesture.WAVE in gestures || needShake) add(Sensor.TYPE_PROXIMITY)
        }
        if (want == registered) return
        sensors.unregisterListener(this)
        registered = want
        for (type in want) {
            val s = if (type == Sensor.TYPE_PROXIMITY) {
                // The wake-up variant keeps working while the CPU sleeps.
                sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type)
            } else {
                sensors.getDefaultSensor(type)
            } ?: continue
            if (type == Sensor.TYPE_PROXIMITY) {
                proximityRange = s.maximumRange
                sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                sensors.registerListener(this, s, 10_000) // 100 Hz where the phone allows it
            }
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        registered = emptySet()
    }

    private fun applyTuning() {
        val scale = prefs.scale
        val t = training
        shake.scale = if (t == Gesture.SHAKE) 1f else scale
        shake.base = if (t == Gesture.SHAKE) ShakeDetector.TRAIN_BASE else prefs.calibration(Gesture.SHAKE) ?: ShakeDetector.DEFAULT_BASE
        twist.scale = if (t == Gesture.TWIST) 1f else scale
        twist.base = if (t == Gesture.TWIST) TwistDetector.TRAIN_BASE else prefs.calibration(Gesture.TWIST) ?: TwistDetector.DEFAULT_BASE
        pickup.scale = scale
    }

    /** Ignores shake / twist input for [ms], e.g. while our own vibration shakes the phone. */
    fun mute(ms: Long) {
        val until = SystemClock.elapsedRealtimeNanos() + ms * 1_000_000L
        shake.mute(until)
        twist.mute(until)
    }

    /** Vibrates with the gesture's own pattern and mutes the detectors meanwhile. */
    fun buzz(gesture: Gesture, vibrate: Boolean = true) {
        val pattern = PATTERNS[gesture] ?: return
        mute(pattern.sum() + MUTE_AFTER)
        if (vibrate) vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> feedShake(t, v[0], v[1], v[2])
            Sensor.TYPE_GRAVITY -> feedPose(t, v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                filter.update(t, v[0], v[1], v[2])
                if (accelForShake) feedShake(t, filter.linear[0], filter.linear[1], filter.linear[2])
                if (accelForPose) feedPose(t, filter.gravity[0], filter.gravity[1], filter.gravity[2])
            }
            Sensor.TYPE_GYROSCOPE -> if (Gesture.TWIST in enabled && twist.add(t, v[0], v[1], v[2])) {
                listener.onGesture(Gesture.TWIST)
            }
            Sensor.TYPE_PROXIMITY -> {
                val near = v[0] < proximityRange
                shake.blocked = near
                if (Gesture.WAVE in enabled && wave.add(t, near)) listener.onGesture(Gesture.WAVE)
            }
        }
    }

    private fun feedShake(t: Long, x: Float, y: Float, z: Float) {
        if (Gesture.SHAKE in enabled && shake.add(t, x, y, z)) listener.onGesture(Gesture.SHAKE)
    }

    private fun feedPose(t: Long, x: Float, y: Float, z: Float) {
        shake.setGravity(x, y, z)
        if (Gesture.PICKUP in enabled && pickup.add(t, x, y, z)) listener.onGesture(Gesture.PICKUP)
        if (Gesture.FLIP in enabled) flip.add(t, z)?.let { listener.onFlip(it) }
    }

    companion object {
        /** Distinct feel per gesture: off/on durations in ms, starting with a pause. */
        val PATTERNS: Map<Gesture, LongArray> = mapOf(
            Gesture.SHAKE to longArrayOf(0, 30, 70, 30), // tick-tick
            Gesture.TWIST to longArrayOf(0, 70), // one firm pulse
            Gesture.PICKUP to longArrayOf(0, 20), // light tap
            Gesture.WAVE to longArrayOf(0, 20),
            Gesture.FLIP to longArrayOf(0, 25, 60, 25, 60, 90), // short short long
        )
        private const val MUTE_AFTER = 250L
    }
}
