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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService

/**
 * Foreground service that listens to the motion and proximity sensors and runs
 * the configured action when a gesture is detected. Started from the app, the
 * Quick Settings tile and on boot; it reconfigures itself when settings change.
 */
class EventsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: EventsPrefs
    private lateinit var power: PowerManager
    private lateinit var runner: ActionRunner
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var engine: GestureEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = EventsPrefs(this)
        power = getSystemService(PowerManager::class.java)
        runner = ActionRunner(this)
        engine = GestureEngine(this, prefs, object : GestureEngine.Listener {
            override fun onGesture(gesture: Gesture) = handle(gesture)

            override fun onFlip(event: FlipDetector.Event) {
                if (paused) return
                when (event) {
                    FlipDetector.Event.FACE_DOWN -> {
                        buzz(Gesture.FLIP)
                        runner.dndForFaceDown(prefs)
                    }
                    FlipDetector.Event.FACE_UP -> runner.dndForFaceUp(prefs)
                }
            }
        })
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
        engine.stop()
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

    /** Applies settings; the engine re-registers sensors only when the needed set changed. */
    private fun configure() {
        engine.configure(Gesture.entries.filter { prefs.isEnabled(it) }.toSet())

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

    private fun handle(gesture: Gesture) {
        if (paused) return // the test / train screen is open
        if ((gesture == Gesture.PICKUP || gesture == Gesture.WAVE) && power.isInteractive) return
        buzz(gesture)
        runner.run(prefs.actionOf(gesture))
    }

    /** Own-pattern vibration; the engine also mutes shake / twist while it runs. */
    private fun buzz(gesture: Gesture) = engine.buzz(gesture, prefs.vibrate)

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

        /** True while the test / train screen is open: gestures are detected there, not acted on. */
        @Volatile
        var paused = false

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
