package com.signalscope.app.trading

import com.signalscope.app.data.StrategyLabStore.LedgerTrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FLAGGED SEAT LETS GO WHEN ITS TRADE CLOSES. Test B0 and Test D, 23 Oct 2026:
 * BTCUSDC went exit-only, its trade closed, and the seat stayed on it — the pass
 * reports the sale to the seat before saving the trade as closed, so the trade
 * still read as held.
 */
class GateSeatReleaseTest {
    private fun open(entry: Long) = LedgerTrade(id = "BTCUSDC-$entry", symbol = "BTCUSDC", status = "OPEN",
        entryTs = entry, entryPrice = 83000.0, entryReason = "flip")

    @Test fun `the trade being closed no longer holds the seat`() {
        assertFalse(MacdGateEngine.stillHeld(listOf(open(1_792_540_800L)), "BTCUSDC", closing = 1_792_540_800L))
    }

    @Test fun `another open trade in the same coin still does`() {
        val trades = listOf(open(1_792_540_800L), open(1_792_627_200L))
        assertTrue(MacdGateEngine.stillHeld(trades, "BTCUSDC", closing = 1_792_540_800L))
    }

    @Test fun `without a closing trade, any open one holds it`() {
        assertTrue(MacdGateEngine.stillHeld(listOf(open(1_792_540_800L)), "btcusdc", closing = 0L))
    }
}
