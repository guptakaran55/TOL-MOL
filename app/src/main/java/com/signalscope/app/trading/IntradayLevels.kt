package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.util.Indicators

// ════════════════════════════════════════════════════════════════════════════
// WHERE THIS STOCK HAS BEEN TURNING LATELY
// ════════════════════════════════════════════════════════════════════════════
// Support and resistance read off the watcher's OWN bars — the same candles the
// entry check and the stop sweep already downloaded, so this adds not one
// request to a pass.
//
// NOT A SECOND ALGORITHM. The clustering, the volatility-scaled tolerance and
// the role-reversal rule all come from the finder the Discovery scan has always
// used. Writing a fresh one for intraday would have produced two answers to the
// same question that drift apart over time, and no way to tell which screen was
// lying. The only thing that changes here is the bars it is handed.
//
// WHAT IT IS FOR. On a candidate: am I buying into a ceiling or off a floor. On
// a holding: is my stop sitting under something that has actually held before,
// or in open air. Both are questions a price alone cannot answer, and both are
// asked constantly on this panel.
//
// WHAT IT IS NOT. A forecast. These are places price has turned before, which is
// a statement about the past — a useful one, because other people are looking at
// the same chart, and a level nobody is watching stops working.
// ════════════════════════════════════════════════════════════════════════════

object IntradayLevels {

    /**
     * Fewer bars than this and the answer is not worth having.
     *
     * The finder needs pivots, a pivot needs five bars of clearance on each side,
     * and a cluster needs several pivots before "a level" means more than "a
     * number price happened to touch once". Sixty is roughly ten sessions of
     * hourly bars — enough for a level to have been tested rather than merely
     * visited.
     */
    const val MIN_BARS = 60

    /**
     * The levels these candles imply, or null when they cannot honestly be read.
     *
     * PURE ARITHMETIC, NO I/O. The caller supplies the bars it already has, which
     * is what keeps this free, and it is also what makes it testable against
     * known numbers rather than against a market.
     */
    fun from(symbol: String, candles: List<CandleData>, barMin: Int,
             nowMs: Long = System.currentTimeMillis()): ManualWatchStore.Levels? {
        if (candles.size < MIN_BARS) return null
        val closes = candles.map { it.close }
        val price = closes.last()
        if (price <= 0.0) return null
        val sr = try {
            Indicators.findSupportResistanceDetailed(
                candles.map { it.high }, candles.map { it.low }, closes)
        } catch (e: Exception) { return null }

        // A LEVEL ON THE WRONG SIDE OF PRICE IS NOT A LEVEL. The finder falls back
        // to the window's extremes when no cluster passes its distance filter, and
        // for a stock making new highs that fallback is the highest bar in the
        // window — which is at or BELOW where it is trading now. Printing that as
        // "resistance" tells the user there is a ceiling overhead when in fact
        // there is nothing above at all, which is the opposite of the truth and
        // the more expensive way round to be wrong.
        val support = if (sr.support > 0.0 && sr.support < price) sr.support else 0.0
        val resistance = if (sr.resistance > 0.0 && sr.resistance > price) sr.resistance else 0.0

        // ── THE SWING WINDOW ────────────────────────────────────────────────
        // Always produced where there are bars for it, even when no clustered
        // level survives above — it is the reading an entry is actually timed
        // off, and it does not depend on anything having been "tested".
        val recent = recentDays(candles, SWING_DAYS)
        val lo = recent.minOfOrNull { it.low } ?: 0.0
        val hi = recent.maxOfOrNull { it.high } ?: 0.0
        val pos = positionInRange(price, lo, hi) ?: -1.0
        val visits = zoneVisits(recent, lo, hi)
        val swingDays = recent.map { it.timestamp / 86_400L }.distinct().size

        if (support <= 0.0 && resistance <= 0.0 && pos < 0.0) return null

        // The same window over a week and a month — on the bars already in hand.
        val wk = window(candles, WEEK_DAYS, WEEK_MIN_DAYS)
        val mo = window(candles, MONTH_DAYS, MONTH_MIN_DAYS)

        return ManualWatchStore.Levels(
            symbol = symbol.trim().uppercase(),
            support = support, resistance = resistance, price = price,
            supportTouches = if (support > 0.0) sr.supportTouches else 0,
            resistanceTouches = if (resistance > 0.0) sr.resistanceTouches else 0,
            swingLow = lo, swingHigh = hi, swingPos = pos,
            swingVisits = visits, swingDays = swingDays,
            weekLow = wk?.low ?: 0.0, weekHigh = wk?.high ?: 0.0,
            weekVisits = wk?.visits ?: 0, weekDays = wk?.days ?: 0,
            monthLow = mo?.low ?: 0.0, monthHigh = mo?.high ?: 0.0,
            monthVisits = mo?.visits ?: 0, monthDays = mo?.days ?: 0,
            barMin = barMin, bars = candles.size, atMs = nowMs)
    }

    // ── WEEK AND MONTH ──────────────────────────────────────────────────────
    // Same reading as the 3-day window, longer horizon. Counted in days that
    // traded, like the 3-day one. A window short of most of its days (a young
    // listing, a thin history) is left out rather than shown as a "month".
    const val WEEK_DAYS = 5
    const val MONTH_DAYS = 21
    private const val WEEK_MIN_DAYS = 4
    private const val MONTH_MIN_DAYS = 15

    private class Window(val low: Double, val high: Double, val visits: Int, val days: Int)

