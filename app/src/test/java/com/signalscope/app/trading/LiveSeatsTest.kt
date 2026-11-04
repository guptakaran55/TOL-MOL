package com.signalscope.app.trading

import com.signalscope.app.data.BinanceLiveStore
import com.signalscope.app.data.T212LiveStore.LiveTrade
import com.signalscope.app.data.ZerodhaLiveStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIVE SEATS GROW WITH THE ALGO'S REAL RESULT, up to twice where they started —
 * the gap between the compounding backtests and fixed-size real orders (23 Oct 2026).
 */
class LiveSeatsTest {
    private val start = 1_792_770_000_000L
    private val ns = "us__b148ki0"

    private fun sold(sym: String, cost: Double, got: Double, at: Long = start + 60_000L,
                     status: String = "CLOSED", rowNs: String = ns) =
        LiveTrade(paperTradeId = "$sym-1", ns = rowNs, symbol = sym, t212Ticker = "${sym}_US_EQ", status = status,
            entryTs = 1L, paperEntryPrice = 100.0, amount = cost, qty = 2.0, sellAt = at,
            liveEntryPrice = cost / 2.0, liveQtyFilled = 2.0, liveEntryValue = cost,
            liveExitPrice = got / 2.0, liveExitQty = 2.0, liveExitValue = got)

    @Test fun `a seat is the budget plus the real result, shared over the seats`() {
        assertEquals(400.0, LiveSeats.perSeat(2000.0, 5, 0.0), 1e-9)
        assertEquals(420.0, LiveSeats.perSeat(2000.0, 5, 100.0), 1e-9)
        assertEquals(380.0, LiveSeats.perSeat(2000.0, 5, -100.0), 1e-9)
    }

    @Test fun `never above twice the starting seat, never below nothing`() {
        assertEquals(800.0, LiveSeats.perSeat(2000.0, 5, 5000.0), 1e-9)
        assertEquals(0.0, LiveSeats.perSeat(2000.0, 5, -3000.0), 1e-9)
        assertEquals(0.0, LiveSeats.perSeat(0.0, 5, 100.0), 1e-9)
    }

    @Test fun `only real sales since the start count, and only this algo's`() {
        val rows = listOf(
            sold("TEAM", 401.42, 425.69),                          // +24.27
            sold("PANW", 400.0, 380.0),                            // −20
            sold("OLD", 400.0, 600.0, at = start - 1),             // before the start
            sold("GEHC", 400.0, 500.0, rowNs = "us__other"),       // another algo
            sold("SAP", 200.0, 210.0, status = "OPEN"))            // not sold
        val r = LiveSeats.t212Realised(rows, ns, start)
        assertEquals(4.27, r.pnl, 1e-9)
        assertEquals(2, r.trades)
        assertEquals(0, r.unread)
    }

    @Test fun `a sale whose amount is not read yet waits, it is not guessed`() {
        val r = LiveSeats.t212Realised(listOf(sold("TEAM", 401.42, 0.0)), ns, start)
        assertEquals(0.0, r.pnl, 1e-9)
        assertEquals(0, r.trades)
        assertEquals(1, r.unread)
    }

    @Test fun `a sale that left shares behind is measured on the shares it sold`() {
        val half = sold("TEAM", 400.0, 210.0).copy(liveExitQty = 1.0)
        assertEquals(10.0, LiveSeats.t212Realised(listOf(half), ns, start).pnl, 1e-9)
    }

    @Test fun `crypto and India read their own ledgers`() {
        val b = BinanceLiveStore.LiveTrade(paperTradeId = "BTC-1", symbol = "BTCUSDC", status = "CLOSED",
            entryTs = 1L, paperEntryPrice = 1.0, quoteOrderQty = 65.0, sellOrderId = 9L, sellAt = start + 1,
            sellQuoteQty = "70.0", livePnlQuote = 5.0)
        assertEquals(5.0, LiveSeats.binanceRealised(listOf(b), start).pnl, 1e-9)
        val z = ZerodhaLiveStore.Row(ns = "in__x", symbol = "INFY", status = "CLOSED", qty = 10,
            entryPrice = 100.0, exitPrice = 104.0, sellAt = start + 1)
        assertEquals(40.0, LiveSeats.zerodhaRealised(listOf(z), "in__x", start).pnl, 1e-9)
    }

    @Test fun `each seat shows its company and whether real money is in it`() {
        // 23 Oct 2026, Us conservative: three bought for real, two waiting.
        val t = LiveSeats.tiles(listOf("WDAY", "ABNB", "CDW", "TEAM", "CTSH"), 5,
            live = setOf("ABNB", "CDW", "WDAY"), paperOpen = setOf("CTSH"))
        assertEquals(listOf("LIVE", "LIVE", "LIVE", "WAITING", "WAITING"), t.map { it.state })
        assertEquals(true, t[4].paperHeld)
    }

    @Test fun `cash seats, missing seats and a position on no seat`() {
        val t = LiveSeats.tiles(listOf("SAP", null), 4, live = setOf("SAP", "GXI"), flagged = setOf("SAP"))
        assertEquals(listOf("LIVE", "EMPTY", "EMPTY", "EMPTY", "LIVE"), t.map { it.state })
        assertEquals(true, t[0].flagged)
        assertEquals("GXI", t[4].symbol)
        assertEquals(-1, t[4].seat)
    }

    @Test fun `a paper algo's seats hold its paper positions`() {
        val t = LiveSeats.tiles(listOf("TKA", "NEM", null), 3, live = emptySet(),
            paperOpen = setOf("TKA", "AOF"), paperCounts = true)
        assertEquals(listOf("PAPER", "WAITING", "EMPTY", "PAPER"), t.map { it.state })
        assertEquals("AOF", t[3].symbol)
        // On a real-money algo the same paper position is not a holding.
        assertEquals("WAITING", LiveSeats.tiles(listOf("TKA"), 1, emptySet(), paperOpen = setOf("TKA"))[0].state)
    }
}
