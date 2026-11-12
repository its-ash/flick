package com.itsash.flick

import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * MethodChannel "com.itsash.flick/control" (Dart side: lib/events/events_platform.dart):
 *   - getConfig() -> {enabled, running, screenOff, vibrate, sensitivity, gestures: {key: {enabled, action}}}
 *   - setEnabled({enabled}): saves and starts/stops EventsService.
 *   - setOption({key, value}): "screenOff" | "vibrate" (bool), "sensitivity" (low/normal/high).
 *   - setGesture({gesture, enabled, action}).
 *   - status() -> {overlay, dnd, battery, accelerometer, gyroscope, proximity, tileSupported}
 *   - openPermission({name}): "overlay" | "dnd" | "battery".
 *   - tryAction({action}): runs an action now, for the "Try" buttons; returns an error text or null.
 *   - setOption also takes "chopThreshold" (double, 15-60), "pocketGate" and "chopAxisOnly" (bool).
 *   - addTile() -> asks Android 13+ to add the Quick Settings tile.
 *   - startTraining({gesture}) / stopTraining() / resetTraining({gesture}): personal strength
 *     (only meaningful while the probe stream below is listened to).
 * EventChannel "com.itsash.flick/probe": live motion levels for the Test gestures screen (GestureProbe).
 */
class MainActivity : FlutterActivity() {

    private val channelName = "com.itsash.flick/control"
    private var runner: ActionRunner? = null
    private var probe: GestureProbe? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val prefs = EventsPrefs(this)
        val probe = GestureProbe(applicationContext).also { this.probe = it }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "com.itsash.flick/probe").setStreamHandler(probe)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getConfig" -> result.success(prefs.toMap() + ("running" to EventsService.running))
                    "setEnabled" -> {
                        prefs.serviceEnabled = call.argument<Boolean>("enabled") ?: false
                        EventsService.sync(this)
                        result.success(null)
                    }
                    "setOption" -> {
                        when (call.argument<String>("key")) {
                            "screenOff" -> prefs.screenOff = call.argument<Boolean>("value") ?: true
                            "vibrate" -> prefs.vibrate = call.argument<Boolean>("value") ?: true
                            "sensitivity" -> {
                                val v = call.argument<String>("value") ?: "normal"
                                prefs.sensitivity = v
                                prefs.chopThreshold = ChopDetector.forSensitivity(v)
                                prefs.chopTrained = false
                            }
                            "chopThreshold" -> {
                                prefs.chopThreshold = (call.argument<Double>("value") ?: ChopDetector.NORMAL.toDouble()).toFloat()
                                prefs.chopTrained = false
                            }
                            "pocketGate" -> prefs.pocketGate = call.argument<Boolean>("value") ?: false
                            "chopAxisOnly" -> prefs.chopAxisOnly = call.argument<Boolean>("value") ?: false
                        }
                        result.success(null)
                    }
                    "setGesture" -> {
                        val g = Gesture.of(call.argument<String>("gesture"))
                        if (g == null) {
                            result.error("bad_gesture", "Unknown gesture", null)
                        } else {
                            prefs.setGesture(g, call.argument<Boolean>("enabled") ?: false, Action.of(call.argument<String>("action")))
                            result.success(null)
                        }
                    }
                    "status" -> result.success(status())
                    "openPermission" -> {
                        openPermission(call.argument<String>("name"))
                        result.success(null)
                    }
                    "tryAction" -> {
                        val action = Action.of(call.argument<String>("action"))
                        if (action != null) {
                            val r = runner ?: ActionRunner(applicationContext).also { runner = it }
                            result.success(r.run(action))
                        } else {
                            result.success(null)
                        }
                    }
                    "startTraining" -> {
                        Gesture.of(call.argument<String>("gesture"))?.let { probe.startTraining(it) }
                        result.success(null)
                    }
                    "stopTraining" -> {
                        probe.stopTraining()
                        result.success(null)
                    }
                    "resetTraining" -> {
                        val g = Gesture.of(call.argument<String>("gesture"))
                        if (g == Gesture.CHOP) probe.resetChop() else if (g != null) prefs.setCalibration(g, null)
                        probe.stopTraining() // re-applies thresholds
                        result.success(null)
                    }
                    "addTile" -> addTile(result)
                    else -> result.notImplemented()
                }
            } catch (e: Throwable) {
                result.error("failed", e.message, null)
            }
        }
    }

    override fun onDestroy() {
        probe?.stop()
        probe = null
        runner?.release()
        runner = null
        super.onDestroy()
    }

    private fun status(): Map<String, Any?> {
        val sensors = getSystemService(SensorManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        return mapOf(
            "overlay" to Settings.canDrawOverlays(this),
            "dnd" to nm.isNotificationPolicyAccessGranted,
            "battery" to power.isIgnoringBatteryOptimizations(packageName),
            "accelerometer" to (sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null),
            "gyroscope" to (sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null),
            "proximity" to (sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null),
            "tileSupported" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        )
    }

    private fun openPermission(name: String?) {
        val pkg = Uri.parse("package:$packageName")
        when (name) {
            "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            "dnd" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "battery" -> {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        }
    }

    private fun addTile(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result.success(false)
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, EventsTileService::class.java),
            "Flick",
            Icon.createWithResource(this, R.drawable.ic_stat_events),
            mainExecutor,
        ) { code -> result.success(code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) }
    }
}
