package com.itsash.flick

import android.content.Context
import android.content.SharedPreferences

/** What a gesture does. Keys are shared with lib/events/gesture.dart. */
enum class Action(val key: String) {
    WAKE("wake"),
    FLASHLIGHT("flashlight"),
    CAMERA("camera"),
    MEDIA("media"),
    DND("dnd");

    companion object {
        fun of(key: String?): Action? = entries.firstOrNull { it.key == key }
    }
}

/** The gestures. Flip always means "Do Not Disturb while face down", so its action is fixed. */
enum class Gesture(val key: String, val defaultEnabled: Boolean, val defaultAction: Action) {
    PICKUP("pickup", true, Action.WAKE),
    WAVE("wave", false, Action.WAKE),
    CHOP("chop", true, Action.FLASHLIGHT),
    TWIST("twist", true, Action.CAMERA),
    FLIP("flip", false, Action.DND);

    companion object {
        fun of(key: String?): Gesture? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Settings shared by the Flutter UI (through MainActivity), the Quick Settings
 * tile, the boot receiver and EventsService, which re-reads them on change.
 */
class EventsPrefs(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Keep the CPU awake for sensors while the screen is off. */
    var screenOff: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OFF, true)
        set(v) = prefs.edit().putBoolean(KEY_SCREEN_OFF, v).apply()

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATE, v).apply()

    /** "low", "normal" or "high". */
    var sensitivity: String
        get() = prefs.getString(KEY_SENSITIVITY, "normal") ?: "normal"
        set(v) = prefs.edit().putString(KEY_SENSITIVITY, v).apply()

    /** Threshold multiplier for the detectors: smaller = more sensitive. */
    val scale: Float
        get() = when (sensitivity) {
            "low" -> 1.3f
            "high" -> 0.75f
            else -> 1f
        }

    fun isEnabled(g: Gesture): Boolean = prefs.getBoolean("gesture.${g.key}.enabled", g.defaultEnabled)

    fun actionOf(g: Gesture): Action =
        if (g == Gesture.FLIP) Action.DND else Action.of(prefs.getString("gesture.${g.key}.action", null)) ?: g.defaultAction

    fun setGesture(g: Gesture, enabled: Boolean, action: Action?) {
        prefs.edit().apply {
            putBoolean("gesture.${g.key}.enabled", enabled)
            if (action != null && g != Gesture.FLIP) putString("gesture.${g.key}.action", action.key)
        }.apply()
    }

    /** Interruption filter to restore when the phone is turned face up again. */
    var savedDndFilter: Int
        get() = prefs.getInt(KEY_SAVED_DND, -1)
        set(v) = prefs.edit().putInt(KEY_SAVED_DND, v).apply()

    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to serviceEnabled,
        "screenOff" to screenOff,
        "vibrate" to vibrate,
        "sensitivity" to sensitivity,
        "gestures" to Gesture.entries.associate {
            it.key to mapOf("enabled" to isEnabled(it), "action" to actionOf(it).key)
        },
    )

    companion object {
        const val FILE = "flick_prefs"
        const val KEY_ENABLED = "service_enabled"
        const val KEY_SCREEN_OFF = "screen_off"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_SAVED_DND = "saved_dnd_filter"
    }
}
