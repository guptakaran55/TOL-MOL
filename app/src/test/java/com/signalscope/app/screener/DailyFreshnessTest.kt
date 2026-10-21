package com.signalscope.app.screener

import com.signalscope.app.data.CandleData
import com.signalscope.app.network.LatestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DAILY PRICES THAT ARE A DAY BEHIND.
 *
 * 2 Oct 2026, 02:06 Berlin: Yahoo's daily series for Symrise ended on Wednesday
 * although Thursday had closed hours before, and every daily decision of the day
 * (gates, ranking, 1D dots) was taken on Wednesday's close.
 */
class DailyFreshnessTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val ny = ZoneId.of("America/New_York")
    private val xetraClose = LocalTime.of(17, 30)
    private val nyClose = LocalTime.of(16, 0)
    private fun at(z: ZoneId, d: String, hh: Int, mm: Int = 0) = ZonedDateTime.of(LocalDate.parse(d), LocalTime.of(hh, mm), z)
    private fun d(s: String) = LocalDate.parse(s)

    // ── is the series current? ──────────────────────────────────────────────

    @Test fun `Friday 02-06 in Germany needs Thursday's bar`() {
        val now = at(berlin, "2026-10-02", 2, 6)
        assertFalse(ScreenerRunner.dailyDataCurrentAt(d("2026-09-30"), now, xetraClose))
        assertTrue(ScreenerRunner.dailyDataCurrentAt(d("2026-10-01"), now, xetraClose))
    }

    @Test fun `the US gate runs after that day's close, so it needs that same day`() {
        // 02:06 Berlin = 20:06 New York on Thursday: Thursday's session is over.
        val now = at(ny, "2026-10-01", 20, 6)
        assertFalse(ScreenerRunner.dailyDataCurrentAt(d("2026-09-30"), now, nyClose))
        assertTrue(ScreenerRunner.dailyDataCurrentAt(d("2026-10-01"), now, nyClose))
    }

    @Test fun `Monday morning needs Friday, not Sunday`() {
        assertTrue(ScreenerRunner.dailyDataCurrentAt(d("2026-10-02"), at(berlin, "2026-10-05", 6), xetraClose))
        assertEquals(d("2026-10-02"), ScreenerRunner.lastClosedSession(at(berlin, "2026-10-04", 12), xetraClose))
    }

    @Test fun `a holiday's missing bar is accepted twelve hours after that close`() {
        // Suppose Thursday had no session: still "missing" at 02:00 Friday,
        // accepted from 05:30 — before the next open, so the day still runs.
        assertFalse(ScreenerRunner.dailyDataCurrentAt(d("2026-09-30"), at(berlin, "2026-10-02", 5, 29), xetraClose))
        assertTrue(ScreenerRunner.dailyDataCurrentAt(d("2026-09-30"), at(berlin, "2026-10-02", 5, 31), xetraClose))
    }

    @Test fun `no data is never current`() {
        assertFalse(ScreenerRunner.dailyDataCurrentAt(null, at(berlin, "2026-10-02", 12), xetraClose))
    }

    // ── filling the missing day from the reply's own last trade ────────────

    private fun bar(day: String, close: Double) =
        CandleData(LocalDate.parse(day).atTime(9, 0).atZone(berlin).toEpochSecond(), close, close, close, close, 100)
    private val series = listOf(bar("2026-09-29", 93.50), bar("2026-09-30", 91.76))
    private val thuClose = LocalDate.parse("2026-10-01").atTime(17, 35).atZone(berlin).toEpochSecond()

    @Test fun `the missing session is added from the last trade`() {
        val out = LatestSession.fill(series, "1d", thuClose, 90.38, 92.0, 90.1, 5000, berlin)
        assertEquals(3, out.size)
        val add = out.last()
        assertEquals(90.38, add.close, 1e-9)
        assertEquals(92.0, add.high, 1e-9)
        assertEquals(90.1, add.low, 1e-9)
        assertEquals(5000L, add.volume)
        // Stamped like its neighbours: 09:00 Berlin on Thursday.
        assertEquals(LocalDate.parse("2026-10-01").atTime(9, 0).atZone(berlin).toEpochSecond(), add.timestamp)
    }

    @Test fun `nothing is added when the series already has that day`() {
        val wed = LocalDate.parse("2026-09-30").atTime(17, 35).atZone(berlin).toEpochSecond()
        assertEquals(series, LatestSession.fill(series, "1d", wed, 91.76, 92.0, 91.0, 1, berlin))
    }

    @Test fun `intraday and monthly series are never touched`() {
        assertEquals(series, LatestSession.fill(series, "15m", thuClose, 90.38, 92.0, 90.1, 1, berlin))
        assertEquals(series, LatestSession.fill(series, "1mo", thuClose, 90.38, 92.0, 90.1, 1, berlin))
    }

    @Test fun `no usable price, nothing added`() {
        assertEquals(series, LatestSession.fill(series, "1d", thuClose, Double.NaN, 92.0, 90.1, 1, berlin))
        assertEquals(series, LatestSession.fill(series, "1d", 0L, 90.38, 92.0, 90.1, 1, berlin))
    }
}
