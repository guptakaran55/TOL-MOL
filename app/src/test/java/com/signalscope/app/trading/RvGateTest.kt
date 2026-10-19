package com.signalscope.app.trading

import com.signalscope.app.trading.RvGate.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RV GATE — when an automatic patient buy or sale starts. Sell at once near
 * the top of today's range or on a falling price; buy at once near the bottom or
 * on a rising price; otherwise wait, never more than two hours.
 *
 * Day used throughout: low 100, high 110 (range 10), ATR 4, an hour after the open.
 */
class RvGateTest {

    private fun sell(price: Double, velocity: Double?, waited: Long = 0L, mins: Long = 60L, belowStop: Boolean = false) =
        RvGate.decide(Side.SELL, price, 110.0, 100.0, 4.0, velocity, waited, mins, belowStop)

    private fun buy(price: Double, velocity: Double?, waited: Long = 0L, mins: Long = 60L) =
        RvGate.decide(Side.BUY, price, 110.0, 100.0, 4.0, velocity, waited, mins)

    @Test fun `a sale near the day's high goes at once, even while rising`() {
        assertTrue(sell(107.0, +0.5).go)          // 70% up the range
    }

    @Test fun `a sale low in the range goes at once when the price is falling`() {
        assertTrue(sell(102.0, -0.5).go)
    }

    @Test fun `a sale low in the range waits while the price rises`() {
        val d = sell(102.0, +0.5)
        assertFalse(d.go)
        assertTrue(d.why.contains("rising"))
    }

    @Test fun `a flat tape is not read as falling`() {
        assertFalse(sell(102.0, -0.1).go)          // under a twentieth of the ATR
    }

    @Test fun `a buy near the day's low goes at once, even while falling`() {
        assertTrue(buy(103.0, -0.5).go)            // 30% up the range
    }

    @Test fun `a buy in the upper middle of the range goes at once when the price is rising`() {
        assertTrue(buy(106.0, +0.5).go)            // 60% up
    }

    // ── NEAR THE TOP: A DIP AND A TURN, NOT A CHASE ─────────────────────────
    // Range 100–110, ATR 4: the top fifth is 108 and up; a dip is 0.8 under the
    // order's own high, a turn 0.4 back up off the dip's low.

    @Test fun `near today's high a rising price no longer starts a buy`() {
        val d = buy(108.5, +0.5)
        assertFalse(d.go)
        assertTrue(d.why.contains("dip and a turn"))
    }

    @Test fun `a dip from the order's own high and a turn up starts it, though still high in the range`() {
        var m = RvGate.remember(null, 109.5, 4.0)          // began waiting at 109.5
        m = RvGate.remember(m, 108.6, 4.0)                 // 0.9 under: a dip
        m = RvGate.remember(m, 108.4, 4.0)                 // its low
        m = RvGate.remember(m, 108.85, 4.0)                // 0.45 back up: the turn
        assertEquals(108.4, m.trough, 1e-9)
        val d = RvGate.decide(Side.BUY, 108.85, 110.0, 100.0, 4.0, +0.5, 0L, 60L, memory = m)
        assertTrue(d.go)
        assertTrue(d.why.contains("turned up"))
    }

    @Test fun `a dip too shallow to count leaves it waiting`() {
        var m = RvGate.remember(null, 109.5, 4.0)
        m = RvGate.remember(m, 109.0, 4.0)                 // only 0.5 under
        assertEquals(0.0, m.trough, 1e-9)
        m = RvGate.remember(m, 109.4, 4.0)
        assertFalse(RvGate.decide(Side.BUY, 109.4, 110.0, 100.0, 4.0, +0.5, 0L, 60L, memory = m).go)
    }

    @Test fun `a new high clears the dip`() {
        var m = RvGate.remember(null, 109.0, 4.0)
        m = RvGate.remember(m, 108.0, 4.0)                 // dipped
        m = RvGate.remember(m, 109.6, 4.0)                 // and ran to a new high instead
        assertEquals(109.6, m.peak, 1e-9)
        assertEquals(0.0, m.trough, 1e-9)
    }

    @Test fun `with no dip it still buys at the two-hour limit`() {
        val d = RvGate.decide(Side.BUY, 109.5, 110.0, 100.0, 4.0, +0.5, RvGate.MAX_WAIT_MS, 150L,
            memory = RvGate.remember(null, 109.5, 4.0))
        assertTrue(d.go); assertTrue(d.timedOut)
    }

