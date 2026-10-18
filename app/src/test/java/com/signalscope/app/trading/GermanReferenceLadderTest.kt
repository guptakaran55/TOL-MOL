package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHICH PRICE MAY VOUCH FOR A LIVE GERMAN VENUE QUOTE.
 *
 * The check exists to catch an ISIN that resolved to the wrong instrument. In
 * October 2026 it caught the right one instead: Tradegate said 59.6 for
 * Pfisterer, Yahoo's frozen line said 66.60, and the live price was thrown away
 * for a week. These pin the ladder that replaced the single anchor.
 */
class GermanReferenceLadderTest {

    @Test fun `a current feed price that agrees settles it, as before`() {
        assertEquals(GermanRealtime.Vouch.FEED, GermanRealtime.vouch(59.6, 59.75, false, 0.0))
        assertEquals(GermanRealtime.Vouch.FEED, GermanRealtime.vouch(108.0, 100.0, false, 0.0))
    }

    @Test fun `a current feed price that disagrees still refuses, with nothing else to ask`() {
        assertNull(GermanRealtime.vouch(59.6, 66.6, false, 0.0))
    }

    @Test fun `the broker's own valuation vouches when the feed disagrees`() {
        // Trading 212 values the very instrument the ISIN came from.
        assertEquals(GermanRealtime.Vouch.BROKER, GermanRealtime.vouch(59.6, 66.6, false, 59.72))
        assertEquals(GermanRealtime.Vouch.BROKER, GermanRealtime.vouch(59.6, 66.6, true, 59.72))
    }

    @Test fun `Pfisterer's week-frozen price no longer vetoes the venue`() {
        // 59.6 is 10.5% under 66.60: refused against a current price, accepted
        // against one known to have stopped.
        assertNull(GermanRealtime.vouch(59.6, 66.6, false, 0.0))
        assertEquals(GermanRealtime.Vouch.STALE_FEED, GermanRealtime.vouch(59.6, 66.6, true, 0.0))
    }

    @Test fun `another company's price is still refused against a stopped feed`() {
        // The SMA Solar test case: a bad ISIN gave 1.82 against a true 61.75.
        assertNull(GermanRealtime.vouch(1.82, 61.75, true, 0.0))
        assertNull(GermanRealtime.vouch(40.0, 61.75, true, 0.0))
    }

    @Test fun `a broker price that disagrees does not vouch`() {
        assertNull(GermanRealtime.vouch(1.82, 61.75, false, 61.80))
    }

    @Test fun `no reference at all, no venue price`() {
        assertNull(GermanRealtime.vouch(59.6, 0.0, false, 0.0))
        assertNull(GermanRealtime.vouch(59.6, -1.0, true, 0.0))
        assertNull(GermanRealtime.vouch(0.0, 59.6, false, 59.6))
    }
}
