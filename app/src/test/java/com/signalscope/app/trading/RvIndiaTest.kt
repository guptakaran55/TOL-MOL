package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RV GATE ON INDIA'S BUYS (6 Nov 2026): an India order is India's — its market
 * hours, its Zerodha symbol — and an order accepted at Zerodha that has not filled
 * yet reads as placed, never as "nothing bought".
 */
class RvIndiaTest {
    private fun order(space: String) = RvManualQueue.Order(id = "x", kind = RvManualQueue.Kind.WATCH_BUY,
        space = space, symbol = "SAILIFE", cash = 6000.0)

    @Test fun `an India order belongs to India's market`() {
        assertEquals("in", order("IN").ns)
        assertEquals("de", order("DE").ns)
        assertEquals("us", order("US").ns)
        assertFalse(order("IN").isSell)
    }

    @Test fun `an order working at Zerodha is placed, not nothing bought`() {
        val working = RvManualQueue.indiaPlaced(immediate = true, note = "limit 1530.00")
        assertTrue(working, working.startsWith("order placed at Zerodha"))
        val trigger = RvManualQueue.indiaPlaced(immediate = false, note = "")
        assertTrue(trigger, trigger.startsWith("buy trigger resting at Zerodha"))
        assertFalse(RvManualQueue.nothingPlaced(working))
        assertFalse(RvManualQueue.nothingPlaced(trigger))
    }
}
