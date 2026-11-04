package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The real stop is judged on the strategy's 2-hour close, not the hourly feed bar. */
class RealStopClockTest {
    private val cfg = StrategyLabConfig(resample = "240min", exitBars = "120min")
    private val day = 1_793_000_000L / 86_400L * 86_400L          // a UTC midnight
    private fun bar(hourUtc: Int, close: Double) =
        CandleData(day + hourUtc * 3600L, close, close, close, close, 0)

    @Test fun anHourlyDipInsideAnUnfinishedTwoHourBarDoesNotCount() {
        // 08:00–10:00 finished at 100; the 10:00 hour dipped to 90, but its
        // 2-hour bar (10:00–12:00) has not finished.
        val feed = listOf(bar(8, 101.0), bar(9, 100.0), bar(10, 90.0))
        assertEquals(100.0, StrategyLabRunner.exitBarClose(feed, cfg, 3600L)!!, 1e-9)
    }

    @Test fun theFinishedBarsCloseIsItsLastHour() {
        // The 10:00–12:00 bar finishes with the 11:00 hour, back at 97.
        val feed = listOf(bar(8, 101.0), bar(9, 100.0), bar(10, 90.0), bar(11, 97.0))
        assertEquals(97.0, StrategyLabRunner.exitBarClose(feed, cfg, 3600L)!!, 1e-9)
    }

    @Test fun nothingBeforeTheFirstBarFinishes() {
        assertNull(StrategyLabRunner.exitBarClose(listOf(bar(8, 100.0)), cfg, 3600L))
    }
}
