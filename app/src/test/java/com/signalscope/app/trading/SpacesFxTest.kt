package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH BROKERS CONVERT CURRENCY ON EVERY TRADE.
 *
 * A currency-conversion charge is the least visible cost in the app: it is not
 * typed in anywhere per algo, it is added on top of whatever other friction is in
 * force, and it disappears into a single blended percentage on the card. For a
 * long time it was charged on Indian trades — a rupee account, funded from a
 * rupee bank, converting nothing — because the account currency it was compared
 * against is one global Trading 212 setting and the comparison never asked whose
 * broker it was.
 *
 * That is worth a test of its own precisely because nothing else would catch it:
 * the arithmetic downstream was always correct, and a wrong answer here looks
 * exactly like a right one.
 */
class SpacesFxTest {

    @Test fun `only Trading 212 converts on each trade`() {
        assertTrue("US is T212", Spaces.brokerConvertsPerTrade("us"))
        assertTrue("DE is T212", Spaces.brokerConvertsPerTrade("de"))
        assertFalse("Zerodha holds rupees", Spaces.brokerConvertsPerTrade("in"))
        assertFalse("Binance settles its own", Spaces.brokerConvertsPerTrade("binance"))
    }

    @Test fun `buckets inherit their space's broker`() {
        // Every cost lookup happens on a namespace, and a namespace is usually a
        // bucket. Resolving the bare space only would have exempted nothing that
        // anyone actually runs.
        assertTrue(Spaces.brokerConvertsPerTrade("us__growth"))
        assertFalse(Spaces.brokerConvertsPerTrade("in__b8alhc"))
        assertFalse(Spaces.brokerConvertsPerTrade("binance__btc"))
    }

    /**
     * The rule the cost resolver applies, reproduced here against known numbers.
     * Kept as arithmetic rather than as a call into the store, which needs a
     * Context: what is being pinned is the DECISION, not the plumbing.
     */
    private fun fxRoundTrip(ns: String, accountCur: String, ratePct: Double): Double =
        if (Spaces.brokerConvertsPerTrade(ns) &&
            !Spaces.currencyOf(ns).equals(accountCur, true)) 2.0 * ratePct else 0.0

    @Test fun `a euro account pays conversion on US stock and nothing on German`() {
        assertEquals(0.30, fxRoundTrip("us__x", "EUR", 0.15), 1e-9)
        assertEquals("EUR account, EUR instrument", 0.0, fxRoundTrip("de__x", "EUR", 0.15), 1e-9)
    }

    @Test fun `a euro account pays NO conversion on Indian trades`() {
        // The defect, pinned as a number. This returned 0.30 — which on the user's
        // Indian algo was three quarters of its entire modelled trading cost, and
        // was charged hundreds of times over a backtest.
        assertEquals(0.0, fxRoundTrip("in__b8alhc", "EUR", 0.15), 1e-9)
        assertEquals("nor on crypto", 0.0, fxRoundTrip("binance__btc", "EUR", 0.15), 1e-9)
    }

    @Test fun `the full Indian round trip is broker fee alone`() {
        // 150,000 over 5 seats = 30,000 a seat; a flat 16 charged on both legs.
        val seat = 150_000.0 / 5
        val brokerPct = 2.0 * 16.0 / seat * 100.0
        assertEquals(0.1067, brokerPct, 1e-4)
        val before = brokerPct + 0.30      // what the app charged
        val after = brokerPct + fxRoundTrip("in__b8alhc", "EUR", 0.15)
        assertEquals("the badge that did not reconcile", 0.4067, before, 1e-4)
        assertEquals("what it should have said", 0.1067, after, 1e-4)
    }
}
