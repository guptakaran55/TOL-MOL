package com.signalscope.app.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * IS THE MARKET TRADING NOW? For the watchlist's floor alert and the signal
 * tracker, which used to read an open afternoon as "shut" most of the time.
 */
class MarketHoursTest {

    private fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    // 30 September 2026 is a Wednesday.

    @Test fun `Xetra is open all afternoon, not only just after a bar closes`() {
        for (h in listOf(9, 11, 13, 15, 16)) {
            assertTrue("$h:45", MarketHours.isRegularSession("de", at("Europe/Berlin", 2026, 9, 30, h, 45)))
        }
        assertFalse(MarketHours.isRegularSession("de", at("Europe/Berlin", 2026, 9, 30, 8, 59)))
        assertFalse(MarketHours.isRegularSession("de", at("Europe/Berlin", 2026, 9, 30, 17, 30)))
    }

    @Test fun `New York hours, judged on New York's clock`() {
        assertTrue(MarketHours.isRegularSession("us", at("America/New_York", 2026, 9, 30, 9, 30)))
        assertFalse(MarketHours.isRegularSession("us", at("America/New_York", 2026, 9, 30, 16, 0)))
        // 21:00 in Germany is 15:00 in New York — open.
        assertTrue(MarketHours.isRegularSession("us", at("Europe/Berlin", 2026, 9, 30, 21, 0)))
    }

    @Test fun `India, and nobody on a weekend`() {
        assertTrue(MarketHours.isRegularSession("in", at("Asia/Kolkata", 2026, 9, 30, 10, 0)))
        assertFalse(MarketHours.isRegularSession("in", at("Asia/Kolkata", 2026, 10, 3, 10, 0)))
        assertFalse(MarketHours.isRegularSession("de", at("Europe/Berlin", 2026, 10, 4, 12, 0)))
        assertFalse("no crypto session here", MarketHours.isRegularSession("binance", at("Europe/Berlin", 2026, 9, 30, 12, 0)))
    }

    @Test fun `a German hourly bar is current through the whole next hour and the feed delay`() {
        val start = at("Europe/Berlin", 2026, 9, 30, 15, 0) / 1000     // 15:00–16:00 bar
        val delay = FeedDelay.XETRA_SEC
        assertTrue(MarketHours.barIsCurrent(start, 3600, at("Europe/Berlin", 2026, 9, 30, 16, 46) / 1000, delay))
        assertTrue(MarketHours.barIsCurrent(start, 3600, at("Europe/Berlin", 2026, 9, 30, 17, 30) / 1000, delay))
    }

    @Test fun `yesterday's bar is not current - which is how a holiday is caught`() {
        val start = at("Europe/Berlin", 2026, 9, 29, 16, 0) / 1000
        assertFalse(MarketHours.barIsCurrent(start, 3600, at("Europe/Berlin", 2026, 9, 30, 11, 0) / 1000, FeedDelay.XETRA_SEC))
    }
}
