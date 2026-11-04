package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A WATCHLIST BUY CUT OFF BEFORE ITS RESULT WAS RECORDED. REGN, 19 Oct 2026: the
 * €50 buy bought 0.063 shares, the app was stopped before the book heard back,
 * and "ladder running" stood in Waiting orders for four days.
 */
class InterruptedBuyTest {
    private val now = 1_792_760_000_000L
    private val min = 60_000L
    private fun pending(ageMin: Long, gtt: Long = 0L, order: String? = null) = ManualWatchStore.Entry(
        symbol = "REGN", space = "us", signalBarTs = 1_792_419_000L, requestedCash = 50.0,
        status = "PENDING", createdAt = now - ageMin * min, gttId = gtt, buyOrderId = order, forced = true)

    @Test fun `days old with no buy running is interrupted`() {
        assertTrue(ManualEntryWatcher.isInterrupted(pending(4 * 24 * 60), now, running = false))
    }

    @Test fun `a ladder still running is not, however long it takes`() {
        assertFalse(ManualEntryWatcher.isInterrupted(pending(30), now, running = true))
    }

    @Test fun `a buy a few minutes old is given its time`() {
        assertFalse(ManualEntryWatcher.isInterrupted(pending(5), now, running = false))
    }

    @Test fun `an order waiting at the broker is a real wait`() {
        assertFalse(ManualEntryWatcher.isInterrupted(pending(600, gtt = 123L), now, running = false))
        assertFalse(ManualEntryWatcher.isInterrupted(pending(600, order = "240101000123"), now, running = false))
    }

    @Test fun `only a pending row`() {
        assertFalse(ManualEntryWatcher.isInterrupted(pending(600).copy(status = "OPEN"), now, running = false))
    }
}
