package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.screener.DailyBar
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * THE SALE RULES SHARED BY FLOOR ORDERS AND MACD TURNS — the stop, the target,
 * the time limit and the broken trend — and the weekly trend and next-price buy
 * that came with them.
 */
class FloorExitsTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val lastBar = LocalTime.of(17, 15)

    private fun bar(day: String, hh: Int, mm: Int, o: Double, h: Double, l: Double, c: Double) =
        CandleData(sec(day, hh, mm), o, h, l, c, 1000)

    private fun sec(day: String, hh: Int, mm: Int) =
        LocalDate.parse(day).atTime(hh, mm).atZone(berlin).toEpochSecond()

    private fun d(s: String) = LocalDate.parse(s)

    /** After everything in the data has finished trading. */
    private val later = sec("2026-10-09", 18, 0)

    private fun walk(bars: List<CandleData>, buys: List<FloorOrders.Buy>, hold: Int = 10,
                     trendDay: LocalDate? = null, today: String = "2026-10-09", now: Long = later,
                     until: Long = Long.MAX_VALUE) =
        FloorOrders.walk(FloorOrders.byDay(bars, berlin), buys, 4.0, 7.0, hold, trendDay,
            d(today), lastBar, berlin, now, until)

    // Wed 30 Sep: the first half fills at 100 in the 09:00 bar, the second at 98 at 09:15.
    private val wed = listOf(
        bar("2026-09-30", 9, 0, 100.5, 101.0, 99.8, 100.2),
        bar("2026-09-30", 9, 15, 100.2, 100.4, 97.9, 98.1),
        bar("2026-09-30", 17, 15, 98.9, 99.2, 98.7, 99.0))
    private val firstHalf = FloorOrders.Buy(sec("2026-09-30", 9, 0), 100.0, 100.0)
    private val secondHalf = FloorOrders.Buy(sec("2026-09-30", 9, 15), 98.0, 100.0)
    private val avgBoth = 200.0 / (100.0 / 100.0 + 100.0 / 98.0)

    @Test fun `the stop is 4 percent under the AVERAGE once the second half joins`() {
        val bars = wed + bar("2026-10-01", 9, 0, 96.0, 96.5, 94.9, 95.5)
        val p = walk(bars, listOf(firstHalf, secondHalf))
        val ex = p.exit!!
        assertEquals("stop", ex.reason)
        assertEquals(avgBoth * 0.96, ex.price, 1e-9)
        assertEquals(d("2026-10-01"), ex.day)
        assertEquals(200.0, p.cost, 1e-9)            // both halves were sold
    }

    @Test fun `a gap below the stop sells at the opening price, which is worse`() {
        val bars = wed + bar("2026-10-01", 9, 0, 94.0, 95.0, 93.5, 94.5)
        val ex = walk(bars, listOf(firstHalf, secondHalf)).exit!!
        assertEquals("stop", ex.reason)
        assertEquals(94.0, ex.price, 1e-9)
    }

    @Test fun `the target needs the price to trade ABOVE it`() {
        val touch = wed + bar("2026-10-01", 10, 0, 105.0, 107.0, 104.0, 106.0)
        assertNull(walk(touch, listOf(firstHalf)).exit)
        val through = touch + bar("2026-10-01", 10, 15, 106.0, 107.5, 105.5, 107.2)
        val ex = walk(through, listOf(firstHalf)).exit!!
        assertEquals("target", ex.reason)
        assertEquals(107.0, ex.price, 1e-9)
        assertEquals(sec("2026-10-01", 10, 15), ex.sec)
    }

    @Test fun `a gap above the target sells at the opening price, which is better`() {
        val bars = wed + bar("2026-10-01", 9, 0, 109.0, 110.0, 108.5, 109.5)
        val ex = walk(bars, listOf(firstHalf)).exit!!
        assertEquals("target", ex.reason)
        assertEquals(109.0, ex.price, 1e-9)
    }

    @Test fun `the target is not read in the bar that bought - its high may have come first`() {
        val bars = listOf(
            bar("2026-09-30", 9, 0, 108.0, 108.0, 99.5, 100.0),   // fill at 100, high 108 before it
            bar("2026-09-30", 9, 15, 100.0, 106.0, 99.8, 105.0))
        val p = walk(bars, listOf(FloorOrders.Buy(sec("2026-09-30", 9, 0), 100.0, 200.0)),
            today = "2026-09-30", now = sec("2026-09-30", 9, 40))
        assertNull(p.exit)
        assertEquals(96.0, p.stop, 1e-9)
        assertEquals(107.0, p.target, 1e-9)
    }

    @Test fun `a bar that reaches both counts as the stop`() {
        val bars = wed + bar("2026-10-01", 9, 0, 100.0, 108.0, 95.0, 100.0)
        assertEquals("stop", walk(bars, listOf(firstHalf)).exit!!.reason)
    }

    @Test fun `the stop is read in the bar that bought - the fill comes on the way down`() {
        val bars = listOf(bar("2026-09-30", 9, 0, 100.5, 101.0, 95.5, 96.0))
        val ex = walk(bars, listOf(FloorOrders.Buy(sec("2026-09-30", 9, 0), 100.0, 200.0)),
            today = "2026-09-30", now = sec("2026-09-30", 9, 20)).exit!!
        assertEquals("stop", ex.reason)
        assertEquals(96.0, ex.price, 1e-9)
    }

    // Wed buy, then Thu and Fri closes — nothing near the stop or the target.
    private val quiet = wed + listOf(
        bar("2026-10-01", 9, 0, 99.0, 100.0, 98.5, 99.5),
        bar("2026-10-01", 17, 15, 99.5, 100.5, 99.4, 100.0),
        bar("2026-10-02", 9, 0, 100.0, 101.0, 99.6, 100.5),
        bar("2026-10-02", 17, 15, 100.5, 101.5, 100.2, 101.0))

    @Test fun `the time limit sells at the close of the n-th trading day after the buy`() {
        val ex = walk(quiet, listOf(firstHalf), hold = 2).exit!!
        assertEquals("time", ex.reason)
        assertEquals(d("2026-10-02"), ex.day)
        assertEquals(101.0, ex.price, 1e-9)
        assertEquals(sec("2026-10-02", 17, 30), ex.sec)
    }

    @Test fun `not before the last bar of that day has finished`() {
        val p = walk(quiet, listOf(firstHalf), hold = 2, today = "2026-10-02", now = sec("2026-10-02", 17, 20))
        assertNull(p.exit)
    }

    @Test fun `a broken trend sells at the close of the day it was found`() {
        val ex = walk(quiet, listOf(firstHalf), trendDay = d("2026-10-01")).exit!!
        assertEquals("trend", ex.reason)
        assertEquals(d("2026-10-01"), ex.day)
        assertEquals(100.0, ex.price, 1e-9)
    }

    @Test fun `a trend day with no trading sells at the next close`() {
        // Found on a day the exchange was shut — the next session's close.
        val bars = wed + listOf(bar("2026-10-02", 17, 15, 100.5, 101.5, 100.2, 101.0))
        val ex = walk(bars, listOf(firstHalf), trendDay = d("2026-10-01")).exit!!
        assertEquals(d("2026-10-02"), ex.day)
    }

    @Test fun `asked up to a moment, only what happened before it counts`() {
        val bars = wed + bar("2026-10-01", 10, 0, 106.0, 107.5, 105.5, 107.2)
        assertNotNull(walk(bars, listOf(firstHalf), until = sec("2026-10-01", 11, 0)).exit)
        assertNull(walk(bars, listOf(firstHalf), until = sec("2026-10-01", 10, 0)).exit)
    }

    @Test fun `no stop and no target when switched off - sold on time only`() {
        val bars = wed + bar("2026-10-01", 9, 0, 90.0, 120.0, 80.0, 100.0) +
            bar("2026-10-01", 17, 15, 100.0, 100.5, 99.5, 100.0)
        val p = FloorOrders.walk(FloorOrders.byDay(bars, berlin), listOf(firstHalf), 0.0, 0.0, 1, null,
            d("2026-10-09"), lastBar, berlin, later)
        assertEquals("time", p.exit!!.reason)
        assertEquals(0.0, p.stop, 1e-9)
    }

    @Test fun `a trend breaks when either the week or the day is known and not rising`() {
        assertFalse(FloorOrders.trendBroken(1, 1))
        assertTrue(FloorOrders.trendBroken(-1, 1))
        assertTrue(FloorOrders.trendBroken(1, 0))
        assertFalse(FloorOrders.trendBroken(null, 1))   // missing data is not a verdict
    }

    @Test fun `weekly closes are finished weeks only`() {
        val bars = listOf(
            DailyBar(d("2026-09-18"), 10.0),   // Fri, week 38
            DailyBar(d("2026-09-21"), 11.0),   // Mon, week 39
            DailyBar(d("2026-09-25"), 12.0),   // Fri, week 39
            DailyBar(d("2026-09-28"), 13.0),   // Mon, week 40
            DailyBar(d("2026-09-30"), 14.0))   // Wed, week 40
        // Thursday of week 40: Mon–Wed is half a week, left out.
        assertArrayEquals(doubleArrayOf(10.0, 12.0), FloorOrders.completedWeeks(bars, d("2026-10-01")), 1e-9)
        // The Monday after: week 40 is finished, its last close is Wednesday's.
        assertArrayEquals(doubleArrayOf(10.0, 12.0, 14.0), FloorOrders.completedWeeks(bars, d("2026-10-05")), 1e-9)
    }

    @Test fun `a MACD turn buys at the open of the first bar from when it was seen`() {
        val days = FloorOrders.byDay(wed, berlin)
        val f = FloorOrders.nextOpen(days, d("2026-09-30"), sec("2026-09-30", 9, 15))!!
        assertEquals(100.2, f.price, 1e-9)
        assertEquals(sec("2026-09-30", 9, 15), f.sec)
        assertNull(FloorOrders.nextOpen(days, d("2026-09-30"), sec("2026-09-30", 17, 30)))
    }
}
