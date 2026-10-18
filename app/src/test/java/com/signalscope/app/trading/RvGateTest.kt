package com.signalscope.app.trading

import com.signalscope.app.trading.RvGate.Side
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

    @Test fun `a buy high in the range goes at once when the price is rising`() {
        assertTrue(buy(108.0, +0.5).go)
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
}
