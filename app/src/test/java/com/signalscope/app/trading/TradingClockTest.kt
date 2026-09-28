package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A QUEUED BUY'S LIFE IS COUNTED IN WEEKDAY TIME.
 *
 * The last German bar of the day is judged after Xetra has shut, so a signal on
 * it waits for the next open. On a Friday that open is Monday — and a flat 48
 * hours expired the order on Sunday night, before it had a single chance.
 */
class TradingClockTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: ZoneId = berlin) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    // 2 October 2026 is a Friday.
    private val fridayEvening = at(2026, 10, 2, 18, 16)

    @Test fun `a Friday evening signal is still alive at Monday's open`() {
        val mondayOpen = at(2026, 10, 5, 9, 16)
        // On the wall clock this is 63 hours — the old rule's verdict: expired.
        assertTrue(mondayOpen - fridayEvening > 48 * hour)
        assertFalse(TradingClock.queueExpired(fridayEvening, mondayOpen, "de__buyx6g"))
        // Only Friday's last hours and Monday's first ones count.
        assertEquals((5 * hour + 44 * 60_000L) + (9 * hour + 16 * 60_000L),
            TradingClock.weekdayMillisBetween(fridayEvening, mondayOpen, berlin))
    }

    @Test fun `it still expires after two sessions' worth of weekday time`() {
        // 5h44 on Friday + 24h Monday + 18h16 Tuesday = exactly 48h. One minute on
        // and it is gone — two sessions later, not never.
        val tuesdayEvening = at(2026, 10, 6, 18, 16)
        assertEquals(48 * hour, TradingClock.weekdayMillisBetween(fridayEvening, tuesdayEvening, berlin))
        assertFalse(TradingClock.queueExpired(fridayEvening, tuesdayEvening, "de"))
        assertTrue(TradingClock.queueExpired(fridayEvening, tuesdayEvening + 60_000L, "de"))
    }

    @Test fun `a midweek queue is unchanged — weekday time is wall time`() {
        val wed = at(2026, 9, 30, 18, 16)
        val fri = at(2026, 10, 2, 18, 16)
        assertEquals(fri - wed, TradingClock.weekdayMillisBetween(wed, fri, berlin))
    }

    @Test fun `a weekend is judged in the exchange's own time zone`() {
        // 23:30 Friday in New York is already Saturday in Frankfurt. The half hour
        // to midnight is weekday time for a US order and weekend time for a German
        // one.
        val ny = ZoneId.of("America/New_York")
        val start = at(2026, 10, 2, 23, 30, ny)
        val end = at(2026, 10, 3, 0, 0, ny)
        assertEquals(30 * 60_000L, TradingClock.weekdayMillisBetween(start, end, ny))
        assertEquals(0L, TradingClock.weekdayMillisBetween(start, end, berlin))
    }

    @Test fun `the clock-change weekend is counted as it really was`() {
        // Europe leaves summer time on Sunday 25 October 2026. Friday 12:00 to
        // Monday 12:00 is 12h + 12h of weekday time either way; the 25-hour Sunday
        // sits in the part that does not count.
        val fri = at(2026, 10, 23, 12, 0)
        val mon = at(2026, 10, 26, 12, 0)
        assertEquals(24 * hour, TradingClock.weekdayMillisBetween(fri, mon, berlin))
    }

    @Test fun `empty and backwards spans are zero`() {
        assertEquals(0L, TradingClock.weekdayMillisBetween(fridayEvening, fridayEvening, berlin))
        assertEquals(0L, TradingClock.weekdayMillisBetween(fridayEvening, fridayEvening - hour, berlin))
    }

    @Test fun `each market uses its own exchange's zone`() {
        assertEquals(ZoneId.of("Europe/Berlin"), TradingClock.zoneFor("de__buyx6g"))
        assertEquals(ZoneId.of("America/New_York"), TradingClock.zoneFor("us"))
        assertEquals(ZoneId.of("Asia/Kolkata"), TradingClock.zoneFor("in__manual"))
    }
}