    private fun window(candles: List<CandleData>, days: Int, minDays: Int): Window? {
        val recent = recentDays(candles, days)
        val n = recent.map { it.timestamp / 86_400L }.distinct().size
        if (n < minDays) return null
        val lo = recent.minOfOrNull { it.low } ?: return null
        val hi = recent.maxOfOrNull { it.high } ?: return null
        if (!(lo > 0.0) || !(hi > lo)) return null
        return Window(lo, hi, zoneVisits(recent, lo, hi), n)
    }

    /** One horizon's floor: "3d", "1w" or "1m", and its range. */
    data class Floor(val scale: String, val label: String, val low: Double, val high: Double,
                     val visits: Int, val days: Int)

    /**
     * WHICH OF ITS LOWS IS THIS PRICE SITTING AT — the 3-day, the week's, the
     * month's — by the same rule for each: the bottom tenth of that window's own
     * range (or below it). Shortest first. Pure, so it is tested on numbers.
     */
    fun floorsHit(lv: ManualWatchStore.Levels, price: Double): List<Floor> {
        if (!(price > 0.0)) return emptyList()
        val all = listOf(
            Floor("3d", "${lv.swingDays.takeIf { it > 0 } ?: SWING_DAYS}-day", lv.swingLow, lv.swingHigh, lv.swingVisits, lv.swingDays),
            Floor("1w", "1-week", lv.weekLow, lv.weekHigh, lv.weekVisits, lv.weekDays),
            Floor("1m", "1-month", lv.monthLow, lv.monthHigh, lv.monthVisits, lv.monthDays))
        return all.filter { f ->
            f.low > 0.0 && f.high > f.low && inSupportZone(positionInRange(price, f.low, f.high))
        }
    }

    /**
     * THE SWING WINDOW — the last few TRADING days, not calendar days.
     *
     * Three, because that is the horizon someone buying a pullback is actually
     * looking at. The clustered levels above answer a different question over a
     * much longer stretch, and the two are kept apart rather than blended: a
     * floor that held six weeks ago is useful context and is NOT where you time
     * an entry this afternoon.
     *
     * Counted in days the market actually traded, taken from the bars themselves,
     * so a weekend or a holiday costs nothing. Counting back in calendar days
     * would quietly hand back one session on a Monday and three on a Thursday.
     */
    const val SWING_DAYS = 3

    /** How far up from the low still counts as "in the support zone", as a
     *  fraction of the window's own range. Range-relative on purpose: a fixed
     *  percentage means something different on a quiet stock than a wild one. */
    private const val ZONE_FRAC = 0.15

    /** The candles belonging to the last [days] days that actually traded. */
    fun recentDays(candles: List<CandleData>, days: Int): List<CandleData> {
        if (candles.isEmpty() || days < 1) return emptyList()
        val dayOf = { c: CandleData -> c.timestamp / 86_400L }
        val wanted = candles.map(dayOf).distinct().takeLast(days).toHashSet()
        return candles.filter { dayOf(it) in wanted }
    }

    /**
     * HOW MANY SEPARATE VISITS price has made to the bottom of its range.
     *
     * Visits, not bars. Ten consecutive bars sitting on the low is price going
     * nowhere; three separate returns to it is a level being defended, and
     * counting bars would score the first far higher than the second — exactly
     * backwards for the only question being asked of it.
     */
    fun zoneVisits(candles: List<CandleData>, low: Double, high: Double): Int {
        if (candles.isEmpty() || low <= 0.0 || high <= low) return 0
        val ceiling = low + ZONE_FRAC * (high - low)
        var visits = 0
        var inZone = false
        for (c in candles) {
            val here = c.low <= ceiling
            if (here && !inZone) visits++
            inZone = here
        }
        return visits
    }

    /**
     * THE BOTTOM OF THE RANGE — how near the floor counts as "near the floor".
     *
     * A tenth of the distance between the two levels, NOT a tenth of the price.
     * That distinction is the whole reason it is a fraction of the range: ten
     * percent of a price is an enormous move on a steady stock and a rounding
     * error on a volatile one, so a fixed percentage would alert constantly on
     * one name and never on another. A tenth of a stock's OWN recent range means
     * the same thing everywhere.
     */
    const val SUPPORT_ZONE = 0.10

    /**
     * Is price in the bottom of its own range?
     *
     * BELOW THE RANGE COUNTS. A position under zero means price has broken
     * through the floor the window was measured from — either the best entry on
     * the list or a level failing, and both are worth being told about rather
     * than silently dropped for being off the end of a scale.
     */
    fun inSupportZone(pos: Double?): Boolean = pos != null && pos <= SUPPORT_ZONE

    /**
     * How far a level sits from a price, as a percentage of that price.
     *
     * Signed from the price outward, so support reads negative and resistance
     * positive — the direction price would have to travel to reach it. Unsigned
     * distances read identically for a floor 3% below and a ceiling 3% above,
     * which are opposite situations.
     */
    fun distancePct(price: Double, level: Double): Double? =
        if (price <= 0.0 || level <= 0.0) null else (level / price - 1.0) * 100.0

    /**
     * Where price sits between the two, 0 at support and 1 at resistance.
     *
     * The single most useful reading on the pair and the one nobody works out in
     * their head from two prices: near the floor is where a buyer wants to be and
     * near the ceiling is where a holder starts paying attention.
     *
     * Null when there is no range to place price inside — one level missing, or
     * both on the same number. NOT clamped: price genuinely trades outside the
     * levels it was measured against between one pass and the next, and silently
     * pinning that to the end of the bar would hide the very moment a level broke.
     */
    fun positionInRange(price: Double, support: Double, resistance: Double): Double? {
        if (price <= 0.0 || support <= 0.0 || resistance <= 0.0) return null
        val span = resistance - support
        if (span <= 0.0) return null
        return (price - support) / span
    }
}
