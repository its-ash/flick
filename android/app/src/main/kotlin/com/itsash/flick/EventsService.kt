package com.itsash.flick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.TileService

/**
 * Foreground service that listens to the motion and proximity sensors and runs
 * the configured action when a gesture is detected. Started from the app, the
 * Quick Settings tile and on boot; it reconfigures itself when settings change.
 */
class EventsService : Service(), SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: EventsPrefs
    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager
    private lateinit var runner: ActionRunner
    private var wakeLock: PowerManager.WakeLock? = null

    private val gravity = GravityFilter()
    private var chop = ChopDetector()
    private var twist = TwistDetector()
    private var pickup = PickupDetector()
    private val wave = WaveDetector()
    private val flip = FlipDetector()
    private var proximityRange = 5f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = EventsPrefs(this)
        sensors = getSystemService(SensorManager::class.java)
        power = getSystemService(PowerManager::class.java)
        runner = ActionRunner(this)
        startInForeground()
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        configure()
        running = true
        refreshTile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.serviceEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        sensors.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        runner.release()
        refreshTile()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == EventsPrefs.KEY_SAVED_DND) return
        if (!prefs.serviceEnabled) {
            stopSelf()
            return
        }
        configure()
    }

    /** (Re)registers only the sensors the enabled gestures need. */
    private fun configure() {
        sensors.unregisterListener(this)
        val scale = prefs.scale
        chop = ChopDetector(scale)
        twist = TwistDetector(scale)
        pickup = PickupDetector(scale)

        val needAccel = prefs.isEnabled(Gesture.CHOP) || prefs.isEnabled(Gesture.PICKUP) || prefs.isEnabled(Gesture.FLIP)
        if (needAccel) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (prefs.isEnabled(Gesture.TWIST)) {
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (prefs.isEnabled(Gesture.WAVE)) {
            // The wake-up variant keeps working while the CPU sleeps.
            (sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY))?.let {
                proximityRange = it.maximumRange
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        // Non-wake-up sensors stop reporting when the CPU sleeps with the screen off.
        if (prefs.screenOff) {
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flick:sensors").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                gravity.update(event.values[0], event.values[1], event.values[2])
                val g = gravity.gravity
                if (prefs.isEnabled(Gesture.CHOP) && chop.add(t, gravity.linearMagnitude())) fire(Gesture.CHOP)
                if (prefs.isEnabled(Gesture.PICKUP) && pickup.add(t, g[0], g[1], g[2]) && !power.isInteractive) {
                    fire(Gesture.PICKUP)
                }
                if (prefs.isEnabled(Gesture.FLIP)) {
                    when (flip.add(t, g[2])) {
                        FlipDetector.Event.FACE_DOWN -> {
                            buzz()
                            runner.dndForFaceDown(prefs)
                        }
                        FlipDetector.Event.FACE_UP -> runner.dndForFaceUp(prefs)
                        null -> Unit
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (twist.add(t, event.values[1])) fire(Gesture.TWIST)
            }
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < proximityRange
                if (wave.add(t, near) && !power.isInteractive) fire(Gesture.WAVE)
            }
        }
    }

    private fun fire(gesture: Gesture) {
        buzz()
        runner.run(prefs.actionOf(gesture))
    }

    private fun buzz() {
        if (!prefs.vibrate) return
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gestures running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Flick listens for gestures."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_events)
            .setContentTitle("Gestures are on")
            .setContentText("Tap to change which gestures Flick listens for.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, EventsTileService::class.java))
    }

    companion object {
        private const val CHANNEL = "events_service"
        private const val NOTIFICATION_ID = 7

        /** True while the service is alive in this process. */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EventsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EventsService::class.java))
        }

        /** Applies the "gestures on" setting: starts or stops the service. */
        fun sync(context: Context) {
            if (EventsPrefs(context).serviceEnabled) start(context) else stop(context)
        }
    }
}
