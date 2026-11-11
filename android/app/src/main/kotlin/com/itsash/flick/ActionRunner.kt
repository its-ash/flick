package com.itsash.flick

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

private const val TAG = "FlickAction"

/** Runs a gesture's action. Lives as long as EventsService. */
class ActionRunner(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val torchId: String? = findTorchCamera()
    private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                Diag.torchOn = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchId) Diag.action("flashlight", "Torch unavailable (camera in use by another app)")
        }
    }

    init {
        Diag.torchAvailable = torchId != null
        cameras?.registerTorchCallback(torchCallback, main)
    }

    fun release() {
        cameras?.unregisterTorchCallback(torchCallback)
    }

    /** Runs [action]. Returns null on success or a short error text; also recorded in [Diag]. */
    fun run(action: Action): String? {
        var error: String? = null
        try {
            when (action) {
                Action.WAKE -> wakeScreen()
                Action.FLASHLIGHT -> error = toggleTorch()
                Action.CAMERA -> openCamera()
                Action.MEDIA -> playPause()
                Action.DND -> toggleDnd()
            }
        } catch (e: Throwable) {
            // A failed action (camera in use, permission revoked) must never stop the service.
            error = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "action ${action.key} failed", e)
        }
        Diag.action(action.key, error)
        return error
    }

    /** Back camera with a flash; else any camera with a flash. Each camera is probed on its own. */
    private fun findTorchCamera(): String? {
        val mgr = cameras ?: return null
        val ids = try {
            mgr.cameraIdList
        } catch (e: Throwable) {
            Log.w(TAG, "cameraIdList failed", e)
            return null
        }
        var anyFlash: String? = null
        for (id in ids) {
            try {
                val c = mgr.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
                if (anyFlash == null) anyFlash = id
            } catch (e: Throwable) {
                Log.w(TAG, "camera $id unreadable", e)
            }
        }
        return anyFlash
    }

    private fun toggleTorch(): String? {
        val id = torchId ?: return "This phone has no flashlight"
        val target = !torchOn
        return try {
            cameras?.setTorchMode(id, target)
            Log.i(TAG, "torch -> $target")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "setTorchMode failed", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Turns the screen on (the lock screen shows if the phone is locked). */
    @Suppress("DEPRECATION")
    fun wakeScreen() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Flick:wake",
        ).acquire(3000)
    }

    private fun openCamera() {
        wakeScreen()
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val intent = Intent(
            if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startFromBackground(intent)
    }

    /**
     * Android blocks activity starts from the background. Apps with "Display over
     * other apps" may start them while they show an overlay window (required from
     * Android 15), so a 1 px invisible overlay is shown for the moment of the start.
     */
    private fun startFromBackground(intent: Intent) {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(intent) // works only if Android allows it
            return
        }
        val wm = context.getSystemService(WindowManager::class.java)
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        main.post {
            var added = false
            try {
                wm.addView(view, params)
                added = true
            } catch (_: Throwable) {
            }
            main.postDelayed({
                try {
                    context.startActivity(intent)
                } catch (_: Throwable) {
                }
                main.postDelayed({ if (added) runCatching { wm.removeView(view) } }, 600)
            }, 60)
        }
    }

    private fun playPause() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    }

    private fun notifications(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)?.takeIf { it.isNotificationPolicyAccessGranted }

    /** Do Not Disturb on/off. Needs "Do Not Disturb access". */
    private fun toggleDnd() {
        val nm = notifications() ?: return
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
    }

    /** Face down: turn on Do Not Disturb, remembering the previous mode. */
    fun dndForFaceDown(prefs: EventsPrefs) {
        val nm = notifications() ?: return
        prefs.savedDndFilter = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    /** Face up again: restore the mode from before, unless the user changed it meanwhile. */
    fun dndForFaceUp(prefs: EventsPrefs) {
        val nm = notifications() ?: return
        val saved = prefs.savedDndFilter
        if (saved > 0 && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            nm.setInterruptionFilter(saved)
        }
        prefs.savedDndFilter = -1
    }
}
