package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * FLOOR ORDERS READ THE MONTHLY TREND (24 Oct 2026) — as the replay they came
 * from was tested — so the monthly closes must be finished months only.
 */
class FloorMonthTrendTest {
    private fun month(y: Int, m: Int, close: Double, zone: ZoneId = ZoneOffset.UTC) =
        CandleData(LocalDate.of(y, m, 1).atStartOfDay(zone).toEpochSecond(), close, close, close, close, 1L)

    @Test fun `the month today falls in is left out`() {
        val bars = listOf(month(2026, 8, 10.0), month(2026, 9, 11.0), month(2026, 10, 12.0))
        assertArrayEquals(doubleArrayOf(10.0, 11.0), FloorOrders.completedMonths(bars, LocalDate.of(2026, 10, 24)), 1e-12)
    }

    @Test fun `a bar stamped in the exchange's time zone stays in its own month`() {
        // Berlin midnight on 1 Sep is 31 Aug 22:00 UTC.
        val bars = listOf(month(2026, 8, 10.0, ZoneId.of("Europe/Berlin")), month(2026, 9, 11.0, ZoneId.of("Europe/Berlin")))
        assertArrayEquals(doubleArrayOf(10.0, 11.0), FloorOrders.completedMonths(bars, LocalDate.of(2026, 10, 2)), 1e-12)
    }

    @Test fun `empty or unusable closes are skipped`() {
        val bars = listOf(month(2026, 7, 0.0), month(2026, 8, Double.NaN), month(2026, 9, 9.0))
        assertArrayEquals(doubleArrayOf(9.0), FloorOrders.completedMonths(bars, LocalDate.of(2026, 10, 24)), 1e-12)
    }
}
