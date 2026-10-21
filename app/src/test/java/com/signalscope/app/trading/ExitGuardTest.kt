package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHERE THE SAFETY STOP SITS under a sale that is waiting: half a day's typical
 * move below the lower of the strategy's sale price and the price now, kept
 * between 1% and 3%, 1.5% when the typical move is unknown.
 */
class ExitGuardTest {

    @Test fun `half a typical day's move under the sale price`() {
        // PANW sold on paper at 400, typical daily move 10 (2.5%): stop 1.25% under.
        assertEquals(395.0, ExitGuard.level(400.0, 401.0, 10.0)!!, 1e-9)
    }

    @Test fun `measured from the price now when it has already fallen`() {
        assertEquals(385.0, ExitGuard.level(400.0, 390.0, 10.0)!!, 1e-9)   // 390 less half of 10
    }

    @Test fun `a quiet stock still gets one percent of room`() {
        assertEquals(99.0, ExitGuard.level(100.0, null, 0.5)!!, 1e-9)
    }

    @Test fun `a wild stock is not given more than three percent`() {
        assertEquals(97.0, ExitGuard.level(100.0, null, 20.0)!!, 1e-9)
    }

    @Test fun `unknown typical move uses one and a half percent`() {
        assertEquals(98.5, ExitGuard.level(100.0, null, 0.0)!!, 1e-9)
    }

    @Test fun `rounded down to the cent, never up`() {
        assertEquals(2.67, ExitGuard.level(2.72, null, 0.0)!!, 1e-9)   // 2.6792
    }

    @Test fun `no price at all, no stop`() {
        assertNull(ExitGuard.level(0.0, null, 5.0))
    }

    @Test fun `a stop at or above the price now is left to the app's own sale`() {
        assertTrue(ExitGuard.wouldTriggerNow(395.0, 394.0))
        assertTrue(ExitGuard.wouldTriggerNow(395.0, 395.0))
        assertFalse(ExitGuard.wouldTriggerNow(395.0, 396.0))
        assertFalse(ExitGuard.wouldTriggerNow(395.0, null))
    }
}
