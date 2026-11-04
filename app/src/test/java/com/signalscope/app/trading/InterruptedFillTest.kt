package com.signalscope.app.trading

import com.signalscope.app.network.Trading212Client.HistoricalFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A watchlist buy cut off by the app stopping: only the fills made while it ran are its own. */
class InterruptedFillTest {
    private fun f(id: Long, side: String, qty: Double, px: Double, at: String) =
        HistoricalFill(id, "AIRd_EQ", qty, px, qty * px, side, at, "FILLED")

    // AIR, 29 Oct 2026: the buy began at 14:31 (13:31 UTC) and the app was killed a minute later.
    private val began = java.time.Instant.parse("2026-10-29T13:31:00Z").toEpochMilli()
    private val history = listOf(
        f(59302019020, "SELL", 1.042, 198.84, "2026-10-29T08:05:11Z"),   // a sale that morning
        f(59601949265, "BUY", 0.254, 196.98, "2026-10-29T13:31:32Z"),    // the cut-off buy
        f(59601912229, "BUY", 2.0, 194.86, "2026-10-30T08:00:29Z"))      // another order, next morning

    @Test fun theBuysOwnFillIsFound() {
        val got = ManualEntryWatcher.fillsOfInterrupted(history, "AIRd_EQ", began, emptySet())
        assertEquals(listOf(59601949265L), got.map { it.orderId })
    }

    @Test fun anOrderAnotherRecordOwnsIsNotTaken() {
        assertTrue(ManualEntryWatcher.fillsOfInterrupted(history, "AIRd_EQ", began, setOf(59601949265L)).isEmpty())
    }

    @Test fun anotherStockOrALaterBuyIsNotTaken() {
        assertTrue(ManualEntryWatcher.fillsOfInterrupted(history, "SAPd_EQ", began, emptySet()).isEmpty())
        val nextDay = java.time.Instant.parse("2026-10-30T07:50:00Z").toEpochMilli()
        assertEquals(listOf(59601912229L),
            ManualEntryWatcher.fillsOfInterrupted(history, "AIRd_EQ", nextDay, emptySet()).map { it.orderId })
    }
}
