package com.signalscope.app.trading

import com.signalscope.app.data.PriceEntryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A BUY AT MY PRICE WHOSE BUY WENT NOWHERE IS ARMED AGAIN (6 Nov 2026) — but only
 * when no order can have reached Trading 212, 15 minutes later at the soonest,
 * and three buys in all. Anything that may have left an order behind stops.
 */
class PriceEntryRetryTest {
    private val now = 1_000_000L
    private val e = PriceEntryStore.Entry(id = "pe1", symbol = "SAP", status = "TRIGGERED", level = 200.0)
    private fun out(text: String, held: Boolean = false, bought: Boolean = false, none: Boolean = false) =
        RvManualQueue.RunOutcome(text, held, bought, none)

    @Test fun `bought, or held by the gate, is done as before`() {
        assertEquals("TRIGGERED", PriceEntries.afterBuy(e, out("ok", bought = true), now).status)
        assertEquals("TRIGGERED", PriceEntries.afterBuy(e, out("held", held = true), now).status)
    }

    @Test fun `nothing placed is armed again, 15 minutes on, up to three buys`() {
        val a = PriceEntries.afterBuy(e, out("nothing bought: no cash", none = true), now)
        assertEquals("WAITING", a.status); assertEquals(1, a.tries)
        assertEquals(now + PriceEntries.RETRY_GAP_MS, a.retryAt)
        val b = PriceEntries.afterBuy(a.copy(status = "TRIGGERED"), out("nothing bought", none = true), now)
        assertEquals("WAITING", b.status); assertEquals(2, b.tries)
        val c = PriceEntries.afterBuy(b.copy(status = "TRIGGERED"), out("nothing bought", none = true), now)
        assertEquals("FAILED", c.status); assertEquals(3, c.tries)
    }

    @Test fun `a failure that may have left an order behind is never retried`() {
        assertEquals("FAILED", PriceEntries.afterBuy(e, out("failed: socket closed"), now).status)
    }

    @Test fun `only results that rule out an order count as nothing placed`() {
        assertTrue(RvManualQueue.nothingPlaced("nothing bought: the ladder filled nothing"))
        assertTrue(RvManualQueue.nothingPlaced("failed: could not read your current holding from Trading 212 — nothing placed"))
        assertFalse(RvManualQueue.nothingPlaced("failed: timeout"))
        assertFalse(RvManualQueue.nothingPlaced("unknown order kind X"))
        assertFalse(RvManualQueue.nothingPlaced("bought 3 at 200"))
    }
}
