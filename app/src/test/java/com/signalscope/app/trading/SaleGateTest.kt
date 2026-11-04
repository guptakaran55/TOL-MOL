package com.signalscope.app.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An algo's sale goes at once; the watchlist's still waits for the RV gate. */
class SaleGateTest {
    @Test fun algoSalesSkipTheGate() {
        assertFalse(T212LiveMirror.saleWaitsForGate("de__buyx6g"))
        assertFalse(T212LiveMirror.saleWaitsForGate("us__b148ki0"))
    }

    @Test fun watchlistSalesKeepIt() {
        assertTrue(T212LiveMirror.saleWaitsForGate("de__manual"))
        assertTrue(T212LiveMirror.saleWaitsForGate("us__manual"))
    }
}
