package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * THE FLOOR-ORDER RULES: the recent low, fills only below the order, the day's
 * end, the sell day and the seats.
 */
class FloorOrdersTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val lastBar = LocalTime.of(17, 15)

    /** A 15-minute bar starting at [hh]:[mm] Berlin time on [day]. */
    private fun bar(day: String, hh: Int, mm: Int, o: Double, h: Double, l: Double, c: Double) =
        CandleData(LocalDate.parse(day).atTime(hh, mm).atZone(berlin).toEpochSecond(), o, h, l, c, 1000)

    private fun sec(day: String, hh: Int, mm: Int) =
        LocalDate.parse(day).atTime(hh, mm).atZone(berlin).toEpochSecond()

    // Mon 28 Sep → Fri 2 Oct 2026
    private val bars = listOf(
        bar("2026-09-24", 9, 0, 100.0, 101.0, 97.0, 100.0),   // Thu — outside a 3-day window
        bar("2026-09-25", 9, 0, 100.0, 102.0, 99.0, 101.0),   // Fri
        bar("2026-09-28", 9, 0, 101.0, 103.0, 98.5, 102.0),   // Mon — the 3-day low
        bar("2026-09-29", 9, 0, 102.0, 104.0, 100.5, 103.0),  // Tue
        bar("2026-09-30", 9, 0, 103.0, 103.5, 99.0, 99.5),    // Wed 09:00
        bar("2026-09-30", 9, 15, 99.5, 99.8, 98.4, 98.9),     // Wed 09:15 — trades below 98.5
        bar("2026-09-30", 17, 15, 98.9, 99.2, 98.7, 99.0),    // Wed last bar
        bar("2026-10-01", 9, 0, 99.0, 100.0, 97.0, 99.8),
        bar("2026-10-01", 17, 15, 99.8, 100.1, 99.5, 100.0),
        bar("2026-10-02", 17, 15, 100.0, 101.0, 99.9, 101.0)
    )
    private val days = FloorOrders.byDay(bars, berlin)

    @Test fun `only both long timescales rising qualifies`() {
        assertTrue(FloorOrders.inUptrend(1, 1))
        assertFalse(FloorOrders.inUptrend(1, -1))
        assertFalse(FloorOrders.inUptrend(-1, 1))
        assertFalse(FloorOrders.inUptrend(1, null))
    }

    @Test fun `the floor is the lowest low of the last three trading days before today`() {
        // Wed's plan looks at Fri, Mon, Tue — not Thu's 97, and not Wed itself.
        assertEquals(98.5, FloorOrders.priorLow(days, LocalDate.parse("2026-09-30"), 3)!!, 1e-9)
        assertNull(FloorOrders.priorLow(days, LocalDate.parse("2026-09-25"), 3))
        assertEquals(98.0 * 1.0, FloorOrders.secondPrice(100.0, 2.0), 1e-9)
    }

    @Test fun `a fill needs the price to trade BELOW the order, not just touch it`() {
        val wed = days[LocalDate.parse("2026-09-30")]!!
        val f = FloorOrders.firstFill(wed, 98.5)!!
        assertEquals(sec("2026-09-30", 9, 15), f.sec)
        assertEquals(98.5, f.price, 1e-9)
        // Low 98.4 is exactly touched, not beaten, by an order at 98.4.
        assertNull(FloorOrders.firstFill(wed, 98.4))
    }

    @Test fun `a gap down fills at the opening price, which is better`() {
        val gap = listOf(bar("2026-09-30", 9, 0, 95.0, 96.0, 94.0, 95.5))
        assertEquals(95.0, FloorOrders.firstFill(gap, 98.5)!!.price, 1e-9)
    }

    @Test fun `today is not over while its last bar is still forming`() {
        val today = LocalDate.parse("2026-09-30")
        assertFalse(FloorOrders.dayComplete(days, today, today, lastBar, berlin, sec("2026-09-30", 17, 20)))
        assertTrue(FloorOrders.dayComplete(days, today, today, lastBar, berlin, sec("2026-09-30", 17, 31)))
        // Any earlier day is over, bars or not.
        assertTrue(FloorOrders.dayComplete(days, LocalDate.parse("2026-09-29"), today, lastBar, berlin, 0L))
    }

    @Test fun `sold on the third trading day after the buy, counted from the data`() {
        assertEquals(LocalDate.parse("2026-10-02"),
            FloorOrders.sellDay(days, LocalDate.parse("2026-09-29"), 3))
        assertNull(FloorOrders.sellDay(days, LocalDate.parse("2026-09-30"), 3))   // Fri 2 Oct is only the 2nd
        assertEquals(101.0, FloorOrders.closeOf(days, LocalDate.parse("2026-10-02"))!!, 1e-9)
    }

    @Test fun `a seat is busy from the buy day through the sell day`() {
        val d = { s: String -> LocalDate.parse(s) }
        val spans = listOf(
            d("2026-09-28") to d("2026-10-01"),   // sold at Thu's close
            d("2026-09-30") to null)               // still held
        assertEquals(2, FloorOrders.seatsBusy(spans, d("2026-10-01")))   // Thu: both still in hand
        assertEquals(1, FloorOrders.seatsBusy(spans, d("2026-10-02")))
        assertEquals(0, FloorOrders.seatsBusy(spans, d("2026-09-25")))
    }

    @Test fun `what happened after a buy - returns once each day closes, and the dip from the fill`() {
        val o = FloorOrders.outcome(days, LocalDate.parse("2026-09-30"), sec("2026-09-30", 9, 15), 98.5,
            LocalDate.parse("2026-10-02"), lastBar, berlin, sec("2026-10-02", 17, 31))
        assertEquals((100.0 / 98.5 - 1) * 100, o.ret1!!, 1e-9)
        assertNull(o.ret3)                     // only two trading days in the data so far
        assertEquals((97.0 / 98.5 - 1) * 100, o.dip!!, 1e-9)   // Thu's 97 — after the fill
        assertFalse(o.done)
    }
}
