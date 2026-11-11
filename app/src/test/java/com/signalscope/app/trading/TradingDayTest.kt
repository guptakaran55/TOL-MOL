package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

/** HAS THIS EXCHANGE TRADED TODAY? — NSE's Diwali holiday, 10 Nov 2026. */
class TradingDayTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ist(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, ist).toInstant().toEpochMilli()

    @Test fun `a trade on the exchange's date is today's, one from the last session is not`() {
        val now = ist(10, 9, 19)                                     // 04:49 in Germany
        assertFalse(TradingDay.isToday(ist(9, 15, 29), "in", now))   // Monday's close
        assertTrue(TradingDay.isToday(ist(10, 9, 15), "in", now))
        assertFalse(TradingDay.isToday(0L, "in", now))
    }

    @Test fun `a holiday leaves every quote on the last session`() {
        val now = ist(10, 9, 19)
        val monday = List(40) { ist(9, 15, 29) }
        assertEquals(false, TradingDay.tradedFromQuotes(monday, "in", now))
        // a normal morning: most have traded, a few thin names not yet
        val normal = List(34) { ist(10, 9, 18) } + List(6) { ist(9, 15, 29) }
        assertEquals(true, TradingDay.tradedFromQuotes(normal, "in", now))
        assertNull(TradingDay.tradedFromQuotes(listOf(ist(9, 15, 29)), "in", now))   // too few to say
    }

    @Test fun `the quote carries when it last traded`() {
        val q = YahooFinanceClient.parseQuotes("""{"quoteResponse":{"result":[{"symbol":"QUESS.NS",
            "regularMarketPrice":700.5,"regularMarketChangePercent":8.52,"regularMarketVolume":1000,
            "averageDailyVolume10Day":100,"regularMarketTime":1794215700}]}}""")
        assertEquals(1794215700_000L, q["QUESS.NS"]!!.tradedAtMs)
    }
}
