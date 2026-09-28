package com.signalscope.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHICH LISTING A BROKER CODE MEANS.
 *
 * This decides where a holding's price comes from when the symbol has to be
 * resolved by ISIN, and an ISIN is listed in several places at once. Pick the
 * wrong line and the position is repriced in the wrong currency with nothing on
 * screen to say so — no error, no blank, just different numbers.
 *
 * The case tests are the point. A European venue tag is a lowercase letter on
 * the base, so uppercasing the ticker first — the obvious, tidy thing to do —
 * makes every German instrument read as venue-unknown.
 */
class FeedVenueTest {

    @Test fun `the lowercase Xetra tag is recognised`() {
        assertEquals(".DE", Trading212Client.wantedFeedSuffix("MUV2d_EQ"))
        assertEquals(".DE", Trading212Client.wantedFeedSuffix("SRT3d_EQ"))
        // The name that started all this: broker code CECG, market ticker CEC.
        assertEquals(".DE", Trading212Client.wantedFeedSuffix("CECGd_EQ"))
    }

    @Test fun `an uppercased German ticker is not silently treated as venue-unknown`() {
        // Upstream must not uppercase before asking. If this ever returns ".DE"
        // the guard has been loosened and a wrong-venue match becomes possible.
        assertNull(Trading212Client.wantedFeedSuffix("CECGD_EQ"))
    }

    @Test fun `US instruments want no suffix at all`() {
        assertEquals("", Trading212Client.wantedFeedSuffix("AAPL_US_EQ"))
        assertEquals("", Trading212Client.wantedFeedSuffix("FB_US_EQ"))
    }

    @Test fun `the long German form is recognised too`() {
        assertEquals(".DE", Trading212Client.wantedFeedSuffix("SAP_DE_EQ"))
    }

    @Test fun `an unknown venue refuses rather than guessing`() {
        // A London line priced as though it were German is worse than no price.
        assertNull(Trading212Client.wantedFeedSuffix("VODl_EQ"))
        assertNull(Trading212Client.wantedFeedSuffix("SOMETHING"))
        assertNull(Trading212Client.wantedFeedSuffix(""))
        assertNull(Trading212Client.wantedFeedSuffix("   "))
    }
}
