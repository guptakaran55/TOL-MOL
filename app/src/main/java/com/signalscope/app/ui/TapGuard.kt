package com.signalscope.app.ui

import android.content.Context
import android.content.Intent
import com.signalscope.app.trading.LabNotifier

/**
 * A NOTIFICATION TAP IS ACTED ON ONCE.
 *
 * When Android restarts the app — after killing it for memory, which on this
 * phone happened several times a day (30 Oct 2026) — it hands the restarted screen
 * the intent that originally launched it. If that was a notification tap, the app
 * opened that stock's detail view again, in the middle of whatever the user was
 * doing. Clearing the extras after use did not help: that clears the app's own
 * copy, not the one Android keeps to restart it with.
 *
 * Each tap now carries a number ([LabNotifier.EXTRA_TAP_ID]); the numbers already
 * used are remembered on disk, so a replay is recognised even by a new process.
 * A tap from a notification posted before numbers existed is recognised by where
 * it leads, for half a day.
 */
object TapGuard {
    private const val PREFS = "tap_guard"
    private const val USED = "used"
    private const val LEGACY = "legacy"
    private const val KEEP = 40
    const val LEGACY_WINDOW_MS = 12 * 3600_000L

    private val DEST = listOf(LabNotifier.EXTRA_OPEN_NS, LabNotifier.EXTRA_OPEN_SYMBOL,
        LabNotifier.EXTRA_OPEN_WATCH_SYMBOL, LabNotifier.EXTRA_OPEN_TAB)

    /** Pure — tested. [used] newest last; [legacy] "where|when" of the last numberless tap. */
    fun isReplay(tapId: String?, where: String, fromHistory: Boolean, used: List<String>,
                 legacy: String?, now: Long): Boolean {
        if (fromHistory) return true
        if (tapId != null) return tapId in used
        val last = legacy?.substringBeforeLast('|') ?: return false
        val at = legacy.substringAfterLast('|').toLongOrNull() ?: return false
        return last == where && now - at < LEGACY_WINDOW_MS
    }

    /**
     * True when [src] carries a destination this app has already gone to — its
     * destination extras are then removed and nothing should be opened. A fresh
     * tap is recorded as used and false is returned.
     */
    fun replayed(ctx: Context, src: Intent): Boolean {
        if (DEST.none { src.hasExtra(it) }) return false
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = p.getString(USED, "").orEmpty().split('\n').filter { it.isNotBlank() }
        val tapId = src.getStringExtra(LabNotifier.EXTRA_TAP_ID)
        val where = (DEST + LabNotifier.EXTRA_OPEN_SPACE).joinToString("|") { src.getStringExtra(it).orEmpty() }
        val fromHistory = (src.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val now = System.currentTimeMillis()
        if (isReplay(tapId, where, fromHistory, used, p.getString(LEGACY, null), now)) {
            (DEST + LabNotifier.EXTRA_OPEN_SPACE + LabNotifier.EXTRA_TAP_ID).forEach { src.removeExtra(it) }
            return true
        }
        val e = p.edit()
        if (tapId != null) e.putString(USED, (used + tapId).takeLast(KEEP).joinToString("\n"))
        else e.putString(LEGACY, "$where|$now")
        e.commit()       // on disk before the jump: a kill a moment later must still find it
        return false
    }
}
