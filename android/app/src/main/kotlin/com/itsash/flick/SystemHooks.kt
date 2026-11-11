package com.itsash.flick

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Restarts the service after a reboot or an app update, if gestures are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (EventsPrefs(context).serviceEnabled) {
                    runCatching { EventsService.start(context) }
                }
            }
        }
    }
}

/** Quick Settings tile: turns gestures on and off from the notification shade. */
class EventsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val prefs = EventsPrefs(this)
        prefs.serviceEnabled = !prefs.serviceEnabled
        try {
            EventsService.sync(this)
        } catch (_: Throwable) {
            // Some Android versions refuse a foreground-service start here: open the app instead.
            prefs.serviceEnabled = false
            openApp()
        }
        update(prefs.serviceEnabled)
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun update(on: Boolean = EventsPrefs(this).serviceEnabled) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
