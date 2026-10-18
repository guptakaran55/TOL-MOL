package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PRICE LIMIT ON YOUR WAITING BUYS — half a day's move above where you
 * pressed the button. Over it, a buy that is ready to go asks you instead.
 */
class ManualBuyLimitTest {

    @Test fun `the limit is half a day's move above your price`() {
        // PEP on 16 Oct: asked at 130.85, a day's move 2.69.
        assertEquals(132.195, RvManualQueue.limitFor(130.85, 2.69), 1e-9)
    }

    @Test fun `with no day's move known, 2% of the price stands in for one`() {
        assertEquals(101.0, RvManualQueue.limitFor(100.0, 0.0), 1e-9)
    }

    @Test fun `no price, no limit`() {
        assertEquals(0.0, RvManualQueue.limitFor(0.0, 2.0), 1e-9)
    }

    @Test fun `over the limit asks, at or under it buys`() {
        assertTrue(RvManualQueue.overLimit(132.30, 132.195))
        assertFalse(RvManualQueue.overLimit(132.195, 132.195))
        assertFalse(RvManualQueue.overLimit(131.40, 132.195))
    }

    @Test fun `an unknown price or limit never blocks a buy`() {
        assertFalse(RvManualQueue.overLimit(null, 132.0))
        assertFalse(RvManualQueue.overLimit(140.0, 0.0))
    }
}
