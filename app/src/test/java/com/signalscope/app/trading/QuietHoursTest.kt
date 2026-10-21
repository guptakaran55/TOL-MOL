package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * NOTHING ABOUT A SHUT STOCK MARKET AT NIGHT.
 *
 *  • A paper trade found hours after its bar is "late", and says which bar.
 *  • "Once a day" counts the market's own day, not the world clock's (which
 *    started each day at 2 a.m. in Germany).
 *  • With every stock market shut, the status line names the next to open.
 *
 * Week used: Fri 16 – Mon 19 Oct 2026, German summer time.
 */
class QuietHoursTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun de(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, berlin)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun sec(z: ZonedDateTime) = z.toEpochSecond()

    // Friday's India 2-hour bar, 14:00–16:00 India time = 10:30–12:30 German time.
    private val coforgeBar = sec(de(16, 10, 30))
    private val twoHours = 2 * 3600L

    @Test fun `a decision minutes after the bar ends is not late`() {
        assertFalse(LatePaperAlerts.isLate(coforgeBar, twoHours, sec(de(16, 12, 46))))
    }

    @Test fun `the same bar judged at one in the morning is late`() {
        assertTrue(LatePaperAlerts.isLate(coforgeBar, twoHours, sec(de(17, 1, 1))))
    }

    @Test fun `a US bar judged half an hour after the close is not late`() {
        // 20:00–22:00 German time, decided 22:31 as the US algos usually are.
        assertFalse(LatePaperAlerts.isLate(sec(de(16, 20, 0)), twoHours, sec(de(16, 22, 31))))
    }

    @Test fun `India's last bar of Friday reads as Friday's close`() {
        assertEquals("Friday's close",
            LatePaperAlerts.describe("in", coforgeBar, twoHours, ms(de(19, 5, 46)), berlin))
    }

    @Test fun `a bar inside the session reads as a day and time`() {
        // Germany 10:00–12:00, found the next day.
        assertEquals("yesterday at 12:00",
            LatePaperAlerts.describe("de", sec(de(15, 10, 0)), twoHours, ms(de(16, 9, 16)), berlin))
    }

    @Test fun `today and yesterday are named as such`() {
        assertEquals("yesterday's close",
            LatePaperAlerts.describe("in", coforgeBar, twoHours, ms(de(17, 1, 1)), berlin))
        assertEquals("today's close",
            LatePaperAlerts.describe("de", sec(de(16, 16, 0)), twoHours, ms(de(16, 23, 0)), berlin))
    }

    @Test fun `the market's day does not roll over at 2 a m German time`() {
        // 02:15 on Saturday is still Friday in New York.
        assertEquals("2026-10-16", MarketHours.tradingDay("us", ms(de(17, 2, 15))))
        assertEquals("2026-10-17", MarketHours.tradingDay("de", ms(de(17, 2, 15))))
    }

    @Test fun `next open skips the weekend`() {
        assertEquals(ms(de(19, 9, 0)), MarketHours.nextOpenMs("de", ms(de(17, 10, 37))))
        assertEquals(ms(de(19, 5, 45)), MarketHours.nextOpenMs("in", ms(de(17, 10, 37))))
        assertEquals(ms(de(19, 15, 30)), MarketHours.nextOpenMs("us", ms(de(17, 10, 37))))
    }

    @Test fun `next open on a weekday morning is the same day`() {
        assertEquals(ms(de(16, 9, 0)), MarketHours.nextOpenMs("de", ms(de(16, 7, 0))))
    }

    @Test fun `the status line names India's Monday open on a Saturday`() {
        assertEquals("India opens Mon 05:45", MarketHours.nextOpeningText(ms(de(17, 10, 37)), berlin))
    }

    @Test fun `the status line says tomorrow late on a weekday evening`() {
        assertEquals("India opens tomorrow 05:45", MarketHours.nextOpeningText(ms(de(15, 23, 0)), berlin))
    }

    @Test fun `in the small hours it is the same day`() {
        assertEquals("India opens at 05:45", MarketHours.nextOpeningText(ms(de(16, 2, 15)), berlin))
    }

    @Test fun `while any stock market trades there is no shut line`() {
        assertNull(MarketHours.nextOpeningText(ms(de(16, 10, 0)), berlin))     // Germany open
        assertNull(MarketHours.nextOpeningText(ms(de(16, 21, 0)), berlin))     // US open
        assertNull(MarketHours.nextOpeningText(ms(de(16, 7, 0)), berlin))      // India open
    }

    @Test fun `crypto has no hours`() {
        assertNull(MarketHours.nextOpenMs("binance", ms(de(17, 10, 0))))
        assertNull(MarketHours.tradingDay("binance", ms(de(17, 10, 0))))
    }
}
