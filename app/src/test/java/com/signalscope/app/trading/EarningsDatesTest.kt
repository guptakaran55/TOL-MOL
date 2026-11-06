package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EARNINGS DATES (6 Nov 2026): reading Yahoo's calendar, and how long an answer is kept. */
class EarningsDatesTest {
    private val day = 86_400_000L
    private val now = 1_794_000_000_000L

    @Test fun `the earliest coming results day is taken`() {
        val a = now / 1000 + 5 * 86_400; val b = now / 1000 + 6 * 86_400; val gone = now / 1000 - 40 * 86_400
        val body = """{"quoteSummary":{"result":[{"calendarEvents":{"earnings":{"earningsDate":[
            {"raw":$gone,"fmt":"x"},{"raw":$b,"fmt":"x"},{"raw":$a,"fmt":"x"}],"isEarningsDateEstimate":true}}}]}}"""
        val r = YahooFinanceClient.parseEarningsDate(body, now)
        assertTrue(r.ok); assertEquals(a * 1000, r.atMs); assertTrue(r.estimate)
    }

    @Test fun `no date known is an answer, not a failure`() {
        val r = YahooFinanceClient.parseEarningsDate("""{"quoteSummary":{"result":[{"calendarEvents":{}}]}}""", now)
        assertTrue(r.ok); assertEquals(0L, r.atMs)
    }

    @Test fun `answers are kept a day, twice a day in results week`() {
        val E = EarningsDates
        assertTrue(E.fresh(EarningsDates.Entry(now + 20 * day, false, now - 20 * 3_600_000L), now))
        assertFalse(E.fresh(EarningsDates.Entry(now + 20 * day, false, now - 25 * 3_600_000L), now))
        assertFalse(E.fresh(EarningsDates.Entry(now + 3 * day, false, now - 13 * 3_600_000L), now))
        assertTrue(E.fresh(EarningsDates.Entry(0L, false, now - 2 * day), now))                 // none known: 3 days
        assertFalse(E.fresh(EarningsDates.Entry(now - 2 * day, false, now - 3_600_000L), now))   // passed: ask for the next
        assertFalse(E.fresh(null, now))
    }

    @Test fun `keys become Yahoo symbols`() {
        assertEquals("IFX.DE", EarningsDates.yahooOf("DE:IFX"))
        assertEquals("TCS.NS", EarningsDates.yahooOf("IN:TCS"))
        assertEquals("NVDA", EarningsDates.yahooOf("US:NVDA"))
    }
}
