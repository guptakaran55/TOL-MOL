package com.signalscope.app.trading

import com.signalscope.app.network.Trading212Client.HistoricalFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A sale closed without its price is priced only from one certain match. */
class ExitFillBackfillTest {
    private fun fill(id: Long, side: String, qty: Double, px: Double, at: String, t: String = "CDW_US_EQ") =
        HistoricalFill(id, t, qty, px, qty * px, side, at, "FILLED")

    private val boughtAt = java.time.Instant.parse("2026-10-19T16:32:00Z").toEpochMilli()
    private val history = listOf(
        fill(1, "BUY", 2.949, 152.2, "2026-10-19T16:34:35Z"),
        fill(2, "SELL", 2.949, 160.5, "2026-10-27T13:42:13Z"))

    @Test fun theStopsSaleIsFound() {
        assertEquals(2L, T212LiveMirror.exitFillFor(history, "CDW_US_EQ", boughtAt, 2.949, emptySet())?.orderId)
    }

    @Test fun anythingLessCertainIsLeftUnpriced() {
        // Another row owns it.
        assertNull(T212LiveMirror.exitFillFor(history, "CDW_US_EQ", boughtAt, 2.949, setOf(2L)))
        // A different share count — your own shares, or a part sale.
        assertNull(T212LiveMirror.exitFillFor(history, "CDW_US_EQ", boughtAt, 1.5, emptySet()))
        // Sold before this position was bought.
        assertNull(T212LiveMirror.exitFillFor(history, "CDW_US_EQ",
            java.time.Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), 2.949, emptySet()))
        // Two candidates: no guessing.
        val two = history + fill(3, "SELL", 2.949, 161.0, "2026-10-28T14:00:00Z")
        assertNull(T212LiveMirror.exitFillFor(two, "CDW_US_EQ", boughtAt, 2.949, emptySet()))
    }
}
