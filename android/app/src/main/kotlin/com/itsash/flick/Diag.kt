package com.itsash.flick

import android.os.SystemClock

/**
 * Live facts about the background path, shown in the Diagnostics card of the Test gestures
 * screen. Written by EventsService / ActionRunner / GestureEngine, read by GestureProbe.
 * Times are SystemClock.elapsedRealtime() (ms), 0 = never.
 */
object Diag {
    @Volatile var serviceHz = 0
    @Volatile var serviceSensor = ""
    @Volatile var serviceSampleAt = 0L

    @Volatile var lastGesture = ""
    @Volatile var lastGestureAt = 0L
    @Volatile var lastGestureSource = ""

    @Volatile var lastAction = ""
    @Volatile var lastActionAt = 0L
    @Volatile var lastActionError: String? = null

    @Volatile var torchAvailable = false
    @Volatile var torchOn = false

    fun gesture(name: String, source: String) {
        lastGesture = name
        lastGestureAt = SystemClock.elapsedRealtime()
        lastGestureSource = source
    }

    fun action(name: String, error: String?) {
        lastAction = name
        lastActionAt = SystemClock.elapsedRealtime()
        lastActionError = error
    }

    /** Seconds since [at], or -1 when never. */
    fun ago(at: Long): Long = if (at == 0L) -1 else (SystemClock.elapsedRealtime() - at) / 1000
}
