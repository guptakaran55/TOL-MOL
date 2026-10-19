package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.screener.DailyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * THE MONTHLY-LOW CHAIN'S RULES — the parts of the user's decision chain that do
 * not need a market to test: which stocks count as at their monthly low, how long
 * a watch lasts, which outlook verdicts count as yes, the champion it trades on,
 * and the moment the four timeframes turn green together.
 */
class ChainRulesTest {

    private val start = LocalDate.of(2026, 9, 1)

    /** One bar per weekday, each day's low and high given; close mid-range. */
    private fun days(ranges: List<Pair<Double, Double>>): List<DailyBar> {
        val out = ArrayList<DailyBar>()
        var d = start
        for ((lo, hi) in ranges) {
            while (d.dayOfWeek.value > 5) d = d.plusDays(1)
            out.add(DailyBar(d, (lo + hi) / 2, hi, lo))
            d = d.plusDays(1)
        }
        return out
    }

    @Test fun `the built-in champion is the conservative B_SLOW config`() {
        val c = ChainRules.championConfig("de")
        assertNotNull("the built-in config must parse", c)
        c!!
        assertEquals("240min", c.resample)
        assertEquals("120min", c.exitBars)
        assertEquals(240, c.warmupBars)
        assertEquals(0.7, c.entry.accelMinSigma, 1e-9)       // conservative; aggressive is 0.5
        assertTrue(c.entry.slopeFlipUpPersistent)
        assertTrue(c.gates.smaEnabled)
        assertEquals("ema", c.gates.smaKind)
        assertEquals(4.0, c.exit.stopLossPct, 1e-9)
        assertEquals(30.0, c.exit.macdDistancePct, 1e-9)
        assertEquals(0L, c.barAnchorSec)
        // India trades on its own clock, like every NSE book
        assertEquals(19_800L, ChainRules.championConfig("in")!!.barAnchorSec)
    }

    @Test fun `a day whose low reached the bottom tenth of the month is a touch`() {
        val month = List(20) { 100.0 to 110.0 }
        assertTrue(ChainRules.touchedMonthlyLow(days(month + (100.5 to 104.0))))      // 5% up the range
        assertTrue(ChainRules.touchedMonthlyLow(days(month + (98.0 to 103.0))))       // a new low counts
        assertFalse(ChainRules.touchedMonthlyLow(days(month + (103.0 to 108.0))))     // 30% up: no
    }

    @Test fun `too short a history is never a touch`() {
        assertFalse(ChainRules.touchedMonthlyLow(days(List(14) { 100.0 to 110.0 })))
        assertFalse(ChainRules.touchedMonthlyLow(days(List(20) { 100.0 to 100.0 })))  // no range at all
    }

    @Test fun `only the last 21 days make the range`() {
        // An old, much lower low falls out of the window, so today's low is the bottom again.
        val ranges = listOf(50.0 to 60.0) + List(20) { 100.0 to 110.0 } + listOf(100.2 to 104.0)
        assertTrue(ChainRules.touchedMonthlyLow(days(ranges)))
    }

    @Test fun `a watch counts weekdays only`() {
        val fri = LocalDate.of(2026, 10, 16)
        assertEquals(LocalDate.of(2026, 10, 19), ChainRules.addTradingDays(fri, 1))
        assertEquals(LocalDate.of(2026, 11, 13), ChainRules.addTradingDays(fri, 20))
    }

    @Test fun `only BUY and STRONG BUY count as a yes`() {
        assertTrue(ChainRules.positive("BUY"))
        assertTrue(ChainRules.positive("strong buy"))
        listOf("WATCH", "AVOID", "STRONG AVOID", "HOLD", "NO VERDICT", "").forEach {
            assertFalse(it, ChainRules.positive(it))
        }
    }

    // ── the four timeframes turning green ───────────────────────────────────

