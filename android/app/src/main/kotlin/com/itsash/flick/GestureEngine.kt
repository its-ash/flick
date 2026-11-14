package com.itsash.flick

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Owns the sensor registrations and the detectors. Used by EventsService (real use)
 * and GestureProbe (test / train screen), so both behave exactly the same.
 *
 * Input choice:
 * - chop: TYPE_LINEAR_ACCELERATION (gravity removed by the platform fusion), else raw
 *   accelerometer through [GravityFilter]. With "work with the screen off" the wake-up
 *   variant of the sensor is preferred (it keeps delivering while the CPU sleeps) and
 *   events are never batched. Proximity is only registered when the optional pocket gate
 *   is on, or for Wave;
 * - pickup / flip: TYPE_GRAVITY, else the same filter's gravity;
 * - twist: gyroscope (all 3 axes).
 * - chop on Motorola phones: the vendor "ChopChop Wakeup" sensor (com.motorola.sensor.chopchop),
 *   the same detector Moto Actions uses. It runs on the sensor chip, fires at the 3rd swing and
 *   wakes the CPU itself, so it needs no 100 Hz stream and no wake lock. Used when present and
 *   "Use the phone's chop sensor" is on; the software [ChopDetector] is the fallback. The Test
 *   screen still streams linear acceleration for its meter and Calibrate.
 * Sensors are only re-registered when the set of needed sensors changes, so editing a
 * setting never drops samples in the middle of a gesture.
 */
