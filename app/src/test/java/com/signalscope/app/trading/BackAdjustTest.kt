package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splits and demergers must not be booked as trades.
 *
 * Observed live on the India feed: RAYMOND 2025-05-13 went 1566 -> 551 overnight (a demerger)
 * and the engine closed it as a "-4% stop loss" worth -65% on one seat. Blocking the trade alone
 * would be insufficient — an unrepaired gap also corrupts the 200-bar sigma, the MACD and the
 * EMA gate for months, and this config's exit is sigma-scaled.
 */
class BackAdjustTest {

    private fun bars(vararg closes: Double): List<CandleData> =
        closes.mapIndexed { i, c ->
            CandleData(timestamp = 1_700_000_000L + i * 86_400L,
                open = c, high = c * 1.01, low = c * 0.99, close = c, volume = 1000L)
        }

    @Test
    fun aCleanSeriesIsReturnedUntouched() {
        val input = bars(100.0, 101.0, 99.5, 102.0, 103.5)
        assertSame("no action means the same list, not a copy", input, StrategyLabEngine.backAdjust(input))
    }

    @Test
    fun aHalvingSplitIsMadeContinuous() {
        // 1:2 split between bar 2 and 3 — the raw series reads as a -50% crash.
        val raw = bars(200.0, 204.0, 208.0, 104.0, 106.0)
        val fixed = StrategyLabEngine.backAdjust(raw)

        // the join is now a normal move, not a crash
        val joinReturn = fixed[3].close / fixed[2].close - 1.0
        assertTrue("join should be a small move, was ${joinReturn * 100}%", kotlin.math.abs(joinReturn) < 0.05)

        // bars AFTER the action are untouched — live prices must stay as the feed reported them
        assertEquals(104.0, fixed[3].close, 1e-9)
        assertEquals(106.0, fixed[4].close, 1e-9)

        // bars BEFORE are halved
        assertEquals(100.0, fixed[0].close, 1e-9)
        assertEquals(102.0, fixed[1].close, 1e-9)
        assertEquals(104.0, fixed[2].close, 1e-9)

        // the whole candle is scaled, not just the close
        assertEquals(fixed[0].high, 200.0 * 1.01 * 0.5, 1e-9)
        assertEquals(fixed[0].low, 200.0 * 0.99 * 0.5, 1e-9)
    }

    @Test
    fun theRaymondCaseIsRepaired() {
        val raw = bars(1500.0, 1540.0, 1566.0, 551.2, 560.0)
        val fixed = StrategyLabEngine.backAdjust(raw)
        val worst = (1 until fixed.size).minOf { fixed[it].close / fixed[it - 1].close - 1.0 }
        assertTrue("worst move should no longer look like a crash, was ${worst * 100}%", worst > -0.10)
        assertEquals("the newest bar is never rescaled", 560.0, fixed.last().close, 1e-9)
    }

    @Test
    fun aGenuineCrashIsLeftAlone() {
        // -20% is a brutal but real session; repairing it would erase a loss that happened.
        val raw = bars(100.0, 101.0, 80.8, 79.0)
        assertSame(raw, StrategyLabEngine.backAdjust(raw))
    }

    @Test
    fun multipleActionsCompound() {
        // two 1:2 splits — the oldest bars must be quartered, not halved
        val raw = bars(400.0, 404.0, 202.0, 204.0, 102.0, 103.0)
        val fixed = StrategyLabEngine.backAdjust(raw)
        assertEquals(100.0, fixed[0].close, 1e-9)
        assertEquals(101.0, fixed[1].close, 1e-9)
        assertEquals(101.0, fixed[2].close, 1e-9)
        assertEquals(103.0, fixed.last().close, 1e-9)
        println("MULTI-SPLIT: " + fixed.joinToString { "%.2f".format(it.close) })
    }
}
