package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BullFlagTest {

    /** One daily bar: open, high, low, close, volume. */
    private data class D(val o: Double, val h: Double, val l: Double, val c: Double, val v: Long)

    /**
     * 80 quiet days at 100, a 5-day pole to 120 on heavy volume, a 5-day flag
     * drifting 118→116 on light volume (line at 119, low 115), then [after].
     */
    private fun series(
        poleVol: Long = 2500,
        flagLows: List<Double> = listOf(117.0, 116.0, 115.0, 116.0, 115.0),
        after: List<D> = emptyList()
    ): List<D> {
        val out = ArrayList<D>()
        repeat(80) { out.add(D(100.0, 100.5, 99.5, 100.0, 1000)) }
        var prev = 100.0
        for (c in listOf(104.0, 108.0, 112.0, 116.0, 120.0)) { out.add(D(prev, c + 0.5, prev - 0.5, c, poleVol)); prev = c }
        val flagCloses = listOf(118.0, 117.0, 116.0, 117.0, 116.0)
        for ((i, c) in flagCloses.withIndex()) out.add(D(prev, c + 1.0, flagLows[i], c, 800)); prev = 116.0
        out.addAll(after)
        return out
    }

    private fun daily(ds: List<D>): List<CandleData> =
        ds.mapIndexed { i, d -> CandleData(i * 86_400L + 4 * 3600, d.o, d.h, d.l, d.c, d.v) }

    private val breakout = D(117.0, 122.0, 116.5, 121.0, 3000)
    private val runToTarget = listOf(D(121.0, 126.0, 120.5, 125.0, 2000), D(125.0, 133.0, 124.0, 132.0, 2000),
        D(132.0, 140.0, 131.0, 139.0, 2000), D(139.0, 145.0, 138.0, 144.0, 2000))

    @Test fun findsTheFlagAndItsLine() {
        val days = BullFlag.toDays(daily(series()))
        val s = BullFlag.detect(days, days.size - 1, BullFlag.Params())
        assertNotNull(s); s!!
        assertEquals(119.0, s.trigger, 1e-9)
        assertEquals(115.0, s.flagLow, 1e-9)
        assertEquals(5, s.flagDays)
        assertEquals(99.5, s.poleLow, 1e-9)
        assertEquals(120.5, s.poleHigh, 1e-9)
        assertEquals(2.5, s.poleVolRatio, 1e-9)
        assertTrue(s.flagVolRatio < 0.5)
    }

    @Test fun aPoleOnOrdinaryVolumeIsNotAFlag() {
        val days = BullFlag.toDays(daily(series(poleVol = 1000)))
        assertNull(BullFlag.detect(days, days.size - 1, BullFlag.Params()))
    }

    @Test fun aFlagThatGivesBackMostOfThePoleIsNotAFlag() {
        val days = BullFlag.toDays(daily(series(flagLows = listOf(117.0, 112.0, 105.0, 110.0, 112.0))))
        assertNull(BullFlag.detect(days, days.size - 1, BullFlag.Params()))
    }

    @Test fun buysTheBreakoutOnASurgeAndTakesTheMeasuredMove() {
        val r = BullFlag.run("X", daily(series(after = listOf(breakout) + runToTarget)), BullFlag.Params())
        assertEquals(1, r.trades.size)
        val t = r.trades[0]
        assertEquals(121.0, t.entryPrice, 1e-9)                 // close of the breakout day, not the line
        assertEquals(115.0 * 0.995, t.stop, 1e-9)
        assertEquals(121.0 + 21.0, t.target, 1e-9)              // pole height 120.5 - 99.5
        assertEquals("TARGET", t.exitReason)
        assertEquals(142.0, t.exitPrice!!, 1e-9)
        assertEquals((142.0 / 121.0 - 1) * 100 - 0.476, t.pctReturn, 1e-9)
        assertTrue(t.volPace > 2.0)
    }

    @Test fun aBreakoutOnQuietVolumeIsSkippedUnlessVolumeIsSwitchedOff() {
        val quiet = breakout.copy(v = 1000)
        val ds = daily(series(after = listOf(quiet) + runToTarget))
        val withVol = BullFlag.run("X", ds, BullFlag.Params())
        assertTrue(withVol.trades.none { it.entryTs / 86_400L == 90L })
        assertEquals(1, withVol.breaksWithoutVolume.coerceAtMost(1))
        val priceOnly = BullFlag.run("X", ds, BullFlag.Params(requireVolume = false))
        assertEquals(90L, priceOnly.trades.first().entryTs / 86_400L)
    }

    @Test fun aBuyTooFarAboveTheLineIsChasing() {
        val r = BullFlag.run("X", daily(series(after = listOf(breakout.copy(h = 124.0, c = 123.5)))), BullFlag.Params())
        assertTrue(r.trades.isEmpty())
        assertEquals(1, r.chased)
    }

    @Test fun aFailedBreakoutStopsOutUnderTheFlag() {
        val fail = listOf(D(120.0, 120.5, 116.0, 117.0, 1500), D(116.0, 116.5, 113.0, 113.5, 1500))
        val t = BullFlag.run("X", daily(series(after = listOf(breakout) + fail)), BullFlag.Params()).trades.single()
        assertEquals("STOP", t.exitReason)
        assertEquals(115.0 * 0.995, t.exitPrice!!, 1e-9)
        assertTrue(t.pctReturn < -5.0)
    }

    @Test fun aGapUnderTheStopFillsAtTheOpen() {
        val gap = listOf(D(110.0, 111.0, 109.0, 110.5, 4000))
        val t = BullFlag.run("X", daily(series(after = listOf(breakout) + gap)), BullFlag.Params()).trades.single()
        assertEquals(110.0, t.exitPrice!!, 1e-9)
    }

    @Test fun onceUpOneRiskTheStopMovesToEntry() {
        // risk = 121 - 114.425 = 6.575; up one risk = 127.575.
        val up = listOf(D(121.0, 128.0, 120.5, 127.0, 2000), D(127.0, 127.5, 119.0, 119.5, 2000))
        val t = BullFlag.run("X", daily(series(after = listOf(breakout) + up)), BullFlag.Params()).trades.single()
        assertEquals("BREAKEVEN", t.exitReason)
        assertEquals(121.0, t.exitPrice!!, 1e-9)
    }

    @Test fun hourlyPaceComparesTheSamePointOfTheDay() {
        // Two bars a day, volume split evenly. On the breakout day the first bar
        // pokes over the line on light volume; the surge arrives in the second.
        val ds = series()
        val c = ArrayList<CandleData>()
        for ((i, d) in ds.withIndex()) {
            val t = i * 86_400L + 4 * 3600
            c.add(CandleData(t, d.o, d.h, d.l, d.c, d.v / 2))
            c.add(CandleData(t + 3600, d.c, d.h, d.l, d.c, d.v / 2))
        }
        val day = ds.size.toLong()
        val t0 = day * 86_400L + 4 * 3600
        c.add(CandleData(t0, 117.0, 120.0, 116.5, 119.5, 400))        // over the line, quiet
        c.add(CandleData(t0 + 3600, 119.5, 121.5, 119.0, 121.0, 3000)) // the surge
        val r = BullFlag.run("X", c, BullFlag.Params())
        val t = r.trades.single()
        assertEquals(t0 + 3600, t.entryTs)
        assertEquals(121.0, t.entryPrice, 1e-9)
        assertEquals("OPEN", t.exitReason)
    }

    @Test fun aBreakoutInADownMarketIsBlocked() {
        val ds = daily(series(after = listOf(breakout) + runToTarget))
        val r = BullFlag.run("X", ds, BullFlag.Params(), marketUp = { false })
        assertTrue(r.trades.isEmpty())
        assertTrue(r.marketBlocked >= 1)
        assertEquals(1, BullFlag.run("X", ds, BullFlag.Params(), marketUp = { true }).trades.size)
    }

    @Test fun marketTrendReadsOnlyClosesBeforeTheDay() {
        // 60 days at 100, then a jump to 200 on day 60: above its 50-day average
        // from day 60's close, so day 61 is the first day that may buy.
        val idx = (0 until 70).map { i ->
            val c = if (i < 60) 100.0 else 200.0
            CandleData(i * 86_400L + 4 * 3600, c, c, c, c, 0)
        }
        val up = BullFlag.marketTrend(idx, BullFlag.MarketRule.ABOVE_SMA50)
        assertTrue(!up(59L))
        assertTrue(!up(60L))
        assertTrue(up(61L))
        assertTrue(!BullFlag.marketTrend(idx, BullFlag.MarketRule.ABOVE_SMA200)(65L))   // not enough history
    }

    @Test fun portfolioSkipsBreakoutsWhenEverySeatIsBusy() {
        val s = BullFlag.detect(BullFlag.toDays(daily(series())), 89, BullFlag.Params())!!
        fun tr(sym: String, entry: Long, exit: Long, pct: Double) =
            BullFlag.Trade(sym, s, entry, 100.0, 2.0, 90.0, 120.0, exit, 100.0, "TARGET", pct, 1)
        val trades = listOf(tr("A", 0, 100, 10.0), tr("B", 50, 200, -5.0), tr("C", 150, 300, 20.0))
        val p = BullFlag.portfolio(trades, seats = 1, firstTs = 0, lastTs = 300)
        assertEquals(2, p.taken)
        assertEquals(1, p.skippedFull)
        assertEquals(1000.0 * 1.10 * 1.20, p.finalValue, 1e-6)
    }
}
