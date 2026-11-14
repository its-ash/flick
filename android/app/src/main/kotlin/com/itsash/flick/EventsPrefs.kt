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

    init {
        migrate()
    }

    /**
     * v2 (chop twice): drops everything the shake / old chop versions saved, so a stale trained
     * value, disabled gesture or low sensitivity can not keep the flashlight gesture from firing.
     */
    private fun migrate() {
        val version = prefs.getInt(KEY_VERSION, 0)
        if (version >= 3) return
        prefs.edit().apply {
            if (version < 2) {
                for (k in listOf("calib.shake", "calib.chop", "gesture.shake.enabled", "gesture.shake.action", "gesture.chop.enabled", "gesture.chop.action")) remove(k)
            }
            // v3: the chop detector was retuned on Motorola's chop-chop (trigger levels 15-60 instead
            // of 6-20), so an old level, trained value or learned direction no longer applies.
            putString(KEY_SENSITIVITY, "normal")
            putFloat(KEY_CHOP_THRESHOLD, ChopDetector.NORMAL)
            putBoolean("chop.trained", false)
            putBoolean(KEY_AXIS_ONLY, false)
            remove("chop.ax"); remove("chop.ay"); remove("chop.az")
            putInt(KEY_VERSION, 3)
        }.apply()
    }

    /** Chop trigger level in m/s² (15-60). Low / Normal / High set 40 / 30 / 22; the slider sets any value. */
    var chopThreshold: Float
        get() = prefs.getFloat(KEY_CHOP_THRESHOLD, ChopDetector.NORMAL).coerceIn(ChopDetector.MIN_THRESHOLD, ChopDetector.MAX_THRESHOLD)
        set(v) = prefs.edit().putFloat(KEY_CHOP_THRESHOLD, v.coerceIn(ChopDetector.MIN_THRESHOLD, ChopDetector.MAX_THRESHOLD)).apply()

    /** Ignore chops while the proximity sensor is covered (pocket). Off by default. */
    var pocketGate: Boolean
        get() = prefs.getBoolean(KEY_POCKET, false)
        set(v) = prefs.edit().putBoolean(KEY_POCKET, v).apply()

    /** Only accept chops near the direction learned in Calibrate. Off by default. */
    var chopAxisOnly: Boolean
        get() = prefs.getBoolean(KEY_AXIS_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_AXIS_ONLY, v).apply()

    var chopAxis: FloatArray?
        get() = if (prefs.contains("chop.ax")) floatArrayOf(prefs.getFloat("chop.ax", 1f), prefs.getFloat("chop.ay", 0f), prefs.getFloat("chop.az", 0f)) else null
        set(v) = prefs.edit().apply {
            if (v == null) {
                remove("chop.ax"); remove("chop.ay"); remove("chop.az")
            } else {
                putFloat("chop.ax", v[0]); putFloat("chop.ay", v[1]); putFloat("chop.az", v[2])
            }
        }.apply()

    /** Use the phone's own chop sensor (Motorola) when it has one. On by default. */
    var chopHardware: Boolean
        get() = prefs.getBoolean(KEY_CHOP_HARDWARE, true)
        set(v) = prefs.edit().putBoolean(KEY_CHOP_HARDWARE, v).apply()

    var chopTrained: Boolean
        get() = prefs.getBoolean("chop.trained", false)
        set(v) = prefs.edit().putBoolean("chop.trained", v).apply()

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

    /** Trained trigger strength for [g] (twist: rad/s), or null when not trained. */
    fun calibration(g: Gesture): Float? = prefs.getFloat("calib.${g.key}", 0f).takeIf { it > 0f }

    fun setCalibration(g: Gesture, value: Float?) {
        prefs.edit().apply { if (value == null) remove("calib.${g.key}") else putFloat("calib.${g.key}", value) }.apply()
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
        "chopThreshold" to chopThreshold.toDouble(),
        "pocketGate" to pocketGate,
        "chopAxisOnly" to chopAxisOnly,
        "chopHardware" to chopHardware,
        "hasAxis" to (chopAxis != null),
        "gestures" to Gesture.entries.associate {
            it.key to mapOf("enabled" to isEnabled(it), "action" to actionOf(it).key)
        },
        "trained" to mapOf(
            Gesture.CHOP.key to chopTrained,
            Gesture.TWIST.key to (calibration(Gesture.TWIST) != null),
        ),
    )

    companion object {
        const val FILE = "flick_prefs"
        const val KEY_ENABLED = "service_enabled"
        const val KEY_SCREEN_OFF = "screen_off"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_SAVED_DND = "saved_dnd_filter"
        const val KEY_VERSION = "prefs_version"
        const val KEY_CHOP_THRESHOLD = "chop.threshold"
        const val KEY_POCKET = "chop.pocket_gate"
        const val KEY_AXIS_ONLY = "chop.axis_only"
        const val KEY_CHOP_HARDWARE = "chop.hardware"
    }
}
