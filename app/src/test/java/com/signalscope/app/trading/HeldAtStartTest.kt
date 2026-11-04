package com.signalscope.app.trading

import com.signalscope.app.trading.StrategyLabEngine.Trade
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE TRADE THE STRATEGY WAS ALREADY IN WHEN THE ALGO WAS SWITCHED ON. BTC, 2026:
 * the 50-day rule bought on 17 Aug and sold on 22 Oct (+26%); the algo was switched
 * on 20 Oct, so the chart showed the 17 Aug entry signal with no trade after it.
 */
class HeldAtStartTest {
    private val day = 86_400L
    private val aug17 = 1_786_924_800L
    private val oct20 = aug17 + 64 * day
    private fun trade(entry: Long, exit: Long?) = Trade(symbol = "BTCUSDC",
        status = if (exit == null) "OPEN" else "CLOSED", entryTs = entry, entryPrice = 64486.01,
        entryReason = "close above SMA50", exitTs = exit, exitPrice = exit?.let { 81331.39 },
        pctReturn = 26.1, pnlNotional = 261.0, barsHeld = 66)

    @Test fun `a trade open across the start line is kept apart`() {
        val free = listOf(trade(aug17 - 30 * day, aug17 - 4 * day), trade(aug17, oct20 + 2 * day))
        val held = StrategyLabEngine.heldAtStart(free, oct20)
        assertEquals(listOf(aug17), held.map { it.entryTs })
    }

    @Test fun `still open today counts, finished before the start does not`() {
        assertEquals(1, StrategyLabEngine.heldAtStart(listOf(trade(aug17, null)), oct20).size)
        assertEquals(0, StrategyLabEngine.heldAtStart(listOf(trade(aug17, oct20 - day)), oct20).size)
    }

    @Test fun `no start line, nothing held at it`() {
        assertEquals(0, StrategyLabEngine.heldAtStart(listOf(trade(aug17, null)), 0L).size)
    }
}
