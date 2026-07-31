package dev.niranjan.reelsblocker

import android.content.Context

/**
 * The pause lengths on offer, cheapest first. A pause costs a cooldown of
 * `cooldownFactor` times its length, during which every option but the free
 * five-minute one is locked — that is what stops a long pause from simply
 * being taken again the moment it ends.
 */
enum class PauseOption(val lengthMs: Long, val cooldownFactor: Int) {
    FIVE_MIN(5 * 60_000L, 0),
    FIFTEEN_MIN(15 * 60_000L, 1),
    THIRTY_MIN(30 * 60_000L, 1),
    ONE_DAY(24 * 60 * 60_000L, 2),
}

class Prefs(context: Context) {

    companion object {
        /** The longest cooldown any option can buy — a full-term ONE_DAY pause. */
        private val MAX_COOLDOWN_MS =
            PauseOption.ONE_DAY.lengthMs * PauseOption.ONE_DAY.cooldownFactor
    }

    private val sp = context.getSharedPreferences("reelsblocker", Context.MODE_PRIVATE)

    /** Wall-clock instant the current pause ends. 0 (or past) means blocking is on. */
    var pauseUntil: Long
        get() = sp.getLong("pauseUntil", 0L)
        private set(value) = sp.edit().putLong("pauseUntil", value).apply()

    /** The option the running pause was started with; FIVE_MIN for pre-picker deadlines. */
    val pauseOption: PauseOption
        get() = sp.getString("pauseOption", null)
            ?.let { runCatching { PauseOption.valueOf(it) }.getOrNull() }
            ?: PauseOption.FIVE_MIN

    /**
     * Clamped to the pause length: winding the system clock backwards would
     * otherwise leave a deadline years out and pause blocking forever.
     */
    val pauseRemainingMs: Long
        get() = (pauseUntil - System.currentTimeMillis()).coerceIn(0L, pauseOption.lengthMs)

    /** Read-only by design — nothing can switch blocking off except a timed pause. */
    val blockingEnabled: Boolean
        get() = pauseRemainingMs == 0L

    /** Instant the paid-for options unlock again. Same clamp reasoning as the pause. */
    private val cooldownUntil: Long
        get() = sp.getLong("cooldownUntil", 0L)

    val cooldownRemainingMs: Long
        get() = (cooldownUntil - System.currentTimeMillis()).coerceIn(0L, MAX_COOLDOWN_MS)

    /** The free option stays available always; everything else waits out the cooldown. */
    fun isLocked(option: PauseOption): Boolean =
        option.cooldownFactor > 0 && cooldownRemainingMs > 0

    /**
     * What was already owed when the running pause started. Held separately so
     * resume() can discount this pause's own charge without wiping a longer
     * lockout that the free five-minute option happened to be taken during.
     */
    private val cooldownBase: Long
        get() = sp.getLong("cooldownBase", 0L)

    fun pause(option: PauseOption) {
        val now = System.currentTimeMillis()
        val end = now + option.lengthMs
        sp.edit()
            .putLong("pauseUntil", end)
            .putLong("pauseStartedAt", now)
            .putString("pauseOption", option.name)
            .putLong("cooldownBase", cooldownUntil)
            // Provisional, assuming the pause runs its full term — that is what is
            // owed if nothing else happens. resume() rewrites it to the smaller
            // earned value.
            .putLong("cooldownUntil", maxOf(cooldownUntil, charge(end, option, option.lengthMs)))
            .apply()
    }

    /** Ends a running pause early, charging a cooldown for the time actually used. */
    fun resume() {
        val now = System.currentTimeMillis()
        // Nothing paused means nothing to charge for — and rewriting the cooldown
        // here would let a stale pauseStartedAt shorten a live one.
        if (pauseRemainingMs == 0L) {
            pauseUntil = 0L
            return
        }
        val option = pauseOption
        val used = (now - sp.getLong("pauseStartedAt", now)).coerceIn(0L, option.lengthMs)
        sp.edit()
            .putLong("pauseUntil", 0L)
            .putLong("cooldownUntil", maxOf(cooldownBase, charge(now, option, used)))
            .apply()
    }

    /**
     * When the lockout bought by [pausedMs] of pausing ends, counting from [from].
     * Zero for the free option: it must never create a cooldown, not even a short
     * one that would outlast the lockout it happened to be taken during.
     */
    private fun charge(from: Long, option: PauseOption, pausedMs: Long): Long =
        if (option.cooldownFactor == 0) 0L else from + option.cooldownFactor * pausedMs

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
