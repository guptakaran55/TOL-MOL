package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRADING 212'S ONLY FEE on a closed trade: 0.15% conversion on each leg when
 * the shares are not in the account's currency — US shares on a euro account —
 * and nothing at all for euro shares on a euro account.
 */
class T212FeesTest {

    private fun trip(ticker: String) = ClosedTrades.match(listOf(
        ClosedTrades.Fill(ticker, ticker, 2.0, 100.0, 180.0, "BUY", "2026-10-01T15:00:00Z"),
        ClosedTrades.Fill(ticker, ticker, 2.0, 110.0, 198.0, "SELL", "2026-10-06T15:00:00Z"))).second

    @Test fun `US shares on a euro account pay the conversion fee both ways`() {
        val rep = ClosedTrades.costed(trip("PLTR_US_EQ"), "us", "EUR")
        val t = rep.trips.single()
        assertTrue(rep.costed)
        assertEquals(0.0015 * (180.0 + 198.0), t.charges, 1e-9)
        assertEquals(0.0015 * (180.0 + 198.0), t.chargeParts!!["FX conversion"]!!, 1e-9)
        assertFalse("no same-day card outside India", t.chargeParts!!.containsKey("brokerage"))
    }

    @Test fun `German shares on a euro account pay nothing`() {
        val rep = ClosedTrades.costed(trip("SAPd_EQ"), "de", "EUR")
        assertFalse(rep.costed)
        assertEquals(0.0, rep.trips.single().charges, 0.0)
    }
}
