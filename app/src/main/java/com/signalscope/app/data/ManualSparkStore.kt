package com.signalscope.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.trading.Spaces
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// WATCHER SPARKLINES — a year of price and MACD per ranked name
// ════════════════════════════════════════════════════════════════════════════
// Enough shape to judge a candidate at a glance: is this a name in a long climb
// that just paused, or one that has been falling all year and bounced once? The
// score and the slope are both single numbers and neither can tell those apart,
// which is exactly the distinction a person wants before buying by hand.
//
// A SEPARATE FILE FROM THE WATCHER STATE, deliberately. The watcher state is
// read on every repaint of the Watchlist tab; these series are roughly a hundred
// numbers per name and would multiply the cost of parsing it by an order of
// magnitude. Splitting them keeps the hot path small and lets this one be read
// only when the panel actually wants to draw.
//
// WRITTEN ONCE A DAY, by the same pass that ranks the universe — it already holds
// every symbol's daily bars, so the series cost arithmetic and no downloads.
// ════════════════════════════════════════════════════════════════════════════

object ManualSparkStore {

    private const val TAG = "ManualSparkStore"
    private val gson = Gson()

    /** How many points survive downsampling. A sparkline is ~80px wide, so more
     *  points than this cannot be drawn distinctly and only cost file size. */
    const val POINTS = 48

    /** The window each series covers — one trading year. */
    const val LOOKBACK_DAYS = 252

    /** How many raw MACD points the short curve keeps. Matches what the Discovery
     *  scan stores per stock, so the two screens hand the shared renderer curves
     *  of the same length and a name looks the same in both. */
    const val MACD_RECENT = 30

    data class Series(
        /**
         * THE PROJECTED CHANNEL — where the 50-day trend line is heading, and how
         * wide this stock normally swings around it.
         *
         * A PREDICTION, AND THE ONLY ONE IN THIS STORE. Everything else here is a
         * measurement of what happened; these three are an extrapolation of the
         * trend twenty trading days forward, bracketed by two standard deviations
         * of how far price usually strays from it. It is a probabilistic channel,
         * not a forecast of a price, and anything that displays it has to say so —
         * the Discovery screen deliberately hides it behind a button for exactly
         * that reason.
         *
         * Zero means it could not be computed: fewer than sixty closes, or a
         * trend line that never established itself. Not an error, and not a
         * reason to show a blank where a caveat belongs.
         */
        val projFloor: Double = 0.0,
        val projCeiling: Double = 0.0,
        val projMid: Double = 0.0,
        /** How far ahead it looks, in trading days. */
        val projDays: Int = 0,
        /** Closes, oldest to newest, already downsampled. */
        val px: List<Double> = emptyList(),
        /** The daily MACD line (12/26 EMA difference) over the same bars. */
        val macd: List<Double> = emptyList(),
        /**
         * The LAST [MACD_RECENT] raw MACD values — not downsampled, not a year.
         *
         * The yearly curve above cannot be drawn in a 40-pixel cell. Squeezing
         * 252 days of MACD into it produces a line that crosses zero so often it
         * reads as static, which is worse than an empty cell because it still
         * occupies the space a readable shape would use. This is the same curve
         * and the same length Discovery keeps, so a stock that appears on both
         * screens is drawn identically instead of two different ways.
         */
        val macdRecent: List<Double> = emptyList(),
        /**
         * The MACD phase over that curve, in Discovery's own vocabulary — the
         * shared renderer colours by this string, so computing it here is what
         * makes the colours agree rather than merely the shapes.
         */
        val phase: String = "",
        /** Trading days actually covered — under [LOOKBACK_DAYS] for a young listing,
         *  which is worth showing rather than stretching a short history to look
         *  like a full year. */
        val days: Int = 0,
        /**
         * Epoch seconds of the first and last bar in the window.
         *
         * Only so the panel can shade the stretch the strategy would have been
         * holding. The mapping from a date to a position on the line is LINEAR IN
         * TIME while the points are spaced by trading day, so weekends and holidays
         * skew it slightly — fine for shading a region on an 80-pixel sparkline,
         * and not to be reused for anything that needs a bar located exactly.
         */
        val t0: Long = 0L,
        val t1: Long = 0L
    )

    data class State(
        val day: String = "",
        val series: Map<String, Series> = emptyMap()
    )

    private fun file(ctx: Context, space: String) =
        File(ctx.filesDir, "manual_spark_${Spaces.baseOf(space.lowercase())}.json")

    /**
     * PARSED ONCE PER WRITE, not once per read.
     *
     * These files are written a single time a day and read on every repaint of the
     * Watchlist tab. India's is over 300 KB — five hundred names, a hundred numbers
     * each — and every parse of it allocates tens of thousands of boxed doubles.
     * Doing that on a repaint timer is the kind of cost that does not announce
     * itself until the device is already short of memory.
     *
     * Keyed on the file's modification time, so a rebuild is picked up immediately
     * and an unchanged file is never read twice.
     */
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
            // Seed the cache from what we just wrote rather than leaving the next
            // reader to parse it straight back off disk.
            cache[Spaces.baseOf(space.lowercase())] = f.lastModified() to s
        } catch (e: Exception) { Log.e(TAG, "save($space) failed", e) }
    }

    /**
     * Reduce a full series to [POINTS] evenly spaced samples, always keeping the
     * NEWEST bar.
     *
     * Sampling rather than averaging on purpose: an average would smooth away the
     * spikes and gaps that make a chart worth looking at, and a sparkline exists
     * to show shape, not to be measured off.
     */
    fun downsample(xs: DoubleArray, points: Int = POINTS, decimals: Int = 2): List<Double> {
        if (xs.isEmpty()) return emptyList()
        if (xs.size <= points) return xs.map { round(it, decimals) }
        val out = ArrayList<Double>(points)
        for (k in 0 until points) {
            // Spread across the range so the last sample is always the final bar —
            // a sparkline whose right-hand end is not today is quietly misleading.
            val idx = ((k.toDouble() / (points - 1)) * (xs.size - 1)).toInt()
            out.add(round(xs[idx], decimals))
        }
        return out
    }

    private fun round(x: Double, d: Int): Double {
        if (x.isNaN() || x.isInfinite()) return 0.0
        var f = 1.0
        repeat(d) { f *= 10.0 }
        return Math.round(x * f) / f
    }
}
