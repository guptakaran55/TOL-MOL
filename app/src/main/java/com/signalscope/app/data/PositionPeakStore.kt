package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * THE HIGHEST PRICE EACH REAL POSITION HAS REACHED SINCE IT WAS BOUGHT.
 *
 * The watchlist already keeps one for its own positions, because its trailing
 * stop hangs off it. Algo positions had none, so "how close is this to its best"
 * could not be answered for most of what is actually held. One row per position,
 * keyed like the live ledgers: "broker|namespace|paper trade id".
 *
 * SEEDED ONCE FROM THE PRICE HISTORY since the buy, then raised on every pass
 * from the latest price. A mark that started at today's price would call a
 * position bought a week ago "at its high" after any bounce at all.
 *
 * NEVER FALLS, like any high-water mark.
 */
object PositionPeakStore {
    private const val TAG = "PositionPeakStore"
    private val gson = Gson()

    data class Peak(
        val peak: Double = 0.0,
        val atMs: Long = 0L,
        /** Raised from the bars since the buy, not just from prices seen on
         *  passes. Until then the seed is tried again on the next pass. */
        val seeded: Boolean = false
    )

    data class State(val peaks: Map<String, Peak>? = null)

    private fun file(ctx: Context) = File(ctx.filesDir, "position_peaks.json")

    @Synchronized
    fun load(ctx: Context): Map<String, Peak> = try {
        val f = file(ctx)
        if (!f.exists()) emptyMap()
        else gson.fromJson(f.readText(), State::class.java)?.peaks.orEmpty()
    } catch (e: Exception) { Log.e(TAG, "load failed", e); emptyMap() }

    /** Replaces the whole map — the caller passes only positions still held, so
     *  rows for positions that have ended drop out here. */
    @Synchronized
    fun save(ctx: Context, peaks: Map<String, Peak>) {
        try { file(ctx).writeText(gson.toJson(State(peaks))) }
        catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }
}
