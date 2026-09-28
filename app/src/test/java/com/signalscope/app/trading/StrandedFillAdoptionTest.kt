package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REOPENING A POSITION THE LADDER BOUGHT AND RECORDED AS NOTHING.
 *
 * A queued entry is written off when the portfolio read at the end of the ladder
 * shows no change — and a fill that has not yet surfaced in that read looks
 * exactly the same as no fill. The account then holds stock with no record, no
 * stop and nothing watching it. That happened here: six orders for a €200 entry,
 * the row written off as unfilled, €123 of the stock still in the portfolio a
 * week later owned by nobody.
 *
 * The danger in fixing it is the opposite mistake. The same account holds
 * positions the user bought themselves, and claiming one of those would put an
 * automatic stop under stock the app was never asked to manage. So what is
 * pinned here is mostly the four guards that stop it reaching too far.
 */
class StrandedFillAdoptionTest {

    /** Is this row even a candidate? */
    private fun candidate(status: String, qty: Double, amount: Double,
                          ageMs: Long, windowMs: Long = 7 * 24 * 60 * 60_000L) =
        status == "ERROR" && qty <= 0.0 && amount > 0.0 && ageMs < windowMs

    /** How much of a broker holding this row may take. */
    private fun adoptable(brokerQty: Double, claimedByOthers: Double,
                          orderedValue: Double, price: Double): Double {
        val free = brokerQty - claimedByOthers
        if (free <= 0.0 || price <= 0.0) return 0.0
        val cap = (orderedValue * 1.05) / price
        return if (free > cap) 0.0 else free
    }

    // ── the case it exists for ──────────────────────────────────────────────

    @Test fun `a part-filled ladder written off as nothing is the real case`() {
        // €200 ordered, €123 actually bought at about €3.97, row says zero.
        assertTrue(candidate("ERROR", 0.0, 200.0, ageMs = 2 * 24 * 60 * 60_000L))
        assertEquals(30.97, adoptable(30.97, 0.0, 200.0, 3.9700), 1e-9)
    }

    // ── and the four guards ─────────────────────────────────────────────────

    @Test fun `a large pre-existing holding is never swallowed by a small order`() {
        // THE GUARD THAT MATTERS MOST. The user's own long-term positions sit in
        // the same account; a €200 entry must not be able to claim €2,000 of
        // stock and put a stop under it.
        assertEquals("far more than was ordered — leave it alone",
            0.0, adoptable(500.0, 0.0, 200.0, 3.97), 1e-9)
        // Slight headroom for the ladder's own ceiling is allowed, and no more.
        assertTrue(adoptable(52.0, 0.0, 200.0, 3.97) > 0.0)     // ~€206, inside 5%
        assertEquals(0.0, adoptable(56.0, 0.0, 200.0, 3.97), 1e-9)  // ~€222, outside
    }

    @Test fun `quantity another record already claims is subtracted first`() {
        // Other live rows and the watchlist book are both netted off, so two
        // managers can never end up on the same shares.
        assertEquals(4.0, adoptable(10.0, 6.0, 200.0, 3.97), 1e-9)
        assertEquals("nothing left over means nothing to adopt",
            0.0, adoptable(10.0, 10.0, 200.0, 3.97), 1e-9)
        assertEquals(0.0, adoptable(10.0, 12.0, 200.0, 3.97), 1e-9)
    }

    @Test fun `only a row that ordered, recently, with nothing recorded`() {
        assertFalse("a filled row is not stranded", candidate("ERROR", 12.0, 200.0, 0L))
        assertFalse("an open row is not stranded", candidate("OPEN", 0.0, 200.0, 0L))
        assertFalse("a queued row is still being tried", candidate("RETRY", 0.0, 200.0, 0L))
        assertFalse("nothing was ordered", candidate("ERROR", 0.0, 0.0, 0L))
        assertFalse("too old to still be about this order",
            candidate("ERROR", 0.0, 200.0, ageMs = 8 * 24 * 60 * 60_000L))
    }

    @Test fun `no price means no adoption`() {
        // Without a price the value cap cannot be applied at all, and the cap is
        // the only thing standing between this and the user's own portfolio.
        assertEquals(0.0, adoptable(30.0, 0.0, 200.0, 0.0), 1e-9)
    }

    @Test fun `a name the broker does not hold yields nothing`() {
        assertEquals(0.0, adoptable(0.0, 0.0, 200.0, 3.97), 1e-9)
    }
}
