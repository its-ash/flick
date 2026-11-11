package com.itsash.flick

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/**
 * Live feed for the "Test gestures" screen (EventChannel com.itsash.flick/probe).
 * While it runs, EventsService.paused is set so the real actions do not fire.
 *
 * Events (maps): {type: "frame", shake, shakeThr, swings, swingsNeeded, twist, twistThr} every 50 ms;
 * {type: "gesture", gesture}; {type: "rep", gesture, n}; {type: "trained", gesture, value}.
 */
class GestureProbe(context: Context) : EventChannel.StreamHandler, GestureEngine.Listener {

    private val prefs = EventsPrefs(context)
    private val engine = GestureEngine(context, prefs, this)
    private val main = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var training: Gesture? = null
    private val peaks = ArrayList<Float>()

    private val tick = object : Runnable {
        override fun run() {
            sink?.success(
                mapOf(
                    "type" to "frame",
                    "shake" to engine.shake.level.toDouble(),
                    "shakeThr" to engine.shake.threshold.toDouble(),
                    "swings" to engine.shake.swings,
                    "swingsNeeded" to engine.shake.swingsNeeded,
                    "twist" to engine.twist.level.toDouble(),
                    "twistThr" to engine.twist.threshold.toDouble(),
                ),
            )
            main.postDelayed(this, 50)
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        EventsService.paused = true
        engine.configure(setOf(Gesture.SHAKE, Gesture.TWIST))
        main.post(tick)
    }

    override fun onCancel(arguments: Any?) = stop()

    fun stop() {
        main.removeCallbacks(tick)
        engine.stop()
        sink = null
        training = null
        EventsService.paused = false
    }

    fun startTraining(g: Gesture) {
        if (g != Gesture.SHAKE && g != Gesture.TWIST) return
        training = g
        peaks.clear()
        engine.training = g
    }

    fun stopTraining() {
        training = null
        peaks.clear()
        engine.training = null
    }

    override fun onGesture(gesture: Gesture) {
        engine.buzz(gesture, prefs.vibrate)
        val t = training
        if (t == null || t != gesture) {
            sink?.success(mapOf("type" to "gesture", "gesture" to gesture.key))
            return
        }
        peaks.add(if (t == Gesture.SHAKE) engine.shake.lastPeak else engine.twist.lastPeak)
        sink?.success(mapOf("type" to "rep", "gesture" to t.key, "n" to peaks.size))
        if (peaks.size >= REPS) {
            val value = if (t == Gesture.SHAKE) {
                Calibration.threshold(peaks, ShakeDetector.MIN_BASE, ShakeDetector.MAX_BASE, ShakeDetector.TRAIN_FRACTION)
            } else {
                Calibration.threshold(peaks, TwistDetector.MIN_BASE, TwistDetector.MAX_BASE)
            }
            prefs.setCalibration(t, value)
            stopTraining()
            sink?.success(mapOf("type" to "trained", "gesture" to t.key, "value" to value?.toDouble()))
        }
    }

    companion object {
        const val REPS = 3
    }
}
