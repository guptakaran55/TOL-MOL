package com.signalscope.app.trading

import com.signalscope.app.data.StrategyLabStore.LedgerTrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A QUEUED REAL BUY LIVES AS LONG AS THE PAPER TRADE IT BELONGS TO.
 *
 * It used to die when the algo's next bar was judged. For a German signal on
 * the evening bar that meant about an hour the next morning, then a write-off —
 * while the strategy went on holding the position it had asked for.
 */
class QueuedEntryLifeTest {

    private fun trade(id: String, status: String) = LedgerTrade(
        id = id, symbol = id.substringBefore('-'), status = status,
        entryTs = id.substringAfter('-').toLong(), entryPrice = 62.40, entryReason = "")

    // Nemetschek's real order from 25 September.
    private val nem = "NEM-1790344800"

    @Test fun `alive while paper holds the position`() {
        assertTrue(T212LiveMirror.paperStillHolds(listOf(trade(nem, "OPEN")), nem))
    }

    @Test fun `dropped once paper has exited`() {
        // Nemetschek was closed on paper at Monday's 18:00 bar; a queued buy
        // still waiting at that point must not go on to buy a finished trade.
        assertFalse(T212LiveMirror.paperStillHolds(listOf(trade(nem, "CLOSED")), nem))
    }

    @Test fun `dropped when the position is gone from the book altogether`() {
        // A reset, or "I sold this myself", removes the paper trade outright.
        assertFalse(T212LiveMirror.paperStillHolds(emptyList(), nem))
    }

    @Test fun `a later trade on the same name does not keep an old order alive`() {
        // Exit, then a fresh entry on the same company: the new trade has its own
        // order. The old queued one matched on the symbol alone would buy twice.
        val later = "NEM-1790604000"
        val book = listOf(trade(nem, "CLOSED"), trade(later, "OPEN"))
        assertFalse(T212LiveMirror.paperStillHolds(book, nem))
        assertTrue(T212LiveMirror.paperStillHolds(book, later))
    }
}
