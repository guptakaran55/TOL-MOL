package com.signalscope.app.screener

import kotlin.math.abs
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
// TTM SQUEEZE — where a stock has gone quiet, and which way it lets go
// ════════════════════════════════════════════════════════════════════════════
// Bollinger bands measure how far price has been ranging; Keltner channels
// measure how far it USUALLY ranges. When the bands sit entirely inside the
// channel the stock is coiling: quieter than its own habit. Those compressions
// resolve, and the tighter and longer the coil, the harder the resolution tends
// to be.
//
// THREE WIDTHS, NOT ONE. Inside the 2.0 channel is common enough to be noise;
// inside 1.5 is unusual; inside 1.0 is rare and is where the large moves
// cluster. Reporting which width still contains the bands turns a yes/no into a
// strength, and that is the whole reason for scanning it.
//
// Note the direction: a NARROWER channel is the harder test, so the smaller
// number is the stronger squeeze.
//
// PURE ARITHMETIC, NO I/O. It takes bars and returns a verdict, so it can be
// tested against known series rather than against the market — which matters for
// something whose thresholds are calibrated numbers rather than opinions.
//
// A NOTE ON WHY THIS RUNS OVER THE WHOLE UNIVERSE. The momentum ranking sorts
// for stocks already moving. A squeeze is the opposite: it finds the ones that
// have stopped. Scanning only the top-ranked names would be hunting for quiet
// stocks in a list ordered by loudness.
// ════════════════════════════════════════════════════════════════════════════

object SqueezeScanner {

    /** Bollinger and Keltner both use this, as in the original. */
    const val LENGTH = 20
    private const val BB_STD = 2.0

    /**
     * Keltner widths, STRONGEST FIRST.
     *
     * A SMALLER multiplier is the harder test: the bands must fit inside a
     * narrower channel. So 1.0 is high compression and rare, 1.5 is mid, and 2.0
     * is low compression and common — the standard three levels.
     *
     * This reads backwards to most people, including me when I first wrote it.
     * Checking widest-first and taking the first fit returns 2.0 for almost
     * everything, because anything inside the narrow channel is also inside the
     * wide one. That scored 40% of a 105-name market as the tightest tier, which
     * is what gave the mistake away.
     */
    val WIDTHS = listOf(1.0, 1.5, 2.0)

    data class Verdict(
        /**
         * Narrowest Keltner width still containing the bands: 1.0, 1.5 or 2.0.
         *
         * SMALLER IS STRONGER. 1.0 is high compression and uncommon; 2.0 is low
         * compression and ordinary. Zero means no squeeze at all — the bands sit
         * outside even the widest channel.
         *
         * So a filter for "at least this compressed" is tier <= x, not >=, and
         * getting that backwards turns the screen into a list of the calmest
         * names dressed up as the tightest.
         */
        val tier: Double = 0.0,
        /** Consecutive days compressed, counting back from the newest bar. What
         *  predicts the size of the release, more than the tier alone. */
        val days: Int = 0,
        /** Momentum histogram. Sign is the likely direction of the break; the
         *  magnitude says how hard it is already leaning. */
        val momentum: Double = 0.0,
        /** Compressed on the previous bar and released on the newest one.
         *  A squeeze that is ON is a thing to watch; this is the event. */
        val fired: Boolean = false,
        /** The tier it was in immediately before firing — how tight the coil was
         *  that just let go. Zero unless [fired]. */
        val firedFrom: Double = 0.0,
        /** False when the bars carry no high/low range, so nothing was scored.
         *  Distinct from "no squeeze": one is silence, the other is an answer. */
        val ok: Boolean = false
    )

    /**
     * Daily bars folded into weekly ones.
     *
     * A weekly squeeze is the same measurement over a slower clock, and it is
     * the more interesting one: a coil that has held for eight weeks is a
     * different animal from one that has held eight days, and it tends to
     * resolve further. Traders normally read it as the setup and the daily as
     * the timing.
     *
     * COSTS NOTHING EXTRA. These are the bars already downloaded for the daily
     * scan, regrouped — open of the first day, the week's true high and low, and
     * the last day's close. Fetching a weekly series separately would be a second
     * download of the same information.
     *
     * THE NEWEST WEEK IS USUALLY UNFINISHED, and that is deliberate: it is how a
     * weekly chart is read live. It does mean the newest verdict can change as
     * the week fills in — a squeeze can appear on Tuesday and be gone by Friday
     * — so the weekly reading is a state to watch rather than a closed fact.
     */
    fun toWeekly(bars: List<DailyBar>): List<DailyBar> {
        if (bars.isEmpty()) return emptyList()
        val out = ArrayList<DailyBar>(bars.size / 5 + 2)
        var wkStart: java.time.LocalDate? = null
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var last: java.time.LocalDate? = null
        for (b in bars.sortedBy { it.date }) {
            // Monday of this bar's week — ISO weeks, so a year boundary inside a
            // week does not split it into two stubs.
            val mon = b.date.with(java.time.DayOfWeek.MONDAY)
            if (wkStart == null || mon != wkStart) {
                if (wkStart != null && last != null) out.add(DailyBar(last, c, h, l))
                wkStart = mon; o = b.close; h = b.high; l = b.low
            } else {
                if (b.high > h) h = b.high
                if (b.low < l) l = b.low
            }
            c = b.close; last = b.date
        }
        if (wkStart != null && last != null) out.add(DailyBar(last, c, h, l))
        return out
    }

