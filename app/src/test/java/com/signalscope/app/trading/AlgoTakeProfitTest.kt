package com.signalscope.app.trading

import com.signalscope.app.data.StrategyLabStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** TAKING PROFIT BY HAND: the trade is closed in the algo's record, not erased. */
class AlgoTakeProfitTest {

    private val open = StrategyLabStore.LedgerTrade(
        id = "SAP-1790000000", symbol = "SAP", status = "OPEN",
        entryTs = 1790000000L, entryPrice = 100.0, entryReason = "MACD slope up", barsHeld = 7)

    @Test fun `closed at the sale price, net of the algo's own costs`() {
        val c = AlgoTakeProfit.closedTrade(open, 105.0, 1790100000L, costPct = 0.2, notional = 1000.0)
        assertEquals("CLOSED", c.status)
        assertEquals(105.0, c.exitPrice!!, 1e-9)
        assertEquals(1790100000L, c.exitTs)
        assertEquals(AlgoTakeProfit.REASON, c.exitReason)
        assertEquals(4.8, c.pctReturn, 1e-9)          // +5% gross, less 0.2% round trip
        assertEquals(48.0, c.pnlNotional, 1e-9)       // on the algo's 1000 paper stake
    }

    @Test fun `everything about the entry is kept`() {
        val c = AlgoTakeProfit.closedTrade(open, 95.0, 1790100000L, costPct = 0.0, notional = 1000.0)
        assertEquals(open.id, c.id)
        assertEquals(open.entryTs, c.entryTs)
        assertEquals(open.entryPrice, c.entryPrice, 1e-9)
        assertEquals(open.entryReason, c.entryReason)
        assertEquals(7, c.barsHeld)
        assertEquals(-5.0, c.pctReturn, 1e-9)         // a loss is booked as a loss
        assertEquals(-50.0, c.pnlNotional, 1e-9)
    }
}
