package com.signalscope.app.trading

import com.signalscope.app.data.T212LiveStore.LiveTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SALE OF YOURS, SPREAD OVER THE APP'S POSITIONS IN ONE STOCK — your
 * watchlist's first, oldest first, then an algo's (the user's choice, 20 Oct
 * 2026). VBK that day: three watchlist lots and an account holding less than
 * the app believed, after buying and selling the stock several times by hand.
 */
class HandSaleOrderTest {
    private val day = 86_400_000L
    private val t0 = 1_792_400_000_000L
    private fun lot(id: String, ns: String, qty: Double, boughtDaysAgo: Int) =
        LiveTrade(paperTradeId = id, ns = ns, symbol = "VBK", t212Ticker = "VBKd_EQ", status = "OPEN",
            entryTs = 0L, paperEntryPrice = 28.0, amount = 100.0, qty = qty,
            liveQtyFilled = qty, buyAt = t0 - boughtDaysAgo * day)

    private val algoOld = lot("VBK-a", "de__buyx6g", 4.0, 5)
    private val watchOld = lot("VBK-1", "de__manual", 1.669, 2)
    private val watchMid = lot("VBK-2", "de__manual", 3.598, 1)
    private val watchNew = lot("VBK-3", "de__manual", 3.669, 0)
    private val all = listOf(watchNew, algoOld, watchMid, watchOld)

    @Test fun `your watchlist's positions come first, oldest first, then an algo's`() {
        assertEquals(listOf("VBK-1", "VBK-2", "VBK-3", "VBK-a"),
            T212HandSales.saleOrder(all).map { it.paperTradeId })
    }

    @Test fun `a sale smaller than the oldest lot trims it and touches nothing else`() {
        val cuts = T212HandSales.allocate(all, 1.0)
        assertEquals(1, cuts.size)
        assertEquals("VBK-1", cuts[0].row.paperTradeId)
        assertEquals(1.0, cuts[0].qty, 1e-9)
        assertTrue(!cuts[0].whole)
    }

    @Test fun `a sale of exactly the gap closes the oldest lot whole`() {
        // The VBK gap: the account held 1.669 fewer than the app's records.
        val cuts = T212HandSales.allocate(listOf(watchOld, watchMid, watchNew), 1.669)
        assertEquals(listOf("VBK-1"), cuts.map { it.row.paperTradeId })
        assertTrue(cuts[0].whole)
    }

    @Test fun `a larger sale uses up lots in order and trims the next`() {
        val cuts = T212HandSales.allocate(all, 6.0)
        assertEquals(listOf("VBK-1", "VBK-2", "VBK-3"), cuts.map { it.row.paperTradeId })
        assertEquals(listOf(true, true, false), cuts.map { it.whole })
        assertEquals(6.0 - 1.669 - 3.598, cuts[2].qty, 1e-9)
    }

    @Test fun `an algo's position is reached only when yours cannot cover the sale`() {
        val cuts = T212HandSales.allocate(all, 10.0)
        assertEquals("VBK-a", cuts.last().row.paperTradeId)
        assertEquals(10.0 - 1.669 - 3.598 - 3.669, cuts.last().qty, 1e-9)
        assertTrue(!cuts.last().whole)
    }

    @Test fun `a lot already partly sold counts only what it still holds`() {
        val trimmed = watchOld.copy(qty = 0.669, liveQtyFilled = 0.669, handSoldQty = 1.0)
        val cuts = T212HandSales.allocate(listOf(trimmed, watchMid), 1.0)
        assertEquals(listOf("VBK-1", "VBK-2"), cuts.map { it.row.paperTradeId })
        assertTrue(cuts[0].whole)
        assertEquals(1.0 - 0.669, cuts[1].qty, 1e-9)
    }
}