    @Test fun `sales are unchanged by the dip rule`() {
        assertTrue(sell(108.5, +0.5).go)                  // top 40%: sells at once
    }

    @Test fun `a buy high in the range waits while the price drifts down`() {
        val d = buy(108.0, -0.5)
        assertFalse(d.go)
        assertTrue(d.why.contains("drifting down"))
    }

    @Test fun `after two hours it goes anyway, and says so`() {
        val s = sell(102.0, +0.5, waited = RvGate.MAX_WAIT_MS)
        assertTrue(s.go); assertTrue(s.timedOut)
        val b = buy(108.0, -0.5, waited = RvGate.MAX_WAIT_MS)
        assertTrue(b.go); assertTrue(b.timedOut)
    }

    @Test fun `a stop sale never waits`() {
        assertTrue(sell(102.0, +0.5, belowStop = true).go)
    }

    @Test fun `no live price means go, not wait blind`() {
        assertTrue(RvGate.decide(Side.BUY, null, 110.0, 100.0, 4.0, -0.5, 0L, 60L).go)
    }

    @Test fun `a narrow early range is ignored and velocity decides`() {
        // Ten minutes in: range 0.8 is under half an ATR, so "bottom of the
        // range" means nothing — a falling buy waits rather than going.
        val d = RvGate.decide(Side.BUY, 100.2, 100.8, 100.0, 4.0, -0.5, 0L, 10L)
        assertFalse(d.go)
        assertTrue(d.why.contains("too narrow"))
    }

    @Test fun `with no 15-minute prices it waits only at the open`() {
        assertFalse(RvGate.decide(Side.SELL, 102.0, 0.0, 0.0, 4.0, null, 0L, 5L).go)
        assertTrue(RvGate.decide(Side.SELL, 102.0, 0.0, 0.0, 4.0, null, 0L, 45L).go)
    }

    // ── TODAY'S SHAPE FROM SEVERAL DAYS OF BARS ─────────────────────────────
    // The gate now asks for five days of 15-minute bars (one day never cleared the
    // feed's 50-bar rule, so it always came back empty) and must use today's only.

    private val open = 1_792_396_800L                     // today 09:00, in seconds
    private fun bar(start: Long, o: Double, h: Double, l: Double, c: Double) =
        com.signalscope.app.data.CandleData(start, o, h, l, c, 1000L)
    private val earlier = (1..4).flatMap { d ->           // four earlier days, far wider ranges
        (0 until 34).map { i -> bar(open - d * 86_400L + i * 900L, 90.0, 120.0, 80.0, 95.0) } }
    private val today = listOf(
        bar(open, 100.0, 102.0, 99.0, 101.0),
        bar(open + 900, 101.0, 104.0, 100.5, 103.0),
        bar(open + 1800, 103.0, 103.5, 101.5, 102.0))

    @Test fun `only today's bars set the range`() {
        val (hi, lo, _) = RvGate.todayShape(earlier + today, open, open + 3600, 0L, 102.5)
        assertEquals(104.0, hi, 1e-9)
        assertEquals(99.0, lo, 1e-9)
    }

    @Test fun `velocity is the live price against the last finished bar`() {
        // At 10:00 all three bars (to 09:45) have finished: last close 102.0.
        val (_, _, v) = RvGate.todayShape(earlier + today, open, open + 3600, 0L, 102.5)
        assertEquals(0.5, v!!, 1e-9)
    }

    @Test fun `a delayed feed counts a bar only once it has arrived`() {
        // Germany's bars arrive 15 minutes late: at 09:58 only the 09:00 and 09:15
        // bars are in (the 09:30 one arrives at 10:00), so the reference is 103.0.
        val (_, _, v) = RvGate.todayShape(earlier + today, open, open + 3500, 900L, 102.5)
        assertEquals(-0.5, v!!, 1e-9)
    }

    @Test fun `nothing of today's means no shape at all`() {
        val (hi, lo, v) = RvGate.todayShape(earlier, open, open + 3600, 0L, 102.5)
        assertEquals(0.0, hi, 1e-9); assertEquals(0.0, lo, 1e-9); assertEquals(null, v)
    }
}
