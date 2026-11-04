package com.signalscope.app.trading

// ════════════════════════════════════════════════════════════════════════════
// AN EQUAL SHARE PER NAME — the 💡 replay of an algo with no seats (26 Oct 2026)
// ════════════════════════════════════════════════════════════════════════════
// An algo without the 3b gate trades every one of its names on its own. Its
// replay used to report the AVERAGE of each name's compounded growth, each from
// its own first price — eight coins from eight different years — and no yearly
// rate or worst drop at all: "Hold above SMA50 +0.0%/yr, +5,189%".
//
// Scored now as the crypto backtest scores it (crypto_hold_lab.py): every name
// an equal share of the money from ONE common start — the day the last of them
// has prices — never rebalanced, starting flat; the account valued every day
// (a name in a trade marked at its close), so the worst drop is a real one.
// ════════════════════════════════════════════════════════════════════════════

object EqualShare {
    /** One name: its daily closes (epoch days, ascending) and the engine's trades, net of costs. */
    class Name(val days: LongArray, val closes: DoubleArray, val trades: List<StrategyLabEngine.Trade>)

    data class Result(val growthPct: Double, val cagrPct: Double, val maxDrawdownPct: Double,
                      val fromDay: Long, val toDay: Long, val closed: Int, val wins: Int, val names: Int)

    private fun day(ts: Long) = ts / 86_400L

    /** Pure — tested. Null when the names share no window. */
    fun run(names: List<Name>): Result? {
        val usable = names.filter { it.days.isNotEmpty() }
        if (usable.isEmpty()) return null
        val from = usable.maxOf { it.days.first() }
        val to = usable.maxOf { it.days.last() }
        if (to <= from) return null
        val calendar = usable.flatMap { n -> n.days.filter { it in from..to } }.distinct().sorted()
        val marks = usable.map { markSeries(it, from, calendar) }
        val value = DoubleArray(calendar.size) { i -> marks.sumOf { it[i] } / marks.size }
        var peak = 1.0; var dd = 0.0
        for (v in value) { peak = maxOf(peak, v); if (peak > 0.0) dd = maxOf(dd, (peak - v) / peak * 100.0) }
        val end = value.last()
        val span = (to - from).toDouble()
        val cagr = (Math.pow(end.coerceAtLeast(1e-9), 365.0 / span) - 1.0) * 100.0
        val counted = usable.flatMap { n -> n.trades.filter { it.status == "CLOSED" && day(it.entryTs) >= from } }
        return Result((end - 1.0) * 100.0, cagr, dd, from, to, counted.size,
            counted.count { it.pctReturn > 0.0 }, usable.size)
    }

    /** A name's share on each calendar day: 1 at [from], grown by each closed trade,
     *  and marked at the day's close while a trade is open. Trades that began before
     *  [from] are left out — the account starts flat. */
    private fun markSeries(n: Name, from: Long, calendar: List<Long>): DoubleArray {
        val trades = n.trades.filter { day(it.entryTs) >= from }.sortedBy { it.entryTs }
        var v = 1.0; var k = 0; var di = 0; var last = Double.NaN
        val out = DoubleArray(calendar.size)
        for ((i, d) in calendar.withIndex()) {
            while (di < n.days.size && n.days[di] <= d) { last = n.closes[di]; di++ }
            while (k < trades.size && trades[k].status == "CLOSED" && trades[k].exitTs != null &&
                day(trades[k].exitTs!!) <= d) { v *= 1.0 + trades[k].pctReturn / 100.0; k++ }
            val open = trades.getOrNull(k)?.takeIf { day(it.entryTs) <= d }
            out[i] = if (open != null && last > 0.0 && open.entryPrice > 0.0) v * last / open.entryPrice else v
        }
        return out
    }
}
