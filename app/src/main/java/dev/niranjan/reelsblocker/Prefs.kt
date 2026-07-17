package dev.niranjan.reelsblocker

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("reelsblocker", Context.MODE_PRIVATE)

    var blockingEnabled: Boolean
        get() = sp.getBoolean("blockingEnabled", true)
        set(value) = sp.edit().putBoolean("blockingEnabled", value).apply()

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
