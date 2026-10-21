package com.signalscope.app.trading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A WAITING SALE AT THE OPEN: left alone while the stock rises, sold once the
 * rise stops — and never held back when it is a stop sale, or when the prices
 * to judge by are missing.
 */
class ExitTimingTest {

    private fun d(belowStop: Boolean = false, price: Double? = 100.0, mins: Long = 30,
                  barsRead: Boolean = true, up: Boolean? = true, high: Double = 100.5,
                  atr: Double = 3.0, waited: Long = 0L) =
        ExitTiming.decide(belowStop, price, mins, barsRead, up, high, atr, waited)

    @Test fun `a rising open is left to rise`() = assertFalse(d().sell)

    @Test fun `a falling bar sells`() = assertTrue(d(up = false).sell)

    @Test fun `a stop sale never waits, however strong the open`() = assertTrue(d(belowStop = true).sell)

    @Test fun `before the first bar can have finished it waits`() = assertFalse(d(mins = 5).sell)

    @Test fun `a stop sale does not wait for the first bar either`() =
        assertTrue(d(mins = 5, belowStop = true).sell)

    @Test fun `no finished bar of today yet waits`() = assertFalse(d(up = null).sell)

    @Test fun `no live price is not held back blind`() = assertTrue(d(price = null).sell)

    @Test fun `no bars at all is not held back blind`() = assertTrue(d(barsRead = false).sell)

    @Test fun `a third of a day's move off today's high sells`() {
        // High 103, typical move 3: selling from 102 down.
        assertTrue(d(price = 102.0, high = 103.0).sell)
        assertFalse(d(price = 102.1, high = 103.0).sell)
    }

    @Test fun `ninety minutes is the longest it is held`() {
        assertTrue(d(waited = 90 * 60_000L).sell)
        assertFalse(d(waited = 89 * 60_000L).sell)
    }

    @Test fun `even waiting for a first bar stops at ninety minutes`() =
        assertTrue(d(up = null, waited = 90 * 60_000L).sell)
}
