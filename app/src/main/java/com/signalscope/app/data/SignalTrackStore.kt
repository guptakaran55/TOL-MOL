package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE RECORD OF EVERY TRACKED SIGNAL, and what the price did afterwards.
 *
 * Two rules are being tried out, and neither has earned the right to place an
 * order: "turning up at a floor" (alerted) and plain "all five dots green"
 * (logged only, for comparison). Each time one fires, the stock and price go
 * in here; the daily pass later fills in the return one, three and five
 * trading days on. A few weeks of this is the difference between a rule that
 * looks right and one that has shown it makes money.
 *
 * SAVED SAFELY. Written to a temporary file and swapped in, so a phone killed
 * mid-save leaves the previous record whole. A file that cannot be read is set
 * aside under another name rather than being overwritten with an empty list.
 */
object SignalTrackStore {
    private const val TAG = "SignalTrackStore"
    private const val FILE = "signal_track.json"
    /** Newest kept; older events fall off. Months of history at a few a day. */
    const val MAX = 400

    const val RULE_FLOOR_TURN = "floor_turn"
    const val RULE_ALL_GREEN = "all_green"

    data class Event(
        /** rule | space | symbol | day — one per stock per rule per day. */
        val id: String = "",
        val rule: String = "",
        val space: String = "",
        val symbol: String = "",
        /** The UTC day it fired, the same calendar the watcher's once-a-day caps use. */
        val day: String = "",
        val atMs: Long = 0L,
        /** The price at the moment it fired — what a buy then would have cost. */
        val price: Double = 0.0,
        /** The five dots at that moment, month first: G, R or · for unknown. */
        val dots: String = "",
        // Returns in percent, filled in as the trading days pass. Null = not yet
        // (or never: a stock whose prices can no longer be read).
        val ret1: Double? = null,
        val ret3: Double? = null,
        val ret5: Double? = null,
        /** Nothing more will be filled in. */
        val done: Boolean = false
    )

    data class State(val events: List<Event> = emptyList())

    private val gson = Gson()
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun id(rule: String, space: String, symbol: String, day: String) =
        "$rule|$space|${symbol.uppercase()}|$day"

    @Synchronized
    fun load(ctx: Context): State {
        val f = file(ctx)
        if (!f.exists()) return State()
        return try {
            gson.fromJson(f.readText(), State::class.java) ?: State()
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of saving over it.
            Log.e(TAG, "signal record unreadable — set aside, starting a new one", e)
            try { f.renameTo(File(f.parentFile, "$FILE.bad-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            State()
        }
    }

    @Synchronized
    private fun save(ctx: Context, s: State) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(gson.toJson(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { Log.e(TAG, "signal record save failed", e) }
    }

    /** Record a new event; false if that stock already fired this rule today. */
    @Synchronized
    fun record(ctx: Context, e: Event): Boolean {
        val s = load(ctx)
        if (s.events.any { it.id == e.id }) return false
        save(ctx, State((s.events + e).takeLast(MAX)))
        return true
    }

    @Synchronized
    fun update(ctx: Context, block: (List<Event>) -> List<Event>) {
        val s = load(ctx)
        val next = block(s.events)
        if (next != s.events) save(ctx, State(next.takeLast(MAX)))
    }
}
