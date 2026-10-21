package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.Spaces
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// SQUEEZE STATE — one verdict per name, refreshed once a day
// ════════════════════════════════════════════════════════════════════════════
// Written by the same daily pass that ranks the universe, from bars already in
// hand, so it costs arithmetic and no downloads. Kept in its own file for the
// same reason the sparklines are: the watcher state is read on every repaint,
// and this is several hundred rows that most repaints do not need.
//
// Cached in memory against the file's modification time, so a screen that reads
// it on a timer parses it once per rebuild rather than once per frame.
// ════════════════════════════════════════════════════════════════════════════

object SqueezeStore {

    private const val TAG = "SqueezeStore"
    private val gson = Gson()

    data class Row(
        /** Narrowest Keltner width still holding the bands. 0 = not squeezed.
         *  Smaller is stronger — 1.0 is high compression, 2.0 is low. */
        val tier: Double = 0.0,
        val days: Int = 0,
        val momentum: Double = 0.0,
        val fired: Boolean = false,
        val firedFrom: Double = 0.0,
        // ── THE SAME MEASUREMENT ON A WEEKLY CLOCK ──────────────────────────
        // Kept beside the daily rather than replacing it, because the pair is
        // what carries the meaning: a long weekly coil is the setup, and the
        // daily is the timing. Either alone says half of it.
        //
        // [wDays] counts WEEKS, not days. An eight-week coil and an eight-day
        // one are very different things and must not share a unit.
        val wTier: Double = 0.0,
        val wDays: Int = 0,
        val wMomentum: Double = 0.0,
        val wFired: Boolean = false,
        val wFiredFrom: Double = 0.0
    )

    data class State(
        val day: String = "",
        val rows: Map<String, Row> = emptyMap(),
        /** Names the scan could not judge — too little history, or a feed that
         *  returned no range. Counted rather than listed: the number is the
         *  useful part, and it stops a shrinking universe passing for calm. */
        val unscored: Int = 0
    )

    private fun file(ctx: Context, space: String) =
        File(ctx.filesDir, "squeeze_${Spaces.baseOf(space.lowercase())}.json")

    private val cache = HashMap<String, Pair<Long, State>>()

    @Synchronized
    fun load(ctx: Context, space: String): State = try {
        val base = Spaces.baseOf(space.lowercase())
        val f = file(ctx, space)
        val stamp = if (f.exists()) f.lastModified() else 0L
        val hit = cache[base]
        if (hit != null && hit.first == stamp) hit.second
        else {
            val s = if (!f.exists()) State()
                    else gson.fromJson(f.readText(), State::class.java) ?: State()
            cache[base] = stamp to s
            s
        }
    } catch (e: Exception) { Log.e(TAG, "load($space) failed", e); State() }

    @Synchronized
    fun save(ctx: Context, space: String, s: State) {
        try {
            val f = file(ctx, space)
            f.writeText(gson.toJson(s))
            cache[Spaces.baseOf(space.lowercase())] = f.lastModified() to s
        } catch (e: Exception) { Log.e(TAG, "save($space) failed", e) }
    }
}
