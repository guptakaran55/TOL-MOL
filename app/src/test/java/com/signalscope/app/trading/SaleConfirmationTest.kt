package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHEN MAY THE APP SAY A POSITION WAS SOLD?
 *
 * The Indian exit paths answered this with "the live-order row is no longer
 * OPEN", which is a different question and answers yes in several situations
 * where nothing reached the broker at all. The result was the book marked
 * CLOSED and a notification announcing a sale for a position the account still
 * held — with its stop retired along with the record.
 *
 * Modelled as the rule, because the real one needs a store and a broker. What
 * is pinned is that a sale needs POSITIVE evidence: an order id.
 */
class SaleConfirmationTest {

    /** The rule: a sale is a closed row carrying a sell order id. */
    private fun sold(status: String?, sellOrderId: String?): Boolean =
        status == "CLOSED" && !sellOrderId.isNullOrBlank()

    /** What the old code did, kept here so the difference is visible. */
    private fun oldRule(status: String?): Boolean = status != "OPEN"

    @Test fun `a closed row with an order id is a sale`() {
        assertTrue(sold("CLOSED", "2100844970163757056"))
    }

    @Test fun `a missing row is not a sale`() {
        // `null != "OPEN"` is true, so a position with no live-order record at all
        // used to read as a completed sale — the emptiest possible evidence
        // producing the most confident possible claim.
        assertFalse(sold(null, null))
        assertTrue("which the old rule got backwards", oldRule(null))
    }

    @Test fun `a row retired by the broker reconcile is not a sale`() {
        // MANUAL_CLOSED means the name was absent from the holdings list. For an
        // Indian delivery buy that is simply what the day of purchase looks like,
        // and it is exactly how SYRMA came to be reported as sold.
        assertFalse(sold("MANUAL_CLOSED", null))
        assertTrue(oldRule("MANUAL_CLOSED"))
    }

    @Test fun `a failed attempt is not a sale`() {
        // ERROR is the opposite of a sale, and the old rule counted it as one.
        assertFalse(sold("ERROR", null))
        assertTrue(oldRule("ERROR"))
    }

    @Test fun `an order still working is not a sale`() {
        // EXITING means an order was attempted and has not completed. Reporting it
        // as done is how a position gets dropped from the book mid-exit.
        assertFalse(sold("EXITING", null))
        assertTrue(oldRule("EXITING"))
    }

    @Test fun `a still-open row is not a sale under either rule`() {
        assertFalse(sold("OPEN", null))
        assertFalse(oldRule("OPEN"))
    }

    // ── absence, which is the other way a sale gets invented ───────────────

    private val CONFIRM_MS = 25 * 60_000L

    /** May a missing holding be retired on this pass? */
    private fun mayRetire(inHeldList: Boolean, absentAt: Long, now: Long): Boolean =
        !inHeldList && absentAt != 0L && now - absentAt >= CONFIRM_MS

    /** What the entry's marker becomes after this pass. */
    private fun nextAbsentAt(inHeldList: Boolean, absentAt: Long, now: Long): Long =
        if (inHeldList) 0L else if (absentAt == 0L) now else absentAt

    @Test fun `one missing reading is a question, not an answer`() {
        // The defect in both markets: a single short holdings list retired a live
        // position and took its stop with it. India twice inside half an hour of
        // the buy, Germany twice inside thirty-three minutes.
        val now = 1_000_000L
        assertFalse("first sighting must never retire", mayRetire(false, 0L, now))
        assertEquals("but it is remembered", now, nextAbsentAt(false, 0L, now))
    }

    @Test fun `a second reading, far enough apart, is evidence`() {
        val first = 1_000_000L
        assertFalse(mayRetire(false, first, first + 10 * 60_000L))
        assertTrue(mayRetire(false, first, first + CONFIRM_MS))
    }

    @Test fun `the wait spans more than one watch pass`() {
        // Otherwise the "second reading" could be the same answer inspected twice
        // rather than a separate call to the broker.
        val onePass = 15 * 60_000L
        assertTrue("must outlast a single pass", CONFIRM_MS > onePass)
    }

    @Test fun `reappearing clears the memory entirely`() {
        // A name that flickers once must never accumulate its way to being
        // retired over a week of unrelated bad reads.
        assertEquals(0L, nextAbsentAt(true, 500_000L, 1_000_000L))
        assertFalse(mayRetire(true, 500_000L, 1_000_000L))
    }

    @Test fun `closed without an order id is not a sale`() {
        // The narrow case the new rule adds on top: a row marked closed by some
        // path that never placed anything. An id is what an order leaves behind,
        // so requiring one is what makes this evidence rather than assertion.
        assertFalse(sold("CLOSED", null))
        assertFalse(sold("CLOSED", ""))
        assertFalse(sold("CLOSED", "   "))
    }
}
