package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which fills a waiting India buy turned into, and what its shares sold for. */
class ZerodhaSettleTest {

    private fun f(id: String, order: String, side: String, qty: Double, px: Double, at: Long, sym: String = "RAY") =
        ZerodhaSettle.Fill(id, order, sym, side, qty, px, at)

    private val fills = listOf(
        f("1", "o-hand", "BUY", 5.0, 100.0, 1_000),      // a buy you made by hand
        f("2", "o-own", "BUY", 11.0, 101.0, 2_000),      // another row's own order
        f("3", "o-trig", "BUY", 4.0, 102.0, 3_000),      // a trigger's order, in two fills
        f("4", "o-trig", "BUY", 8.0, 104.0, 3_000),
        f("5", "o-s1", "SELL", 11.0, 110.0, 5_000),
        f("6", "o-s2", "SELL", 5.0, 120.0, 6_000))

    @Test fun anOrderIsMatchedByItsIdAndAveraged() {
        val b = ZerodhaSettle.boughtFor(fills, "RAY", 0, 12, "o-trig", emptySet(), anyQty = true)!!
        assertEquals("o-trig", b.orderId)
        assertEquals(12, b.qty)
        assertEquals((4 * 102.0 + 8 * 104.0) / 12, b.price, 1e-9)
    }

    @Test fun withoutAnIdOnlyTheExactShareCountAfterThePlacementCounts() {
        // 5 shares bought by hand are never taken for an 11-share trigger.
        assertNull(ZerodhaSettle.boughtFor(fills, "RAY", 0, 7, null, emptySet(), anyQty = false))
        // An order another row owns is skipped even at the right size.
        assertNull(ZerodhaSettle.boughtFor(fills, "RAY", 0, 11, null, setOf("o-own"), anyQty = false))
        assertEquals("o-trig", ZerodhaSettle.boughtFor(fills, "RAY", 0, 12, null, setOf("o-own"), anyQty = false)!!.orderId)
        // Nothing bought before the row was placed (a minute's grace aside).
        assertNull(ZerodhaSettle.boughtFor(fills, "RAY", 3_000 + 61_000, 12, null, emptySet(), anyQty = false))
    }

    @Test fun salesAreSharedOutFirstInFirstOut() {
        val used = HashMap<String, Double>()
        val a = ZerodhaSettle.soldFor(fills, "RAY", 2_000, 5, used)!!
        assertEquals(110.0, a.first, 1e-9)
        // The next row gets the 6 left of the first sale and 5 of the second.
        val b = ZerodhaSettle.soldFor(fills, "RAY", 2_000, 11, used)!!
        assertEquals((6 * 110.0 + 5 * 120.0) / 11, b.first, 1e-9)
        assertEquals(6_000L, b.second)
        // Nothing left to cover a third.
        assertNull(ZerodhaSettle.soldFor(fills, "RAY", 2_000, 1, used))
    }

    @Test fun salesBeforeTheBuyDoNotCount() {
        assertNull(ZerodhaSettle.soldFor(fills, "RAY", 5_500, 11, HashMap()))
        assertNull(ZerodhaSettle.soldFor(fills, "OTHER", 0, 1, HashMap()))
    }

    // ── written off, still held ──

    private val day = 24 * 3600_000L
    private fun gone(error: String, closedAt: Long, status: String = "MANUAL_CLOSED") =
        com.signalscope.app.data.ZerodhaLiveStore.Row(ns = "in__manual", paperTradeId = "SAILIFE-1",
            symbol = "SAILIFE", status = status, qty = 4, entryPrice = 1476.0, manualClosedAt = closedAt, error = error)

    @Test fun onlyRecordsTheOutsideSaleChecksClosedAreLookedAtAgain() {
        val now = 20 * day
        // The watchlist's check and the algo's check — both looked at again.
        assertTrue(ZerodhaSettle.closedAsGone(gone("not held at the broker — closed outside the app", now - day), now))
        assertTrue(ZerodhaSettle.closedAsGone(gone("Closed: no longer held at Zerodha and no sale on record — sold outside the app.", now - day), now))
        // Any other close, a record already marked still held, or one past the window — never.
        assertFalse(ZerodhaSettle.closedAsGone(gone("Closed: no longer held at Zerodha — the protective trigger (GTT 1) could not be described by Zerodha.", now - day), now))
        assertFalse(ZerodhaSettle.closedAsGone(gone(ZerodhaSettle.STILL_HELD, now - day), now))
        assertFalse(ZerodhaSettle.closedAsGone(gone("not held at the broker — closed outside the app", now - 11 * day), now))
        assertFalse(ZerodhaSettle.closedAsGone(gone("not held at the broker — closed outside the app", now - day, status = "CLOSED"), now))
    }

    @Test fun sharesStillSettlingCountAsHeld() {
        // WESTLIFE on 30 Oct 2026: bought the day before, nothing settled yet.
        val o = com.google.gson.JsonParser.parseString("""{"quantity":0,"t1_quantity":16}""").asJsonObject
        assertEquals(16, com.signalscope.app.network.ZerodhaClient.heldQty(o))
        val mixed = com.google.gson.JsonParser.parseString("""{"quantity":5,"t1_quantity":3}""").asJsonObject
        assertEquals(8, com.signalscope.app.network.ZerodhaClient.heldQty(mixed))
        val old = com.google.gson.JsonParser.parseString("""{"quantity":4}""").asJsonObject
        assertEquals(4, com.signalscope.app.network.ZerodhaClient.heldQty(old))
    }
}
