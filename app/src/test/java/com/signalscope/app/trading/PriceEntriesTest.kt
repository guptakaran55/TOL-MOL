package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUY AT MY PRICE — the trigger rule, as a GTT keeps it: the direction is fixed
 * when it is set (below the price waits for a fall, above for a rise), and it
 * fires the first time the price reaches the level that way — at it or beyond.
 */
class PriceEntriesTest {

    @Test fun `set below today's price it waits for a fall, above for a rise`() {
        assertEquals(false, PriceEntries.directionFor(95.0, 100.0))
        assertEquals(true, PriceEntries.directionFor(105.0, 100.0))
    }

    @Test fun `at today's price, or with no price, there is no direction to wait for`() {
        assertNull(PriceEntries.directionFor(100.0, 100.0))
        assertNull(PriceEntries.directionFor(95.0, 0.0))
        assertNull(PriceEntries.directionFor(0.0, 100.0))
    }

    @Test fun `a dip entry fires at the level and below, never above`() {
        assertFalse(PriceEntries.reached(rising = false, level = 95.0, price = 95.01))
        assertTrue(PriceEntries.reached(rising = false, level = 95.0, price = 95.0))
        assertTrue(PriceEntries.reached(rising = false, level = 95.0, price = 91.0))   // gapped through: buys cheaper
    }

    @Test fun `a breakout entry fires at the level and above, never below`() {
        assertFalse(PriceEntries.reached(rising = true, level = 105.0, price = 104.99))
        assertTrue(PriceEntries.reached(rising = true, level = 105.0, price = 105.0))
        assertTrue(PriceEntries.reached(rising = true, level = 105.0, price = 108.0))
    }

    @Test fun `no price is never a trigger`() {
        assertFalse(PriceEntries.reached(rising = false, level = 95.0, price = 0.0))
        assertFalse(PriceEntries.reached(rising = true, level = 105.0, price = Double.NaN))
    }

    @Test fun `an entry set either side of the price does not fire where it was set`() {
        for (now in listOf(10.0, 99.5, 250.0, 1234.56)) for (k in listOf(0.9, 0.99, 1.01, 1.2)) {
            val level = now * k
            val rising = PriceEntries.directionFor(level, now)!!
            assertFalse("level $level from $now", PriceEntries.reached(rising, level, now))
        }
    }

    @Test fun `each market keeps its own clock`() {
        assertEquals("de", PriceEntries.baseOf("DE"))
        assertEquals("us", PriceEntries.baseOf("US"))
    }
}
