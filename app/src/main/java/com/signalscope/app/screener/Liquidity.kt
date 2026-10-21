package com.signalscope.app.screener

import com.signalscope.app.data.CandleData

// ════════════════════════════════════════════════════════════════════════════
// HOW MUCH OF THIS STOCK ACTUALLY CHANGES HANDS
// ════════════════════════════════════════════════════════════════════════════
// A universe assembled by unioning a downloaded index list with a stored one
// grows in only one direction, and what it grows by is the long tail. The names
// that arrive that way are rarely the ones anybody wanted.
//
// WHAT THIS IS NOT PROTECTING AGAINST. Not the ability to fill: a €200 order is
// 0.004% of a day in a name turning over €5m, and would fill in something a
// hundred times thinner. Size is never the binding constraint at this book's
// scale, and a filter sold as "can I get filled" would be solving a problem
// nobody has.
//
// WHAT IT IS PROTECTING AGAINST, in the order that matters:
//
//   • SPREAD. The cost model assumes 0.20% slippage. A thin listing can quote
//     one to two percent wide, which quietly exceeds the edge the strategy is
//     trying to capture — and never shows up as a loss, only as returns that
//     are mysteriously worse than the backtest.
//   • A RANKING THAT CAN BE FOOLED. The screener scores trailing return,
//     volatility and days above the average. A micro-cap that doubled on one
//     announcement scores well on all three. With five seats, one such name is
//     a fifth of the book.
//   • WASTED SEATS. A name the broker does not carry occupies a slot and
//     produces nothing but refused orders.
//
// THE MEDIAN, NEVER THE MEAN OR THE LATEST DAY. One announcement can lift a
// day's volume twentyfold, and that day is precisely what a thin stock looks
// like at the moment it is most dangerous to admit. An average carries that
// spike into the answer; the latest day IS the spike. The median ignores it.
// ════════════════════════════════════════════════════════════════════════════

object Liquidity {

    /** Sessions the median is taken over — a quarter of a year, so one busy week
     *  cannot carry a name over the line on its own. */
    const val LOOKBACK_DAYS = 60

    /**
     * Median daily turnover — price times volume — over the recent window, in
     * the instrument's own currency.
     *
     * 0.0 when it cannot be worked out, which every caller must treat as
     * "unknown" rather than "illiquid": a provider that omits volume sends
     * zeros, and reading that as a failing grade would empty the universe on a
     * feed change rather than on anything about the market.
     */
    fun medianTurnover(bars: List<DailyBar>, lookback: Int = LOOKBACK_DAYS): Double =
        medianOf(bars.takeLast(lookback)
            .filter { it.close > 0.0 && it.volume > 0.0 }
            .map { it.close * it.volume })

    /**
     * THE SAME MEASURE over raw candles, for the Discovery scan — which holds
     * candles rather than daily bars. One rule with two input shapes, never two
     * rules: a company must not pass the floor on one screen and fail it on the
     * next because each did its own arithmetic.
     */
    fun medianTurnoverOfCandles(candles: List<CandleData>, lookback: Int = LOOKBACK_DAYS): Double =
        medianOf(candles.takeLast(lookback)
            .filter { it.close > 0.0 && it.volume > 0L }
            .map { it.close * it.volume.toDouble() })

    /** [passes] for candles. Unknown passes here too, for the same reason. */
    fun passesCandles(candles: List<CandleData>, minTurnover: Double,
                      lookback: Int = LOOKBACK_DAYS): Boolean {
        if (minTurnover <= 0.0) return true
        val t = medianTurnoverOfCandles(candles, lookback)
        if (t <= 0.0) return true
        return t >= minTurnover
    }

    private fun medianOf(raw: List<Double>): Double {
        if (raw.isEmpty()) return 0.0
        val values = raw.sorted()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2.0
    }

    /**
     * Does this name trade enough to be worth a seat?
     *
     * UNKNOWN PASSES. A name whose turnover cannot be measured is admitted, not
     * excluded. The filter exists to remove things that are demonstrably too
     * thin; silently dropping everything a feed failed to describe would be a
     * data outage presenting itself as a market with nothing in it — the same
     * mistake as reading an unreadable holdings list as an empty account.
     */
    fun passes(bars: List<DailyBar>, minTurnover: Double,
               lookback: Int = LOOKBACK_DAYS): Boolean {
        if (minTurnover <= 0.0) return true          // filter switched off
        val t = medianTurnover(bars, lookback)
        if (t <= 0.0) return true                    // unknown is not a verdict
        return t >= minTurnover
    }

    // ── the defaults, calibrated per market ────────────────────────────────
    //
    // Quoted in each market's OWN currency, because a single number cannot mean
    // the same thing across three of them — five million is generous in euros,
    // trivial in rupees and nothing at all in dollars.
    //
    // Each is set to keep large, mid and moderate caps and to drop the genuine
    // micro-cap tail, which is the line asked for.

    /**
     * GERMANY — €2m median daily turnover.
     *
     * DAX names turn over €50m to €500m a day and MDAX €5m to €50m, so both
     * clear this comfortably. SDAX spans roughly €1m to €10m, which is where
     * the line actually bites: €2m keeps the traded half of it and drops the
     * rest. Five million was the figure suggested, and it would work, but it
     * removes most of SDAX along with the junk — more than "moderate cap and
     * above" was meant to exclude.
     */
    const val DEFAULT_DE = 2_000_000.0

    /**
     * USA — $10m median daily turnover.
     *
     * Inert against the current universe, deliberately: every NASDAQ-100 member
     * turns over hundreds of millions a day, so nothing is excluded today. It
     * earns its place the moment that list is widened, which is exactly how the
     * German universe came to need one.
     */
    const val DEFAULT_US = 10_000_000.0

    /**
     * INDIA — ₹10 crore (₹100m) median daily turnover.
     *
     * NSE large caps trade well above ₹500 crore a day and mid caps ₹50 to ₹500
     * crore, so both pass easily. Small caps run ₹5 to ₹50 crore and micro caps
     * below ₹1 crore. Ten crore sits above the micro tail while leaving the
     * liquid end of the small-cap range in play — and India is the space where
     * this matters most, since its universe is six hundred names rather than a
     * hundred.
     */
    const val DEFAULT_IN = 100_000_000.0

    fun defaultFor(base: String): Double = when (base.lowercase()) {
        "de" -> DEFAULT_DE
        "in" -> DEFAULT_IN
        "us" -> DEFAULT_US
        else -> 0.0          // crypto and anything else: no filter
    }
}