class GestureEngine(
    context: Context,
    private val prefs: EventsPrefs,
    private val listener: Listener,
    private val source: String = "service",
) : SensorEventListener {

    interface Listener {
        fun onGesture(gesture: Gesture)
        fun onFlip(event: FlipDetector.Event) = Unit
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    val chop = ChopDetector()
    val twist = TwistDetector()
    private val pickup = PickupDetector()
    private val wave = WaveDetector()
    private val flip = FlipDetector()
    private val filter = GravityFilter()

    private var enabled: Set<Gesture> = emptySet()
    private var registered: Pair<Set<Int>, Boolean> = emptySet<Int>() to false
    private var accelForChop = false
    private val chopSensor: Sensor? = hardwareChopSensor(sensors)

    /** True while chops come from the phone's own chop sensor instead of [ChopDetector]. */
    @Volatile var hardwareChop = false
        private set
    private var lastHardwareChop = 0L

    /** True when a registered sensor only delivers while the CPU is awake (needs a wake lock). */
    var needsCpu = false
        private set
    private var accelForPose = false
    private var proximityRange = 5f

    /** Samples per second of the chop input sensor (0 = none arriving) and what it is. */
    @Volatile var hz = 0
        private set
    @Volatile var sensorName = ""
        private set
    @Volatile var lastSampleAt = 0L
        private set
    @Volatile var proximityNear = false
        private set
    private var countStart = 0L
    private var count = 0

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
        val needChop = Gesture.CHOP in gestures
        val needPose = Gesture.PICKUP in gestures || Gesture.FLIP in gestures
        val needProx = Gesture.WAVE in gestures || (needChop && prefs.pocketGate)
        val wakeUp = prefs.screenOff
        val hw = needChop && prefs.chopHardware && chopSensor != null
        hardwareChop = hw
        // The Test screen keeps the motion stream for its live meter and Calibrate.
        val needStream = needChop && (!hw || source != "service")
        val linear = pick(Sensor.TYPE_LINEAR_ACCELERATION, wakeUp)
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelForChop = needStream && linear == null
        accelForPose = needPose && gravity == null
        val want = buildSet {
            if (hw) add(chopSensor!!.type)
            if (needStream && linear != null) add(Sensor.TYPE_LINEAR_ACCELERATION)
            if (needPose && gravity != null) add(Sensor.TYPE_GRAVITY)
            if (accelForChop || accelForPose) add(Sensor.TYPE_ACCELEROMETER)
            if (Gesture.TWIST in gestures) add(Sensor.TYPE_GYROSCOPE)
            if (needProx) add(Sensor.TYPE_PROXIMITY)
        }
        if (!needProx) {
            chop.blocked = false
            proximityNear = false
        }
        needsCpu = want.any { it != Sensor.TYPE_PROXIMITY && it != chopSensor?.type }
        if (source == "service") Diag.serviceHardwareChop = hw
        val key = want to wakeUp
        if (key == registered) return
        sensors.unregisterListener(this)
        registered = key
        sensorName = ""
        for (type in want) {
            val s = when (type) {
                Sensor.TYPE_PROXIMITY -> sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type)
                Sensor.TYPE_LINEAR_ACCELERATION -> linear
                Sensor.TYPE_ACCELEROMETER -> pick(type, wakeUp)
                chopSensor?.type -> chopSensor
                else -> sensors.getDefaultSensor(type)
            } ?: continue
            val ok = if (type == Sensor.TYPE_PROXIMITY || s === chopSensor) {
                if (type == Sensor.TYPE_PROXIMITY) proximityRange = s.maximumRange
                sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                // 100 Hz where the phone allows it, never batched (max report latency 0).
                sensors.registerListener(this, s, 10_000, 0)
            }
            Log.i(TAG, "$source: ${s.name} type=$type wakeUp=${s.isWakeUpSensor} registered=$ok")
            if (type == Sensor.TYPE_LINEAR_ACCELERATION || (type == Sensor.TYPE_ACCELEROMETER && accelForChop)) {
                sensorName = (if (type == Sensor.TYPE_LINEAR_ACCELERATION) "linear acceleration" else "accelerometer + filter") +
                    if (s.isWakeUpSensor) " (wake-up)" else ""
            }
        }
        hz = 0
        count = 0
        countStart = 0L
    }

    private fun pick(type: Int, wakeUp: Boolean): Sensor? =
        (if (wakeUp) sensors.getDefaultSensor(type, true) else null) ?: sensors.getDefaultSensor(type)

    fun stop() {
        sensors.unregisterListener(this)
        registered = emptySet<Int>() to false
        hz = 0
    }

    private fun applyTuning() {
        val scale = prefs.scale
        chop.threshold = if (training == Gesture.CHOP) ChopDetector.TRAIN_THRESHOLD else prefs.chopThreshold
        chop.axis = if (training != Gesture.CHOP && prefs.chopAxisOnly) prefs.chopAxis else null
        twist.scale = if (training == Gesture.TWIST) 1f else scale
        twist.base = if (training == Gesture.TWIST) TwistDetector.TRAIN_BASE else prefs.calibration(Gesture.TWIST) ?: TwistDetector.DEFAULT_BASE
        pickup.scale = scale
    }

    /** Ignores chop / twist input for [ms], e.g. while our own vibration shakes the phone. */
    fun mute(ms: Long) {
        val until = SystemClock.elapsedRealtimeNanos() + ms * 1_000_000L
        chop.mute(until)
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
        if (event.sensor === chopSensor || (chopSensor != null && event.sensor.type == chopSensor.type)) {
            onHardwareChop(t)
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                countSample()
                feedChop(t, v[0], v[1], v[2])
            }
            Sensor.TYPE_GRAVITY -> feedPose(t, v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                filter.update(t, v[0], v[1], v[2])
                if (accelForChop) {
                    countSample()
                    feedChop(t, filter.linear[0], filter.linear[1], filter.linear[2])
                }
                if (accelForPose) feedPose(t, filter.gravity[0], filter.gravity[1], filter.gravity[2])
            }
            Sensor.TYPE_GYROSCOPE -> if (Gesture.TWIST in enabled && twist.add(t, v[0], v[1], v[2])) {
                emit(Gesture.TWIST)
            }
            Sensor.TYPE_PROXIMITY -> {
                // Some phones report a max range of 8 but 5 for "far": only a small value is near.
                val near = v[0] < minOf(proximityRange, 3f)
                proximityNear = near
                chop.blocked = near && prefs.pocketGate && Gesture.CHOP in enabled
                if (Gesture.WAVE in enabled && wave.add(t, near)) emit(Gesture.WAVE)
            }
        }
    }

    private fun countSample() {
        val now = SystemClock.elapsedRealtime()
        lastSampleAt = now
        if (countStart == 0L) countStart = now
        count++
        if (now - countStart >= 1000) {
            hz = (count * 1000L / (now - countStart)).toInt()
            count = 0
            countStart = now
            if (source == "service") {
                Diag.serviceHz = hz
                Diag.serviceSensor = sensorName
                Diag.serviceSampleAt = now
            }
        }
    }

    private fun emit(g: Gesture) {
        Diag.gesture(g.key, source)
        listener.onGesture(g)
    }

    private fun feedChop(t: Long, x: Float, y: Float, z: Float) {
        val fired = Gesture.CHOP in enabled && chop.add(t, x, y, z)
        // With the hardware sensor the stream only feeds the meter, except while calibrating.
        if (fired && (!hardwareChop || training == Gesture.CHOP)) emit(Gesture.CHOP)
    }

    private fun onHardwareChop(t: Long) {
        Log.i(TAG, "$source: hardware chop")
        if (!hardwareChop || Gesture.CHOP !in enabled || training == Gesture.CHOP || chop.blocked) return
        if (t - lastHardwareChop < HARDWARE_GAP_NS) return
        lastHardwareChop = t
        emit(Gesture.CHOP)
    }

    private fun feedPose(t: Long, x: Float, y: Float, z: Float) {
        if (Gesture.PICKUP in enabled && pickup.add(t, x, y, z)) emit(Gesture.PICKUP)
        if (Gesture.FLIP in enabled) flip.add(t, z)?.let { listener.onFlip(it) }
    }

    companion object {
        private const val TAG = "FlickEngine"

        /** Distinct feel per gesture: off/on durations in ms, starting with a pause. */
        val PATTERNS: Map<Gesture, LongArray> = mapOf(
            Gesture.CHOP to longArrayOf(0, 30, 70, 30), // tick-tick
            Gesture.TWIST to longArrayOf(0, 70), // one firm pulse
            Gesture.PICKUP to longArrayOf(0, 20), // light tap
            Gesture.WAVE to longArrayOf(0, 20),
            Gesture.FLIP to longArrayOf(0, 25, 60, 25, 60, 90), // short short long
        )
        private const val MUTE_AFTER = 250L
        private const val HARDWARE_GAP_NS = 400_000_000L
        const val CHOP_SENSOR_TYPE = "com.motorola.sensor.chopchop"

        /** Motorola's own chop-chop gesture sensor, or null on other phones. */
        fun hardwareChopSensor(sensors: SensorManager): Sensor? =
            sensors.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == CHOP_SENSOR_TYPE }
    }
}
