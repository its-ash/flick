package com.itsash.flick

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import kotlin.math.sqrt

/**
 * Live feed for the "Test gestures" screen (EventChannel com.itsash.flick/probe).
 * While it runs, EventsService.paused is renewed so the real actions do not fire.
 *
 * Events (maps), see [frame] for the keys: {type: "frame", ...} every 50 ms;
 * {type: "gesture", gesture}; {type: "rep", gesture, n}; {type: "trained", gesture, value}.
 */
class GestureProbe(context: Context) : EventChannel.StreamHandler, GestureEngine.Listener {

    private val appContext = context.applicationContext
    private val prefs = EventsPrefs(context)
    private val engine = GestureEngine(context, prefs, this, "test")
    private val main = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var runner: ActionRunner? = null
    private var training: Gesture? = null
    private val peaks = ArrayList<Float>()
    private val axisSum = FloatArray(3)

    private val tick = object : Runnable {
        override fun run() {
            EventsService.paused = true // renew the lease
            sink?.success(frame())
            main.postDelayed(this, 50)
        }
    }

    private fun frame(): Map<String, Any?> {
        val c = engine.chop
        return mapOf(
            "type" to "frame",
            "chop" to c.level.toDouble(),
            "chopThr" to c.threshold.toDouble(),
            "noise" to c.noiseLevel.toDouble(),
            "pending" to c.pendingChops,
            "burstPeak" to c.burstPeak.toDouble(),
            "burstSeq" to c.burstSeq,
            "chopPeak" to c.lastChopPeak.toDouble(),
            "chopSeq" to c.chopSeq,
            "twist" to engine.twist.level.toDouble(),
            "twistThr" to engine.twist.threshold.toDouble(),
            // Diagnostics
            "serviceRunning" to EventsService.running,
            "serviceEnabled" to prefs.serviceEnabled,
            "serviceHz" to Diag.serviceHz,
            "serviceSensor" to Diag.serviceSensor,
            "serviceHardware" to (EventsService.running && Diag.serviceHardwareChop),
            "serviceSampleAgo" to Diag.ago(Diag.serviceSampleAt),
            "probeHz" to engine.hz,
            "probeSensor" to engine.sensorName,
            "near" to engine.proximityNear,
            "pocketGate" to prefs.pocketGate,
            "torchAvailable" to Diag.torchAvailable,
            "torchOn" to Diag.torchOn,
            "lastGesture" to Diag.lastGesture,
            "lastGestureAgo" to Diag.ago(Diag.lastGestureAt),
            "lastGestureSource" to Diag.lastGestureSource,
            "lastAction" to Diag.lastAction,
            "lastActionAgo" to Diag.ago(Diag.lastActionAt),
            "lastActionError" to Diag.lastActionError,
        )
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        EventsService.paused = true
        runner = ActionRunner(appContext)
        engine.configure(setOf(Gesture.CHOP, Gesture.TWIST))
        main.post(tick)
    }

    override fun onCancel(arguments: Any?) = stop()

    fun stop() {
        main.removeCallbacks(tick)
        engine.stop()
        runner?.release()
        runner = null
        sink = null
        training = null
        EventsService.paused = false
    }

    fun startTraining(g: Gesture) {
        if (g != Gesture.CHOP && g != Gesture.TWIST) return
        training = g
        peaks.clear()
        axisSum.fill(0f)
        engine.training = g
    }

    fun stopTraining() {
        training = null
        peaks.clear()
        engine.training = null
    }

    /** Back to the Normal preset: no trained level, no learned direction. */
    fun resetChop() {
        prefs.chopTrained = false
        prefs.chopAxis = null
        prefs.chopAxisOnly = false
        prefs.chopThreshold = ChopDetector.NORMAL
        stopTraining()
    }

    override fun onGesture(gesture: Gesture) {
        engine.buzz(gesture, prefs.vibrate)
        val t = training
        if (t == null || t != gesture) {
            sink?.success(mapOf("type" to "gesture", "gesture" to gesture.key))
            return
        }
        if (t == Gesture.CHOP) {
            peaks.add(engine.chop.lastPeak)
            val a = engine.chop.lastAxis
            // Axis has no sign: align every rep with the first one before adding.
            val dot = a[0] * axisSum[0] + a[1] * axisSum[1] + a[2] * axisSum[2]
            val s = if (dot < 0f) -1f else 1f
            for (i in 0..2) axisSum[i] += s * a[i]
        } else {
            peaks.add(engine.twist.lastPeak)
        }
        sink?.success(mapOf("type" to "rep", "gesture" to t.key, "n" to peaks.size))
        if (peaks.size >= REPS) {
            val value = if (t == Gesture.CHOP) {
                Calibration.threshold(peaks, ChopDetector.MIN_THRESHOLD, ChopDetector.MAX_THRESHOLD, ChopDetector.TRAIN_FRACTION)
            } else {
                Calibration.threshold(peaks, TwistDetector.MIN_BASE, TwistDetector.MAX_BASE)
            }
            if (t == Gesture.CHOP) {
                if (value != null) prefs.chopThreshold = value
                prefs.chopTrained = value != null
                val n = sqrt(axisSum[0] * axisSum[0] + axisSum[1] * axisSum[1] + axisSum[2] * axisSum[2])
                prefs.chopAxis = if (n > 1e-3f) floatArrayOf(axisSum[0] / n, axisSum[1] / n, axisSum[2] / n) else null
            } else {
                prefs.setCalibration(t, value)
            }
            stopTraining()
            sink?.success(mapOf("type" to "trained", "gesture" to t.key, "value" to value?.toDouble()))
        }
    }

    companion object {
        const val REPS = 3
    }
}
