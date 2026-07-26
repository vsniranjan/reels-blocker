package dev.niranjan.reelsblocker

import android.content.Context

class Prefs(context: Context) {

    companion object {
        /** A pause lasts exactly this long — there is no indefinite off. */
        const val PAUSE_DURATION_MS = 5 * 60 * 1000L
    }

    private val sp = context.getSharedPreferences("reelsblocker", Context.MODE_PRIVATE)

    /** Wall-clock instant the current pause ends. 0 (or past) means blocking is on. */
    var pauseUntil: Long
        get() = sp.getLong("pauseUntil", 0L)
        private set(value) = sp.edit().putLong("pauseUntil", value).apply()

    /**
     * Clamped to the pause length: winding the system clock backwards would
     * otherwise leave a deadline years out and pause blocking forever.
     */
    val pauseRemainingMs: Long
        get() = (pauseUntil - System.currentTimeMillis()).coerceIn(0L, PAUSE_DURATION_MS)

    /** Read-only by design — nothing can switch blocking off except a timed pause. */
    val blockingEnabled: Boolean
        get() = pauseRemainingMs == 0L

    fun pause() {
        pauseUntil = System.currentTimeMillis() + PAUSE_DURATION_MS
    }

    fun resume() {
        pauseUntil = 0L
    }

    var dumpMode: Boolean
        get() = sp.getBoolean("dumpMode", false)
        set(value) = sp.edit().putBoolean("dumpMode", value).apply()

    val blockedToday: Int
        get() = if (sp.getString("blockedDate", "") == today()) sp.getInt("blockedToday", 0) else 0

    val blockedTotal: Int
        get() = sp.getInt("blockedTotal", 0)

    fun clearBlockedCounts() {
        sp.edit().remove("blockedDate").remove("blockedToday").remove("blockedTotal").apply()
    }

    fun recordBlocked() {
        val todayCount = blockedToday + 1
        sp.edit()
            .putString("blockedDate", today())
            .putInt("blockedToday", todayCount)
            .putInt("blockedTotal", blockedTotal + 1)
            .apply()
    }

    /** Timestamp of the last time any reel surface was detected (blocked or allowed). */
    var lastReelSignal: Long
        get() = sp.getLong("lastReelSignal", 0L)
        set(value) = sp.edit().putLong("lastReelSignal", value).apply()

    var lastBrokenWarningAt: Long
        get() = sp.getLong("lastBrokenWarningAt", 0L)
        set(value) = sp.edit().putLong("lastBrokenWarningAt", value).apply()

    /** Set once when the service first connects, so the watchdog has a baseline. */
    fun ensureBaseline() {
        if (lastReelSignal == 0L) lastReelSignal = System.currentTimeMillis()
    }

    private fun today(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
}
