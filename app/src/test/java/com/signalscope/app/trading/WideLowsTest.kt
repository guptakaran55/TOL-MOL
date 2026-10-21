package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ManualWatchStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE 3-DAY, 1-WEEK AND 1-MONTH LOWS: which ones a price is sitting at, by the
 * same "bottom tenth of the window's own range" rule for each.
 */
class WideLowsTest {

    private fun levels(
        s: Pair<Double, Double> = 98.0 to 102.0,
        w: Pair<Double, Double> = 95.0 to 103.0,
        m: Pair<Double, Double> = 90.0 to 110.0
    ) = ManualWatchStore.Levels(symbol = "SAP",
        swingLow = s.first, swingHigh = s.second, swingDays = 3, swingVisits = 1,
        weekLow = w.first, weekHigh = w.second, weekDays = 5, weekVisits = 2,
        monthLow = m.first, monthHigh = m.second, monthDays = 21, monthVisits = 1)

    @Test fun `only the 3-day low - the usual pullback`() {
        val hits = IntradayLevels.floorsHit(levels(), 98.2)   // bottom of 98-102, mid-week, mid-month
        assertEquals(listOf("3d"), hits.map { it.scale })
    }

    @Test fun `a week's low that is also the 3-day low`() {
        // The dip of the last 3 days IS the week's lowest point.
        val hits = IntradayLevels.floorsHit(levels(s = 95.0 to 99.0, w = 95.0 to 103.0), 95.3)
        assertEquals(listOf("3d", "1w"), hits.map { it.scale })
        assertEquals("1-week", hits.last().label)
    }

    @Test fun `all three, and below the month's low counts too`() {
        val hits = IntradayLevels.floorsHit(levels(s = 90.0 to 94.0, w = 90.0 to 100.0, m = 90.0 to 110.0), 89.5)
        assertEquals(listOf("3d", "1w", "1m"), hits.map { it.scale })
    }

    @Test fun `near the month's low without being low for the last few days`() {
        // A stock that has sat quietly near its monthly low all week: the 3-day
        // range is narrow and it is mid-range there, but low for the month.
        val hits = IntradayLevels.floorsHit(levels(s = 91.0 to 92.0, w = 91.0 to 92.0, m = 90.5 to 110.0), 91.5)
        assertEquals(listOf("1m"), hits.map { it.scale })
    }

    @Test fun `a horizon without enough history is simply left out`() {
        val lv = levels().copy(monthLow = 0.0, monthHigh = 0.0, monthDays = 0)
        val hits = IntradayLevels.floorsHit(lv, 89.0)
        assertTrue(hits.none { it.scale == "1m" })
    }

    @Test fun `no price, no alert`() {
        assertTrue(IntradayLevels.floorsHit(levels(), 0.0).isEmpty())
    }

    @Test fun `week and month are read from the same hourly bars`() {
        // 25 trading days of hourly bars, a lower low every few days back in time.
        val bars = ArrayList<CandleData>()
        val day0 = 1_790_000_000L / 86_400L
        for (d in 0 until 25) {
            val dayLow = when {
                d == 2 -> 80.0                // 23 days ago: outside the month
                d == 8 -> 85.0                // 17 days ago: the month's low
                d == 21 -> 92.0               // 4 days ago: the week's low
                else -> 95.0
            }
            for (h in 0 until 8) {
                val t = (day0 + d) * 86_400L + 8 * 3600L + h * 3600L
                val low = if (h == 3) dayLow else 96.0 + (h % 3)
                bars.add(CandleData(t, 97.0, 100.0 + (h % 2), low, 98.0 + (h % 2), 1000))
            }
        }
        val lv = IntradayLevels.from("SAP", bars, 60)
        assertNotNull(lv); lv!!
        assertEquals(92.0, lv.weekLow, 1e-9)
        assertEquals(5, lv.weekDays)
        assertEquals(85.0, lv.monthLow, 1e-9)
        assertEquals(21, lv.monthDays)
    }
}
