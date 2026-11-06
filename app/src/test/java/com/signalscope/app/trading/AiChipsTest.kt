package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI & CHIPS (6 Nov 2026): which stocks count, and the share a buy leaves. */
class AiChipsTest {
    @Test fun `a stock is keyed by its market and bare symbol`() {
        assertEquals("US:NVDA", AiChips.keyOf("nvda", "US"))
        assertEquals("DE:IFX", AiChips.keyOf("IFX.DE", "US"))       // a German listing held in the US space
        assertEquals("DE:IFX", AiChips.keyOf("IFX", "DE"))
        assertEquals("IN:NETWEB", AiChips.keyOf("NETWEB.NS"))
        assertEquals("IN:NETWEB", AiChips.keyOf("NETWEB", "IN"))
        assertEquals("XX:ARM.L", AiChips.keyOf("ARM.L"))
    }

    @Test fun `your mark wins, then the list, then the industry`() {
        val none = emptySet<String>()
        assertTrue(AiChips.classify("US:NVDA", null, none, none, none))                       // on the list
        assertTrue(AiChips.classify("US:TXN", "Semiconductors", none, none, none))
        assertTrue(AiChips.classify("US:ENTG", "Semiconductor Equipment & Materials", none, none, none))
        assertFalse(AiChips.classify("US:KO", "Beverages—Non-Alcoholic", none, none, none))
        assertFalse(AiChips.classify("US:NVDA", null, none, setOf("US:NVDA"), none))           // marked off
        assertTrue(AiChips.classify("US:KO", null, setOf("US:KO"), none, none))                // marked on
        assertTrue(AiChips.classify("DE:SMHN", null, none, none, setOf("DE:SMHN")))            // learned
    }

    @Test fun `the share after a buy`() {
        assertEquals(30.0, AiChips.shareAfter(300.0, 1000.0, 0.0, true), 1e-9)
        assertEquals(400.0 / 1100.0 * 100.0, AiChips.shareAfter(300.0, 1000.0, 100.0, true), 1e-9)
        assertEquals(300.0 / 1100.0 * 100.0, AiChips.shareAfter(300.0, 1000.0, 100.0, false), 1e-9)
        assertEquals(0.0, AiChips.shareAfter(0.0, 0.0, 0.0, true), 1e-9)
    }
}
