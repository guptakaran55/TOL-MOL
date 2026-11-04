package com.signalscope.app.trading

import com.signalscope.app.data.FloorLiveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SALE OF YOURS THAT REACHES THE GERMAN BUILT-IN ALGOS' REAL SHARES — taken
 * off their positions oldest first, a position used up whole and the next one
 * trimmed (2 Nov 2026). Before, those shares were never taken off at all.
 */
class FloorHandSaleTest {
    private fun row(id: String, qty: Double, buyAt: Long) = FloorLiveStore.Row(
        ns = "de__m1", tradeId = id, symbol = "SZG", ticker = "SZGd_EQ",
        status = FloorLiveStore.OPEN, qty = qty, buyAt = buyAt)

    private val old = row("a", 2.0, 100L)
    private val mid = row("b", 3.0, 200L)
    private val new = row("c", 1.5, 300L)
    private val all = listOf(new, old, mid)

    @Test fun `oldest first, the first used up whole and the next trimmed`() {
        val cuts = T212HandSales.floorAllocate(all, 3.0)
        assertEquals(listOf("a" to 2.0, "b" to 1.0), cuts.map { it.first.tradeId to it.second })
    }

    @Test fun `nothing sold takes nothing`() {
        assertTrue(T212HandSales.floorAllocate(all, 0.0).isEmpty())
        assertTrue(T212HandSales.floorAllocate(all, -1.0).isEmpty())
    }

    @Test fun `a sale bigger than them all takes each whole and no more`() {
        val cuts = T212HandSales.floorAllocate(all, 99.0)
        assertEquals(listOf("a" to 2.0, "b" to 3.0, "c" to 1.5), cuts.map { it.first.tradeId to it.second })
    }

    @Test fun `a hair under a whole position counts as all of it`() {
        val cuts = T212HandSales.floorAllocate(listOf(old), 1.999)
        assertEquals(2.0, cuts.single().second, 1e-9)
    }
}
