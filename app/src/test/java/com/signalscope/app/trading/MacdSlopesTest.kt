package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * THE FIVE SLOPE DOTS ON A WATCHER ALERT — month, day, 4 hours, hour, 15 minutes.
 */
class MacdSlopesTest {

    private fun c(ts: Long, close: Double) = CandleData(ts, close, close, close, close, 100L)
    private val t0 = LocalDateTime.of(2026, 9, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)

    /** A steady climb, then a turn down for the last [down] steps. */
    private fun path(n: Int, down: Int) = List(n) { i ->
        if (i < n - down) 100.0 + i else 100.0 + (n - down) - 3.0 * (i - (n - down) + 1)
    }

    @Test fun `rising prices give a rising slope, a turn down gives a falling one`() {
        assertEquals(1, MacdSlopes.sign(MacdSlopes.latest(path(80, 0).toDoubleArray(), 5)))
        assertEquals(-1, MacdSlopes.sign(MacdSlopes.latest(path(80, 12).toDoubleArray(), 5)))
    }

    @Test fun `too little history is no verdict, not a guess`() {
        assertNull(MacdSlopes.latest(DoubleArray(29) { 100.0 + it }, 5))
        assertNull(MacdSlopes.sign(null))
        assertEquals("unknown is grey", 0xFF94A3B8.toInt(), MacdSlopes.color(null))
    }

    @Test fun `the stored daily curve gives the same answer as the closes behind it`() {
        // The 1D dot falls back to the stored MACD curve when the watcher has no
        // ranking row for the stock; the sign must match the closes' own slope.
        val closes = path(120, 10).toDoubleArray()
        val a = 2.0 / 13; val b = 2.0 / 27
        var e12 = closes[0]; var e26 = closes[0]
        val macd = closes.map { x -> e12 = a * x + (1 - a) * e12; e26 = b * x + (1 - b) * e26; e12 - e26 }
        assertEquals(MacdSlopes.sign(MacdSlopes.latest(closes, 5)),
            MacdSlopes.sign(MacdSlopes.latestFromMacd(macd.takeLast(30), 5)))
    }

    @Test fun `hours are built from quarter hours and only whole bars count`() {
        // Six quarter-hours: one complete hour, and an hour half built.
        val q = List(6) { c(t0 + it * 900L, 100.0 + it) }
        val closes = MacdSlopes.resample(q, 3600L, t0 + 6 * 900L, 0L)
        assertEquals("only the finished hour", 1, closes.size)
        assertEquals("its close is its last quarter hour", 103.0, closes[0], 1e-9)
    }

    @Test fun `a German hour waits for the feed's fifteen minutes`() {
        val q = List(4) { c(t0 + it * 900L, 100.0 + it) }
        assertEquals(0, MacdSlopes.resample(q, 3600L, t0 + 3600L, FeedDelay.XETRA_SEC).size)
        assertEquals(1, MacdSlopes.resample(q, 3600L, t0 + 3600L + 900L, FeedDelay.XETRA_SEC).size)
    }

    @Test fun `the month in progress is left out`() {
        val months = listOf(
            c(LocalDateTime.of(2026, 7, 1, 0, 0).toEpochSecond(ZoneOffset.UTC), 10.0),
            c(LocalDateTime.of(2026, 8, 1, 0, 0).toEpochSecond(ZoneOffset.UTC), 11.0),
            c(LocalDateTime.of(2026, 9, 1, 0, 0).toEpochSecond(ZoneOffset.UTC), 12.0))
        val now = LocalDateTime.of(2026, 9, 30, 12, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(listOf(10.0, 11.0), MacdSlopes.completedMonths(months, now).toList())
    }

    @Test fun `five dots, longest first, each on its own timescale`() {
        // Sixty days of quarter hours rising steadily, then the last few falling
        // hard: the short dots turn red before the long ones do.
        val n = 60 * 96
        val q = List(n) { i -> c(t0 + i * 900L, if (i < n - 12) 100.0 + i * 0.01 else 100.0 + (n - 12) * 0.01 - (i - (n - 13)) * 0.5) }
        val months = List(60) { i -> c(t0 - (60 - i) * 31L * 86400, 50.0 + i) }
        val now = t0 + n * 900L + 60
        val r = MacdSlopes.all(q, months, 0.8, now, 0L, 5)
        assertEquals(listOf("1M", "1D", "4H", "1H", "15m"), r.map { it.label })
        assertEquals("month still rising", 1, r[0].sign)
        assertEquals("the watcher's own daily figure", 1, r[1].sign)
        assertEquals("fifteen minutes has turned", -1, r[4].sign)
        assertTrue("an hour has turned too", r[3].sign == -1)
    }

    @Test fun `missing inputs leave their dots unknown`() {
        val r = MacdSlopes.all(null, null, null, t0, 0L, 5)
        assertTrue(r.all { it.sign == null })
    }
}
