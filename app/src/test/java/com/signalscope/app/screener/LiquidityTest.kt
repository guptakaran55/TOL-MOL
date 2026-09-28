package com.signalscope.app.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * THE LIQUIDITY FLOOR.
 *
 * A universe built by unioning a downloaded index list with a stored one only
 * ever grows, and what it grows by is the long tail. This is what keeps that
 * tail out — not because a €200 order could not fill in a thin name (it could,
 * a hundred times over) but because the spread on one exceeds the edge the
 * strategy is trying to capture, and because a momentum ranking cannot tell a
 * micro-cap that spiked on an announcement from an opportunity.
 *
 * Two properties carry the whole design: the measure must be robust to a single
 * busy day, and not knowing must never be treated as a failing grade.
 */
class LiquidityTest {

    private fun bars(vararg turnoverPerDay: Pair<Double, Double>): List<DailyBar> =
        turnoverPerDay.mapIndexed { i, (close, vol) ->
            DailyBar(LocalDate.of(2026, 1, 1).plusDays(i.toLong()),
                close = close, volume = vol)
        }

    private fun flat(days: Int, close: Double, vol: Double) =
        (0 until days).map {
            DailyBar(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), close = close, volume = vol)
        }

    // ── the median, and why it is not the mean ──────────────────────────────

    @Test fun `turnover is price times volume`() {
        assertEquals(1_000_000.0, Liquidity.medianTurnover(flat(30, 10.0, 100_000.0)), 1e-6)
    }

    @Test fun `one enormous day cannot carry a thin name over the line`() {
        // THE REASON IT IS A MEDIAN. A single announcement lifts a day's volume
        // twentyfold, and that day is exactly what a thin stock looks like at the
        // moment it is most dangerous to admit. An average would let it in.
        val thin = flat(59, 1.0, 100_000.0) +           // €100k a day
            bars(1.0 to 100_000_000.0)                   // one €100m day
        val mean = thin.takeLast(60).sumOf { it.close * it.volume } / 60.0
        assertTrue("the average is dragged over any sane floor", mean > 1_500_000.0)
        assertEquals("the median is not", 100_000.0, Liquidity.medianTurnover(thin), 1e-6)
        assertFalse(Liquidity.passes(thin, 2_000_000.0))
    }

    @Test fun `an even-length window averages the middle two`() {
        val b = bars(1.0 to 100.0, 1.0 to 200.0, 1.0 to 300.0, 1.0 to 400.0)
        assertEquals(250.0, Liquidity.medianTurnover(b), 1e-6)
    }

    @Test fun `only the recent window counts`() {
        // A name that used to trade heavily and has since gone quiet must be
        // judged on what it does now.
        val was = flat(200, 1.0, 10_000_000.0)
        val now = flat(60, 1.0, 50_000.0)
        assertEquals(50_000.0, Liquidity.medianTurnover(was + now), 1e-6)
    }

    // ── not knowing is not a verdict ────────────────────────────────────────

    @Test fun `a name with no volume data is admitted, not cut`() {
        // A provider that omits volume sends zeros. Reading that as a failing
        // grade would empty the universe on a feed change rather than on
        // anything about the market — the same mistake as reading an unreadable
        // holdings list as an empty account.
        val noVol = flat(60, 10.0, 0.0)
        assertEquals(0.0, Liquidity.medianTurnover(noVol), 1e-9)
        assertTrue("unknown passes", Liquidity.passes(noVol, 2_000_000.0))
    }

    @Test fun `an empty series passes rather than failing`() {
        assertTrue(Liquidity.passes(emptyList(), 2_000_000.0))
    }

    @Test fun `a floor of zero switches the filter off entirely`() {
        assertTrue(Liquidity.passes(flat(60, 1.0, 1.0), 0.0))
        assertTrue(Liquidity.passes(flat(60, 1.0, 1.0), -1.0))
    }

    // ── the threshold itself ────────────────────────────────────────────────

    @Test fun `sitting exactly on the floor passes`() {
        assertTrue(Liquidity.passes(flat(60, 10.0, 200_000.0), 2_000_000.0))
        assertFalse(Liquidity.passes(flat(60, 10.0, 199_000.0), 2_000_000.0))
    }

    @Test fun `each market gets a default in its own currency`() {
        // One number cannot mean the same thing in euros, dollars and rupees.
        assertEquals(2_000_000.0, Liquidity.defaultFor("de"), 1e-6)
        assertEquals(10_000_000.0, Liquidity.defaultFor("us"), 1e-6)
        assertEquals(100_000_000.0, Liquidity.defaultFor("in"), 1e-6)
        assertEquals("crypto has no such filter", 0.0, Liquidity.defaultFor("binance"), 1e-6)
    }

    // ── the Discovery scan measures the same way ────────────────────────────

    private fun candles(days: Int, close: Double, vol: Long) =
        (0 until days).map {
            com.signalscope.app.data.CandleData(1_700_000_000L + it * 86_400L,
                close, close, close, close, vol)
        }

    @Test fun `candles and daily bars give the same answer for the same days`() {
        // THE WHOLE POINT OF ONE RULE. Discovery holds candles, the watcher holds
        // daily bars; if the two did their own arithmetic a company could pass on
        // one screen and fail on the next.
        val spikeDay = 1.0 to 100_000_000.0
        val bars = flat(59, 1.0, 100_000.0) + bars(spikeDay)
        val cs = candles(59, 1.0, 100_000L) +
            com.signalscope.app.data.CandleData(1_800_000_000L, 1.0, 1.0, 1.0, 1.0, 100_000_000L)
        assertEquals(Liquidity.medianTurnover(bars), Liquidity.medianTurnoverOfCandles(cs), 1e-6)
        assertEquals(Liquidity.passes(bars, 2_000_000.0), Liquidity.passesCandles(cs, 2_000_000.0))
    }

    @Test fun `a thin German listing is cut from Discovery and a liquid one kept`() {
        // Measured on the phone: much of the broker's Xetra list turns over next
        // to nothing, which is what filled Discovery with names nobody asked for.
        assertFalse(Liquidity.passesCandles(candles(60, 12.0, 800L), Liquidity.DEFAULT_DE))
        assertTrue(Liquidity.passesCandles(candles(60, 40.0, 250_000L), Liquidity.DEFAULT_DE))
    }

    @Test fun `unknown volume passes in Discovery too`() {
        assertTrue(Liquidity.passesCandles(candles(60, 10.0, 0L), Liquidity.DEFAULT_DE))
        assertTrue(Liquidity.passesCandles(emptyList(), Liquidity.DEFAULT_DE))
        assertTrue("a floor of zero is off", Liquidity.passesCandles(candles(60, 1.0, 1L), 0.0))
    }

    @Test fun `the German default keeps mid caps and drops the micro tail`() {
        // MDAX turns over €5m to €50m a day, SDAX roughly €1m to €10m, micro
        // caps well under €0.5m. The line is meant to fall between the last two.
        val mdax = flat(60, 50.0, 200_000.0)        // €10m a day
        val sdaxLiquid = flat(60, 20.0, 150_000.0)  // €3m a day
        val micro = flat(60, 3.0, 40_000.0)         // €120k a day
        val f = Liquidity.DEFAULT_DE
        assertTrue(Liquidity.passes(mdax, f))
        assertTrue("the traded end of the small caps survives", Liquidity.passes(sdaxLiquid, f))
        assertFalse("the micro tail does not", Liquidity.passes(micro, f))
    }
}
