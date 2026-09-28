package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SALE THAT WAS NEVER OBSERVED HAS TO END SOMEWHERE.
 *
 * Both exit paths mark a row EXITING before selling and return it to OPEN if the
 * sell fails — which is correct while the process lives. It does not always live:
 * a patient ladder runs for minutes and Android kills background work freely, so
 * a row can enter EXITING and have nobody left to bring it back.
 *
 * Nothing watched for that. The retry sweep read only OPEN rows, so an abandoned
 * exit was invisible to the one thing that could rescue it: not retried, not
 * closed, and reported by the audit as a stuck position once an hour for ever. A
 * Continental row sat that way from 8 September, alerting hourly about 0.698
 * shares the account no longer held.
 *
 * Modelled as pure decisions over row state, because the sweep itself needs a
 * Context and a broker. What is pinned is WHEN a row is adopted and WHEN it is
 * closed — the two judgements that were missing.
 */
class ExitRecoveryTest {

    private val TWO_HOURS = 2 * 60 * 60 * 1000L

    /** The adoption rule the sweep now applies. */
    private fun abandoned(status: String, lastTouchedMsAgo: Long): Boolean =
        status == "EXITING" && lastTouchedMsAgo > TWO_HOURS

    /**
     * The closing rule. [heldQty] null means the broker could not be read, which
     * is never grounds for closing anything.
     */
    private fun closeAsGone(heldQty: Map<String, Double>?, symbol: String): Boolean =
        heldQty != null && (heldQty[symbol] ?: 0.0) <= 0.0

    // ── adopting an abandoned exit ──────────────────────────────────────────

    @Test fun `a row abandoned in EXITING is adopted`() {
        assertTrue("ten days is not a sale in progress",
            abandoned("EXITING", 10L * 24 * 60 * 60 * 1000))
    }

    @Test fun `a sale actually in flight is left alone`() {
        // The cost of adopting too early is a second sell order against a real one
        // still working, so the window is deliberately generous.
        assertFalse("a ladder may legitimately run for minutes",
            abandoned("EXITING", 90_000L))
        assertFalse("and for a while longer", abandoned("EXITING", 30L * 60 * 1000))
    }

    @Test fun `only EXITING rows are adopted`() {
        // OPEN already has an owner, CLOSED is finished, and an entry still filling
        // is a different problem with a different sweep.
        listOf("OPEN", "CLOSED", "PENDING", "RETRY", "ERROR").forEach {
            assertFalse("$it must not be adopted by the exit sweep",
                abandoned(it, 10L * 24 * 60 * 60 * 1000))
        }
    }

    // ── closing a row whose shares are gone ─────────────────────────────────

    @Test fun `a row whose shares the broker no longer holds is closed`() {
        // The Continental case: the ledger claimed 0.698, the account held none.
        // Re-selling would ask for stock that is not there, fail for ever, and keep
        // the alert alive.
        assertTrue(closeAsGone(mapOf("BAS" to 1.889), "CON"))
        assertTrue("an explicit zero counts too", closeAsGone(mapOf("CON" to 0.0), "CON"))
    }

    @Test fun `a row the broker still holds is re-sold, not closed`() {
        assertFalse(closeAsGone(mapOf("CON" to 0.698), "CON"))
    }

    @Test fun `an unreadable portfolio closes nothing`() {
        // THE DANGEROUS CONFUSION. "I could not read your account" and "your
        // account is empty" arrive as the same shape and mean opposite things —
        // treating one as the other would close every row in the book on a single
        // rate-limited reply, each one silently writing off a real position.
        assertFalse("null is not an empty account", closeAsGone(null, "CON"))
        assertFalse(closeAsGone(null, "BAS"))
    }

    // ── the two together ────────────────────────────────────────────────────

    @Test fun `an abandoned exit on vanished shares resolves in one pass`() {
        // Adopted back to OPEN, then found to be gone, then closed. The whole point
        // is that this now terminates instead of alerting for ever.
        val adopted = abandoned("EXITING", 10L * 24 * 60 * 60 * 1000)
        assertTrue(adopted)
        val statusAfterAdoption = if (adopted) "OPEN" else "EXITING"
        assertEquals("OPEN", statusAfterAdoption)
        assertTrue("and then closed on the same sweep",
            closeAsGone(emptyMap(), "CON"))
    }

    @Test fun `an abandoned exit on shares still held gets another sell`() {
        // The other branch, which must not be lost: the position is real and still
        // needs exiting, so adoption hands it to the retry rather than the closer.
        assertTrue(abandoned("EXITING", 3L * 60 * 60 * 1000))
        assertFalse("still held, so it is re-sold",
            closeAsGone(mapOf("CON" to 0.698), "CON"))
    }
}
