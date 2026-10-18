package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * WEEKLY ZONE ORDERS: where they buy, where they give up, and what becomes of
 * one waiting through the days after it was placed.
 */
class FloorWeekTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun sec(day: String, hh: Int, mm: Int) =
        LocalDate.parse(day).atTime(hh, mm).atZone(berlin).toEpochSecond()

    private fun bar(day: String, hh: Int, mm: Int, o: Double, h: Double, l: Double, c: Double) =
        CandleData(sec(day, hh, mm), o, h, l, c, 1000)

    // The week before Mon 12 Oct: low 90 on Tuesday, high 110 on Thursday. The
    // Friday before it, far lower, is outside the week and must not count.
    private val week = FloorOrders.byDay(listOf(
        bar("2026-10-02", 9, 0, 60.0, 61.0, 50.0, 60.0),
        bar("2026-10-05", 9, 0, 100.0, 101.0, 95.0, 100.0),
        bar("2026-10-06", 9, 0, 100.0, 100.0, 90.0, 95.0),
        bar("2026-10-07", 9, 0, 95.0, 104.0, 94.0, 103.0),
        bar("2026-10-08", 9, 0, 103.0, 110.0, 102.0, 108.0),
        bar("2026-10-09", 9, 0, 108.0, 108.0, 99.0, 100.0)), berlin)

    @Test fun `the zones are the bottom and top tenth of the last five trading days`() {
        val z = FloorOrders.weekZone(week, LocalDate.parse("2026-10-12"), 10.0)!!
        assertEquals(90.0, z.low, 1e-9)
        assertEquals(110.0, z.high, 1e-9)
        assertEquals(92.0, z.buyAt, 1e-9)      // 90 + 10% of the 20-point range
        assertEquals(108.0, z.cancelAt, 1e-9)  // 110 − 10% of it
    }

    @Test fun `no zones without a full week`() {
        assertNull(FloorOrders.weekZone(week, LocalDate.parse("2026-10-07"), 10.0))
    }

    private val buyAt = 92.0
    private val second = FloorOrders.secondPrice(92.0, 2.0)   // 90.16
    private val cancelAt = 108.0

    @Test fun `it waits through a day that never dips and fills on a later one`() {
        val bars = listOf(
            bar("2026-10-12", 9, 0, 100.0, 101.0, 99.0, 100.0),
            bar("2026-10-13", 9, 0, 95.0, 96.0, 91.5, 92.0))
        val f = FloorOrders.weekFate(bars, buyAt, second, cancelAt)
        assertEquals(sec("2026-10-13", 9, 0), f.first!!.sec)
        assertEquals(92.0, f.first!!.price, 1e-9)
        assertNull(f.second)                    // 91.5 never went under 90.16
        assertEquals(0L, f.cancelSec)
    }

    @Test fun `reaching the resistance zone first cancels it, and a later dip buys nothing`() {
        val bars = listOf(
            bar("2026-10-12", 9, 0, 105.0, 108.0, 104.0, 107.0),   // touches 108: enough
            bar("2026-10-13", 9, 0, 95.0, 96.0, 89.0, 90.0))
        val f = FloorOrders.weekFate(bars, buyAt, second, cancelAt)
        assertEquals(sec("2026-10-12", 9, 0), f.cancelSec)
        assertNull(f.first)
        assertNull(f.second)
    }

    @Test fun `a dip first, then resistance - the half already in stays, the other is given up`() {
        val bars = listOf(
            bar("2026-10-12", 9, 0, 95.0, 95.0, 91.0, 93.0),
            bar("2026-10-14", 9, 0, 100.0, 109.0, 99.0, 108.5),
            bar("2026-10-15", 9, 0, 95.0, 95.0, 85.0, 86.0))
        val f = FloorOrders.weekFate(bars, buyAt, second, cancelAt)
        assertEquals(92.0, f.first!!.price, 1e-9)
        assertNull(f.second)                    // the 85 came after it gave up
        assertEquals(sec("2026-10-14", 9, 0), f.cancelSec)
    }

    @Test fun `a morning that opens below both halves fills both at the open`() {
        val bars = listOf(bar("2026-10-14", 9, 0, 89.0, 90.0, 88.0, 89.5))
        val f = FloorOrders.weekFate(bars, buyAt, second, cancelAt)
        assertEquals(89.0, f.first!!.price, 1e-9)
        assertEquals(89.0, f.second!!.price, 1e-9)
    }

    @Test fun `one bar that reaches both zones is taken as having given up`() {
        val bars = listOf(bar("2026-10-12", 9, 0, 100.0, 109.0, 91.0, 100.0))
        val f = FloorOrders.weekFate(bars, buyAt, second, cancelAt)
        assertNull(f.first)
        assertEquals(sec("2026-10-12", 9, 0), f.cancelSec)
    }
}
