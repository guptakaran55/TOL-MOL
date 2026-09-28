package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxAndVelocityTest {

    // ── €200 a seat means €200 ───────────────────────────────────────────────

    @Test fun `the measured US shortfall is exactly the exchange rate`() {
        // Measured on the phone: every US order was sized at 200 and Trading 212
        // charged about €176 for it — $200 at roughly 1.136 dollars to the euro.
        val rate = 200.0 / 176.04
        assertEquals(200.0, 176.04 * rate, 1e-9)
        // Converted, the same €200 budget now asks the broker for ~$227.
        assertEquals(227.2, 200.0 * rate, 0.1)
    }

    @Test fun `an ordinary rate is believed`() {
        assertTrue(FxRates.plausible(1.14, null))
        assertTrue(FxRates.plausible(1.14, 1.12))
    }

    @Test fun `a garbage print can never inflate an order`() {
        // The conversion MULTIPLIES what is spent. A yen-style 114 read as a
        // euro-dollar rate would turn €200 into a $22,800 order.
        assertFalse(FxRates.plausible(114.0, null))
        assertFalse(FxRates.plausible(0.0, null))
        assertFalse(FxRates.plausible(Double.NaN, null))
        assertFalse(FxRates.plausible(-1.1, null))
    }

    @Test fun `a jump no real session makes is refused`() {
        assertFalse("30% in an hour is a bad print", FxRates.plausible(1.48, 1.14))
        assertTrue("a volatile day is fine", FxRates.plausible(1.16, 1.14))
    }

    // ── the price dot, on 15-minute candles ─────────────────────────────────

    private fun c(ts: Long, close: Double) = CandleData(ts, close, close, close, close, 1000L)
    private val t0 = 1_790_000_100L - (1_790_000_100L % 900L)   // on a 15-minute boundary

    @Test fun `velocity is the last completed candle against the one before`() {
        val cs = listOf(c(t0, 100.0), c(t0 + 900, 100.5), c(t0 + 1800, 101.0))
        val r = PriceVelocity.last15m(cs, t0 + 2700 + 5, 0L)!!
        assertEquals((101.0 / 100.5 - 1) * 100, r.pct, 1e-9)
        assertEquals(t0 + 2700, r.endSec)
    }

    @Test fun `the candle still forming is never read`() {
        // At t0+2000 the third candle is only a third of the way through.
        val cs = listOf(c(t0, 100.0), c(t0 + 900, 100.5), c(t0 + 1800, 90.0))
        val r = PriceVelocity.last15m(cs, t0 + 2000, 0L)!!
        assertEquals((100.5 / 100.0 - 1) * 100, r.pct, 1e-9)
    }

    @Test fun `a German candle waits for the feed's fifteen minutes`() {
        // Complete by the clock at t0+2700, but Yahoo's Xetra prices for it only
        // arrive by t0+3600; until then the reading comes from the candle before.
        val cs = listOf(c(t0, 100.0), c(t0 + 900, 100.5), c(t0 + 1800, 99.0))
        val early = PriceVelocity.last15m(cs, t0 + 3000, FeedDelay.XETRA_SEC)!!
        assertEquals(t0 + 1800, early.endSec)
        val later = PriceVelocity.last15m(cs, t0 + 3600, FeedDelay.XETRA_SEC)!!
        assertEquals(t0 + 2700, later.endSec)
        assertTrue(later.pct < 0)
    }

    @Test fun `fewer than two completed candles gives no dot`() {
        assertNull(PriceVelocity.last15m(listOf(c(t0, 100.0)), t0 + 5000, 0L))
        assertNull(PriceVelocity.last15m(emptyList(), t0 + 5000, 0L))
    }
}
