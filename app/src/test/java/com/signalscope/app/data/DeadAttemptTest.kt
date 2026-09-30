package com.signalscope.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT COUNTS AS AN ATTEMPT THAT BOUGHT NOTHING.
 *
 * A row left the book only when it was sold, so failed ladders and cancelled
 * orders sat in the open list for good and — worse — stood in for the company
 * when the panel asked whether the book already covered a real holding.
 *
 * The rule is local and deliberately narrow: the status says it ended, and the
 * share count agrees. Where the two disagree the shares win.
 */
class DeadAttemptTest {

    private fun e(status: String, qty: Double = 0.0) =
        ManualWatchStore.Entry(symbol = "TRITURBINE", space = "in", status = status, filledQty = qty)

    @Test fun `a ladder that filled nothing is a dead attempt`() {
        assertTrue(ManualWatchStore.isDeadAttempt(e("FAILED")))
    }

    @Test fun `an order cancelled before it filled is a dead attempt`() {
        assertTrue(ManualWatchStore.isDeadAttempt(e("CANCELLED")))
    }

    @Test fun `shares win over the label`() {
        // A row carrying shares is a position whatever its status says. Tidying
        // it away would hide real stock behind a bookkeeping mismatch.
        assertFalse(ManualWatchStore.isDeadAttempt(e("FAILED", 3.0)))
        assertFalse(ManualWatchStore.isDeadAttempt(e("CANCELLED", 0.5)))
    }

    // ── a second buy on the same signal ─────────────────────────────────────

    @Test fun `a free signal, or one whose attempt bought nothing, may be claimed`() {
        assertTrue(ManualWatchStore.mayReplace(null))
        assertTrue(ManualWatchStore.mayReplace(e("FAILED")))
        assertTrue(ManualWatchStore.mayReplace(e("CANCELLED")))
    }

    @Test fun `a live order is never written over - the Tata Steel bug`() {
        // The first tap left a trigger resting at Zerodha; the second tap's
        // refusal was written over it and the book lost a real order.
        val resting = ManualWatchStore.Entry(symbol = "TATASTEEL", space = "in",
            status = "PENDING", gttId = 335041527L)
        assertFalse(ManualWatchStore.mayReplace(resting))
    }

    @Test fun `a position or a finished trade is never written over`() {
        assertFalse(ManualWatchStore.mayReplace(e("OPEN", 16.0)))
        assertFalse(ManualWatchStore.mayReplace(e("PENDING")))
        assertFalse("its history is a record of real money", ManualWatchStore.mayReplace(e("CLOSED")))
        assertFalse("shares beat the label", ManualWatchStore.mayReplace(e("FAILED", 2.0)))
    }

    @Test fun `live and finished rows are never dead attempts`() {
        // A resting trigger has no shares yet and is still very much alive —
        // it can fill at any moment and must stay where it can be cancelled.
        assertFalse(ManualWatchStore.isDeadAttempt(e("PENDING")))
        // An open row can briefly read zero while its fill is reconciled.
        assertFalse(ManualWatchStore.isDeadAttempt(e("OPEN")))
        assertFalse(ManualWatchStore.isDeadAttempt(e("OPEN", 10.0)))
        assertFalse(ManualWatchStore.isDeadAttempt(e("CLOSED")))
    }
}