    // ── the pieces ──────────────────────────────────────────────────────────

    private fun sma(x: DoubleArray, i: Int, n: Int): Double {
        var s = 0.0
        for (k in i - n + 1..i) s += x[k]
        return s / n
    }

    private fun stdev(x: DoubleArray, i: Int, n: Int, mean: Double): Double {
        var s = 0.0
        for (k in i - n + 1..i) { val d = x[k] - mean; s += d * d }
        return sqrt(s / n)
    }

    /**
     * True range, which is why the high and low had to be carried.
     *
     * The gap between yesterday's close and today's range is part of the day's
     * movement, and a range measured from the close alone misses it entirely —
     * which is exactly the case where a stock is about to stop being quiet.
     */
    private fun trueRange(h: DoubleArray, l: DoubleArray, c: DoubleArray, i: Int): Double =
        if (i == 0) h[i] - l[i]
        else maxOf(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))

    /** Linear-regression value at the end of the window — the slope-fitted
     *  estimate of where the series is heading, as the original uses. */
    private fun linReg(y: DoubleArray, i: Int, n: Int): Double {
        val nn = n.toDouble()
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0
        for (k in 0 until n) {
            val x = k.toDouble()
            val v = y[i - n + 1 + k]
            sx += x; sy += v; sxy += x * v; sxx += x * x
        }
        val denom = nn * sxx - sx * sx
        if (abs(denom) < 1e-12) return 0.0
        val slope = (nn * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / nn
        return intercept + slope * (nn - 1)
    }

    /** Which width — if any — contains the bands on bar [i]. 0.0 = not squeezed. */
    private fun tierAt(
        close: DoubleArray, high: DoubleArray, low: DoubleArray,
        ema: DoubleArray, atr: DoubleArray, i: Int
    ): Double {
        if (atr[i] <= 0.0) return 0.0
        val mid = sma(close, i, LENGTH)
        val sd = stdev(close, i, LENGTH, mid)
        val bbUp = mid + BB_STD * sd
        val bbLo = mid - BB_STD * sd
        // Ascending, so the FIRST fit is the narrowest channel that still holds
        // the bands — the strongest true statement about this bar. Descending
        // would report the loosest, which every squeezed stock satisfies.
        for (w in WIDTHS.sorted()) {
            val kcUp = ema[i] + w * atr[i]
            val kcLo = ema[i] - w * atr[i]
            if (bbUp <= kcUp && bbLo >= kcLo) return w
        }
        return 0.0
    }

    /**
     * Score one symbol's daily bars.
     *
     * Returns a verdict with [Verdict.ok] false when there is not enough history
     * or the bars carry no range — never a fabricated zero, because "no squeeze"
     * and "could not tell" lead to opposite decisions.
     */
    fun scan(bars: List<DailyBar>): Verdict {
        val n = bars.size
        if (n < LENGTH * 2) return Verdict()
        val close = DoubleArray(n) { bars[it].close }
        val high = DoubleArray(n) { bars[it].high }
        val low = DoubleArray(n) { bars[it].low }

        // A feed that dropped the range gives high == low == close on every bar.
        // Scoring that would report a permanent, total squeeze on everything.
        var ranged = 0
        for (i in 0 until n) if (high[i] > low[i]) ranged++
        if (ranged < n / 2) return Verdict()

        // EMA of close, and Wilder-style smoothed true range.
        val ema = DoubleArray(n)
        val atr = DoubleArray(n)
        val a = 2.0 / (LENGTH + 1.0)
        ema[0] = close[0]
        atr[0] = trueRange(high, low, close, 0)
        for (i in 1 until n) {
            ema[i] = a * close[i] + (1 - a) * ema[i - 1]
            val tr = trueRange(high, low, close, i)
            atr[i] = (atr[i - 1] * (LENGTH - 1) + tr) / LENGTH
        }

        val last = n - 1
        val tierNow = tierAt(close, high, low, ema, atr, last)
        val tierPrev = tierAt(close, high, low, ema, atr, last - 1)

        // How long it has been coiling. Counted from whichever bar is currently
        // compressed — for a fired squeeze that is the previous one, since the
        // useful number is the length of the coil that just let go.
        val from = if (tierNow > 0.0) last else if (tierPrev > 0.0) last - 1 else -1
        var days = 0
        if (from >= 0) {
            var i = from
            while (i >= LENGTH && tierAt(close, high, low, ema, atr, i) > 0.0) { days++; i-- }
        }

        // Carter's momentum: price against the midpoint of its own recent range
        // and mean, regression-fitted. Direction matters more than scale here.
        val basis = DoubleArray(n)
        for (i in LENGTH - 1 until n) {
            var hh = high[i]; var ll = low[i]
            for (k in i - LENGTH + 1..i) { if (high[k] > hh) hh = high[k]; if (low[k] < ll) ll = low[k] }
            basis[i] = close[i] - (((hh + ll) / 2.0) + sma(close, i, LENGTH)) / 2.0
        }
        val mom = if (last >= LENGTH * 2 - 2) linReg(basis, last, LENGTH) else 0.0

        return Verdict(
            tier = tierNow,
            days = days,
            momentum = mom,
            fired = (tierPrev > 0.0 && tierNow == 0.0),
            firedFrom = if (tierPrev > 0.0 && tierNow == 0.0) tierPrev else 0.0,
            ok = true)
    }
}
