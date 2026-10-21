package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO OWNS A REAL POSITION'S EXIT.
 *
 * A live broker row can be in one of three relationships with the paper book, and
 * each has a different manager. Confusing any two of them either strands a
 * position with nobody running its exits, or sends two sell orders after the same
 * shares — the second of which opens a short the account never authorised.
 *
 *   paper row OPEN    → the strategy manages it. Nothing else may touch it.
 *   paper row CLOSED  → the exit-retry sweep re-sells it. Nothing else may.
 *   paper row MISSING → nobody. This is the orphan sweep's, and only its.
 *
 * The middle case is the dangerous one to get wrong: both sweeps can see the row
 * and both would act. Pinned here rather than left to the reading of two
 * functions in different files.
 */
class OrphanPositionTest {

    /** The claim rule the orphan sweep applies. */
    private fun orphanSweepClaims(
        paperIds: Set<String>, engineHoldsNames: Set<String>,
        liveId: String, liveSymbol: String
    ): Boolean = liveId !in paperIds && liveSymbol !in engineHoldsNames

    /** The claim rule the exit-retry sweep applies. */
    private fun retrySweepClaims(paperStatusById: Map<String, String>, liveId: String): Boolean =
        paperStatusById[liveId] == "CLOSED"

    // ── the three states ────────────────────────────────────────────────────

    @Test fun `a position the strategy still holds belongs to the strategy`() {
        val paper = mapOf("GXI-100" to "OPEN")
        assertFalse("orphan sweep must not touch it",
            orphanSweepClaims(paper.keys, setOf("GXI"), "GXI-100", "GXI"))
        assertFalse("nor the retry sweep",
            retrySweepClaims(paper, "GXI-100"))
    }

    @Test fun `a position whose paper side closed belongs to the retry sweep`() {
        val paper = mapOf("GXI-100" to "CLOSED")
        assertTrue(retrySweepClaims(paper, "GXI-100"))
        assertFalse("the orphan sweep must stand back or both will sell",
            orphanSweepClaims(paper.keys, emptySet(), "GXI-100", "GXI"))
    }

    @Test fun `a position with no paper row at all belongs to the orphan sweep`() {
        // The state two German positions were actually found in: real, open, and
        // absent from the book entirely.
        val paper = emptyMap<String, String>()
        assertTrue(orphanSweepClaims(paper.keys, emptySet(), "SRT3-1789970400", "SRT3"))
        assertFalse("the retry sweep cannot see it — missing is not CLOSED",
            retrySweepClaims(paper, "SRT3-1789970400"))
    }

    @Test fun `exactly one sweep claims each state`() {
        // The property that matters more than any individual case.
        val cases = listOf(
            Triple("OPEN", false, false),     // the strategy's
            Triple("CLOSED", false, true),    // the retry's
            Triple("MISSING", true, false))   // the orphan's
        for ((state, wantOrphan, wantRetry) in cases) {
            val paper = if (state == "MISSING") emptyMap() else mapOf("X-1" to state)
            val holds = if (state == "OPEN") setOf("X") else emptySet()
            val o = orphanSweepClaims(paper.keys, holds, "X-1", "X")
            val r = retrySweepClaims(paper, "X-1")
            assertEquals("$state orphan claim", wantOrphan, o)
            assertEquals("$state retry claim", wantRetry, r)
            assertFalse("$state: both swept the same row", o && r)
        }
    }

    @Test fun `the same NAME held under another id is left alone`() {
        // Two algos can hold one company, and an id that does not match is not
        // proof nothing manages it. Selling then would liquidate the sibling.
        assertFalse(orphanSweepClaims(setOf("GXI-200"), setOf("GXI"), "GXI-100", "GXI"))
    }

    // ── what may be acted on ────────────────────────────────────────────────

    /** The anchor rule: no real fill price, no verdict. */
    private fun canAnchor(paid: Double, entryTs: Long) = paid > 0.0 && entryTs > 0L

    @Test fun `a position with no recorded fill price is never judged`() {
        // Every adopted rule measures from what was paid. Guessing one would put a
        // stop at a price nobody traded.
        assertFalse(canAnchor(0.0, 1789970400L))
        assertFalse(canAnchor(251.50, 0L))
        assertTrue(canAnchor(251.50, 1789970400L))
    }

    /** The money rule: a verdict is not enough, the rule set must be complete. */
    private fun maySell(ok: Boolean, fires: Boolean, entryMacdKnown: Boolean) =
        ok && fires && entryMacdKnown

    @Test fun `a partial rule set never sells`() {
        // With no entry bar in the window the MACD-distance qualifier and the
        // holding-time limit stop working, and both fail towards selling. An order
        // placed then wears the name of a strategy that never specified it.
        assertFalse("fires but the rule set is incomplete", maySell(true, true, false))
        assertFalse("no verdict at all", maySell(false, true, true))
        assertFalse("judged, and says hold", maySell(true, false, true))
        assertTrue("judged, fires, complete", maySell(true, true, true))
    }

    // ── the watchlist's own manager ─────────────────────────────────────────

    /**
     * The full claim rule, including the namespace that manages its own.
     *
     * The watchlist book records the SIGNAL BAR a position opened on; a live row
     * only knows when the order was sent. So where both could act, the book's
     * anchor is the better one and the sweep stands back — by symbol, not by
     * namespace, because a live row in that namespace with no book entry behind
     * it is covered by nobody at all.
     */
    private fun claimsWithBook(
        paperIds: Set<String>, engineHoldsNames: Set<String>, bookOwns: Set<String>,
        liveId: String, liveSymbol: String
    ): Boolean = liveId !in paperIds && liveSymbol !in engineHoldsNames &&
        liveSymbol !in bookOwns

    @Test fun `a watchlist position the book still holds is left to the book`() {
        // Both managers can see it, both would sell, and the second sell lands
        // in nothing — a short the account never authorised.
        assertFalse(claimsWithBook(emptySet(), emptySet(), setOf("SBILIFE"),
            "SBILIFE-1", "SBILIFE"))
        assertFalse(claimsWithBook(emptySet(), emptySet(), setOf("SBFC"), "SBFC-1", "SBFC"))
    }

    @Test fun `a watchlist row with no book entry is still covered`() {
        // The reason this is filtered by symbol and not by namespace. Two real
        // positions were in exactly this state — a hand-over that never landed —
        // and excluding the whole namespace would have left them unmanaged.
        assertTrue(claimsWithBook(emptySet(), emptySet(), setOf("SBILIFE", "SBFC"),
            "TATASTEEL-1", "TATASTEEL"))
        assertTrue(claimsWithBook(emptySet(), emptySet(), setOf("ELG", "IFX"),
            "S92-1", "S92"))
    }

    @Test fun `an algo namespace is unaffected by the book`() {
        // The book only governs its own namespace; an algo's orphan is the
        // sweep's regardless of what the watchlist happens to hold.
        assertTrue(claimsWithBook(emptySet(), emptySet(), emptySet(), "SRT3-1", "SRT3"))
        assertTrue(claimsWithBook(emptySet(), emptySet(), emptySet(), "GXI-1", "GXI"))
    }
}
