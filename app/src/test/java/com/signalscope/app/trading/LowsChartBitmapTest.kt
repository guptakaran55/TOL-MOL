package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The near-low alert's three price windows, cut from the watchlist's hourly bars. */
class LowsChartBitmapTest {

    private val day0 = 1_790_000_000L / 86_400L

    /** [days] trading days of 7 hourly bars; day d closes at 100 + d, its low 1 under. */
    private fun bars(days: Int): List<CandleData> = (0 until days).flatMap { d ->
        (0 until 7).map { h ->
            val t = (day0 + d) * 86_400L + 9 * 3600L + h * 3600L
            val px = 100.0 + d
            CandleData(t, px, px + 0.5, px - 1.0, px, 1000)
        }
    }

    @Test fun `a year of history gives 1Y, 1M and 5D, the year as daily closes`() {
        val p = LowsChartBitmap.panels(bars(300), now = 399.0)
        assertEquals(listOf("1Y", "1M", "5D"), p.map { it.label })
        assertEquals(252, p[0].closes.size)               // one point per day
        assertEquals(21 * 7, p[1].closes.size)            // the month's bars themselves
        assertEquals(5 * 7, p[2].closes.size)
        // Each window's low is that window's own lowest low.
        assertEquals(100.0 + 300 - 252 - 1.0, p[0].low, 1e-9)
        assertEquals(100.0 + 300 - 5 - 1.0, p[2].low, 1e-9)
        assertTrue(p.all { it.now == 399.0 })
    }

    @Test fun `history short of a year is labelled with the days it covers`() {
        val p = LowsChartBitmap.panels(bars(60), now = 159.0)
        assertEquals("60d", p[0].label)
    }

    @Test fun `no price or too few bars - nothing to draw`() {
        assertTrue(LowsChartBitmap.panels(bars(1), now = 100.0).isEmpty())
        assertTrue(LowsChartBitmap.panels(bars(60), now = 0.0).isEmpty())
    }
}
