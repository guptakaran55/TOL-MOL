package com.signalscope.app.trading

import com.signalscope.app.trading.StrategyLabEngine.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AN EQUAL SHARE PER NAME — the replay of an algo with no seats (26 Oct 2026). */
class EqualShareTest {
    private val d0 = 20_000L
    private fun days(n: Int, from: Long = d0) = LongArray(n) { from + it }
    private fun trade(entryDay: Long, entry: Double, exitDay: Long?, pct: Double) = Trade(symbol = "X",
        status = if (exitDay == null) "OPEN" else "CLOSED", entryTs = entryDay * 86_400L, entryPrice = entry,
        entryReason = "", exitTs = exitDay?.let { it * 86_400L }, exitPrice = null, pctReturn = pct,
        pnlNotional = 0.0, barsHeld = 0)

    @Test fun `two names, one gains 10 percent and one sits out — the account gains 5`() {
        val a = EqualShare.Name(days(10), DoubleArray(10) { 100.0 }, listOf(trade(d0 + 2, 100.0, d0 + 5, 10.0)))
        val b = EqualShare.Name(days(10), DoubleArray(10) { 50.0 }, emptyList())
        val r = EqualShare.run(listOf(a, b))!!
        assertEquals(5.0, r.growthPct, 1e-9)
        assertEquals(1, r.closed)
        assertEquals(1, r.wins)
    }

    @Test fun `the start is when the last name has prices, and earlier trades are left out`() {
        val old = EqualShare.Name(days(20), DoubleArray(20) { 100.0 },
            listOf(trade(d0 + 1, 100.0, d0 + 3, 50.0), trade(d0 + 12, 100.0, d0 + 14, 10.0)))
        val young = EqualShare.Name(days(10, d0 + 10), DoubleArray(10) { 10.0 }, emptyList())
        val r = EqualShare.run(listOf(old, young))!!
        assertEquals(d0 + 10, r.fromDay)
        assertEquals(5.0, r.growthPct, 1e-9)          // only the +10% trade, half the money
    }

    @Test fun `an open trade is valued at the close, so its fall is a real drop`() {
        val closes = doubleArrayOf(100.0, 100.0, 80.0, 90.0)
        val a = EqualShare.Name(days(4), closes, listOf(trade(d0 + 1, 100.0, null, 0.0)))
        val r = EqualShare.run(listOf(a))!!
        assertEquals(20.0, r.maxDrawdownPct, 1e-9)
        assertEquals(-10.0, r.growthPct, 1e-9)
    }

    @Test fun `names with no common window give nothing`() {
        assertNull(EqualShare.run(listOf(EqualShare.Name(days(1), doubleArrayOf(1.0), emptyList()))))
    }
}
