package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.Spaces
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// PORTFOLIO TRACKING — when the app started watching each holding
// ════════════════════════════════════════════════════════════════════════════
// The strategy rules need three things about a position: what it cost, when it
// opened, and the momentum reading at that moment. For stock the app itself
// bought, all three are known. For stock you have held for years, the broker
// tells us the cost and NOTHING tells us the date.
//
// So the app records the moment it first saw each holding and measures the
// time-based rules from there. That is an honest answer to an unanswerable
// question: the stop and the target still measure from your REAL cost, and only
// the rules that count bars are anchored to when watching began.
//
// It is stamped once and kept. Re-stamping every pass would reset the holding
// clock continuously and a maximum-holding rule would never fire; dropping it
// when a name briefly vanishes from the portfolio would do the same. It is
// cleared only when the holding genuinely goes away.
// ════════════════════════════════════════════════════════════════════════════

object PortfolioTrackStore {

    private const val TAG = "PortfolioTrackStore"
    private val gson = Gson()

    data class Track(
        /** Epoch SECONDS the app first saw this holding. The engine clock is in
         *  seconds and mixing the two units is a bug that hides for months. */
        val since: Long = 0L,
        /** Average cost as the broker reported it when tracking began, kept only
         *  for the log — the live cost is re-read every pass, because you may buy
         *  more of the same name and change it. */
        val costAtStart: Double = 0.0,
        /** UTC day this holding last raised a strategy alert. */
        val alertedDay: String = "",
        /** Last verdict, so the panel can say what is happening without re-running
         *  a check that needs price history. */
        val lastReason: String = ""
    )

    data class State(val tracks: Map<String, Track> = emptyMap())

    private fun file(ctx: Context, space: String) =
        File(ctx.filesDir, "portfolio_track_${Spaces.baseOf(space.lowercase())}.json")

    @Synchronized
    fun load(ctx: Context, space: String): State = try {
        val f = file(ctx, space)
        if (!f.exists()) State() else gson.fromJson(f.readText(), State::class.java) ?: State()
    } catch (e: Exception) { Log.e(TAG, "load($space) failed", e); State() }

    @Synchronized
    fun save(ctx: Context, space: String, s: State) {
        try { file(ctx, space).writeText(gson.toJson(s)) }
        catch (e: Exception) { Log.e(TAG, "save($space) failed", e) }
    }

    @Synchronized
    fun mutate(ctx: Context, space: String, block: (State) -> State): State {
        val next = block(load(ctx, space)); save(ctx, space, next); return next
    }

    /**
     * The tracking start for [symbol], stamping it now if this is the first sight.
     * Returns the timestamp to measure from.
     */
    fun startFor(ctx: Context, space: String, symbol: String, cost: Double): Long {
        val k = symbol.uppercase()
        val existing = load(ctx, space).tracks[k]
        if (existing != null && existing.since > 0L) return existing.since
        val now = System.currentTimeMillis() / 1000L
        mutate(ctx, space) { s ->
            s.copy(tracks = s.tracks + (k to (s.tracks[k] ?: Track()).copy(
                since = now, costAtStart = cost)))
        }
        Log.i(TAG, "[$space] $k tracking begins at $now (cost $cost)")
        return now
    }

    /** Forget names the account no longer holds, so a repurchase later starts its
     *  own clock instead of inheriting one from a position closed months ago. */
    fun retainOnly(ctx: Context, space: String, held: Set<String>) {
        val s = load(ctx, space)
        val keep = s.tracks.filterKeys { it in held }
        if (keep.size != s.tracks.size) save(ctx, space, s.copy(tracks = keep))
    }
}