    /** Round-the-clock fifteen-minute bars: falling for [downBars], then rising. */
    private fun vShape(downBars: Int, upBars: Int, t0: Long = 1_790_000_100L / 900L * 900L): List<CandleData> {
        val out = ArrayList<CandleData>()
        var px = 100.0
        for (i in 0 until downBars + upBars) {
            val step = if (i < downBars) -0.08 else 0.12
            val o = px; px += step
            out.add(CandleData(t0 + i * 900L, o, maxOf(o, px), minOf(o, px), px, 1000))
        }
        return out
    }

    @Test fun `a fall then a rise turns all four green once, after the low`() {
        val bars = vShape(downBars = 1200, upBars = 400)
        val now = bars.last().timestamp + 3600
        val turn = ChainRules.firstGreenTurn(bars, { 1.0 }, ZoneOffset.UTC, afterSec = 0L,
            nowSec = now, delaySec = 0L, smoothing = 1)
        assertNotNull(turn)
        // after the bottom, never before it
        assertTrue(turn!!.barSec > bars[1200].timestamp)
        assertEquals(turn.barSec + 900L, turn.endSec)
    }

    @Test fun `a falling day keeps the dots from turning green`() {
        val bars = vShape(downBars = 1200, upBars = 400)
        val now = bars.last().timestamp + 3600
        assertNull(ChainRules.firstGreenTurn(bars, { -1.0 }, ZoneOffset.UTC, 0L, now, 0L, 1))
        assertNull(ChainRules.firstGreenTurn(bars, { null }, ZoneOffset.UTC, 0L, now, 0L, 1))
    }

    @Test fun `a turn before the watch began is not a buy`() {
        val bars = vShape(downBars = 1200, upBars = 400)
        val now = bars.last().timestamp + 3600
        val first = ChainRules.firstGreenTurn(bars, { 1.0 }, ZoneOffset.UTC, 0L, now, 0L, 1)!!
        val later = ChainRules.firstGreenTurn(bars, { 1.0 }, ZoneOffset.UTC, first.barSec + 900L, now, 0L, 1)
        // A steady rise stays green, so there is no fresh turn after the first one.
        assertNull(later)
    }

    @Test fun `bars not yet finished are not judged`() {
        val bars = vShape(downBars = 1200, upBars = 400)
        val first = ChainRules.firstGreenTurn(bars, { 1.0 }, ZoneOffset.UTC, 0L,
            bars.last().timestamp + 3600, 0L, 1)!!
        // Seen from the moment that bar was still forming, nothing has turned yet.
        assertNull(ChainRules.firstGreenTurn(bars, { 1.0 }, ZoneOffset.UTC, 0L, first.barSec + 899L, 0L, 1))
    }

    @Test fun `the champion is asked again only once a new two-hour bar has finished`() {
        val h2 = 7200L
        val delay = 900L
        // 10:00 UTC bar boundary; visible 15 minutes + a minute later.
        val boundary = 1_790_006_400L / h2 * h2
        assertEquals(boundary + delay + 60, ChainRules.frameVisibleAt(boundary + delay + 60, h2, 0L, delay))
        assertEquals(boundary + delay + 60, ChainRules.frameVisibleAt(boundary + h2 - 1, h2, 0L, delay))
        // Just before it becomes visible, the previous bar is still the newest.
        assertEquals(boundary - h2 + delay + 60, ChainRules.frameVisibleAt(boundary + delay + 59, h2, 0L, delay))
        // India's grid is shifted by 5:30.
        val inVisible = ChainRules.frameVisibleAt(boundary + 19_800L + delay + 60, h2, 19_800L, delay)
        assertEquals(0L, Math.floorMod(inVisible - delay - 60 - 19_800L, h2))
    }

    @Test fun `the next price is the first open at or after the moment`() {
        val bars = vShape(10, 10)
        val f = ChainRules.nextOpen(bars, bars[3].timestamp + 1)!!
        assertEquals(bars[4].timestamp, f.sec)
        assertEquals(bars[4].open, f.price, 1e-12)
        assertNull(ChainRules.nextOpen(bars, bars.last().timestamp + 1))
        assertEquals(1800L, ChainRules.ceil15(901L))
        assertEquals(900L, ChainRules.ceil15(900L))
    }
}
