package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore
import com.signalscope.app.data.FloorLiveStore
import com.signalscope.app.network.Trading212Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which paper trades Germany's MACD swing copies to Trading 212, and when it sells. */
class FloorLiveTest {

    private val on = 1_793_400_000_000L                 // real money switched on
    private fun t(id: String, firstSec: Long, status: String = "OPEN") = FloorAlgoStore.Trade(
        id = id, symbol = id.substringBefore("|"), day = "2026-11-02", firstSec = firstSec,
        firstPrice = if (firstSec > 0L) 100.0 else 0.0, status = status, qty = 0.5, cost = 50.0, halves = 1)
    private fun row(id: String, status: String, qty: Double = 0.25) =
        FloorLiveStore.Row(ns = "de__x", tradeId = id, symbol = id.substringBefore("|"), ticker = "X_EQ",
            status = status, qty = qty)

    @Test fun onlyBuysMadeAfterRealMoneyWentOnAreCopied() {
        val trades = listOf(
            t("OLD|1", on / 1000 - 60),                  // held on paper before — never bought
            t("NEW|2", on / 1000 + 900),                 // bought on paper after — copied
            t("WAIT|3", 0L),                             // turn seen, no price yet — not yet
            t("FULL|4", on / 1000 + 900, "MISSED"),      // seats were full on paper — never
            t("DONE|5", on / 1000 + 900, "SOLD"))        // already sold on paper — never
        assertEquals(listOf("NEW|2"), FloorLive.buysDue(trades, emptyList(), on).map { it.id })
        // A row of any kind means it was decided — bought, skipped or failed.
        assertTrue(FloorLive.buysDue(trades, listOf(row("NEW|2", FloorLiveStore.SKIPPED)), on).isEmpty())
        // Never switched on: nothing.
        assertTrue(FloorLive.buysDue(trades, emptyList(), 0L).isEmpty())
    }

    @Test fun aRealPositionSellsWhenItsPaperTradeDoes() {
        val trades = listOf(t("A|1", on / 1000, "SOLD"), t("B|2", on / 1000))
        val rows = listOf(row("A|1", FloorLiveStore.OPEN), row("B|2", FloorLiveStore.OPEN),
            row("C|3", FloorLiveStore.OPEN),             // its paper trade is gone from the book
            row("D|4", FloorLiveStore.SELLING),          // already selling
            row("E|5", FloorLiveStore.OPEN, qty = 0.0))  // nothing left to sell
        val due = FloorLive.salesDue(trades + t("D|4", on / 1000, "SOLD") + t("E|5", on / 1000, "SOLD"), rows)
        assertEquals(listOf("A|1", "C|3"), due.map { it.first.tradeId })
        assertNull(due.first { it.first.tradeId == "C|3" }.second)
    }

    @Test fun lateBuysAreNotChasedAndTheBudgetIsSpreadOverTheSeats() {
        val bought = t("A|1", on / 1000)
        assertFalse(FloorLive.tooLate(bought, on + 59 * 60_000L))
        assertTrue(FloorLive.tooLate(bought, on + 61 * 60_000L))
        assertEquals(25.0, FloorLive.perSeat(500.0, 20), 1e-9)
        assertEquals(0.0, FloorLive.perSeat(0.0, 20), 0.0)
    }

    @Test fun realPricesAreTheAverageOfTheRowsOwnFills() {
        fun f(id: Long, q: Double, px: Double) = Trading212Client.HistoricalFill(id, "X_EQ", q, px, q * px, "BUY", "", "FILLED")
        val fills = listOf(f(1, 0.1, 100.0), f(2, 0.3, 104.0), f(9, 5.0, 90.0))
        val (q, px) = FloorLive.fillOf(fills, listOf(1L, 2L))!!
        assertEquals(0.4, q, 1e-9)
        assertEquals((0.1 * 100 + 0.3 * 104) / 0.4, px, 1e-9)
        assertNull(FloorLive.fillOf(fills, listOf(7L)))
    }

    @Test fun onlyGermanMacdTurnsCanHoldRealMoney() {
        assertTrue(FloorLive.eligible(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_MACD, space = "de")))
        assertFalse(FloorLive.eligible(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_MACD, space = "us")))
        assertFalse(FloorLive.eligible(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_FLOOR, space = "de")))
        // The paper swings on your own portfolio never hold real money.
        assertFalse(FloorLive.eligible(FloorAlgoStore.Algo(kind = FloorAlgoStore.KIND_MACD, space = "de",
            settings = FloorAlgoStore.Settings(universeMode = FloorAlgoStore.UNI_PORTFOLIO))))
        assertEquals(100.0, FloorAlgoStore.portfolioPerStock("de"), 0.0)
        assertEquals(10_000.0, FloorAlgoStore.portfolioPerStock("in"), 0.0)
    }

    @Test fun startingAfreshKeepsThePaperRecordAsALedgerAndEmptiesEverySeat() {
        val held = t("KGX|1", on / 1000 - 86_400)
        val sold = t("BMW|1", on / 1000 - 9 * 86_400, "SOLD").copy(sellDay = "2026-10-30", sellPrice = 70.0)
        val a = FloorAlgoStore.Algo(id = "x", kind = FloorAlgoStore.KIND_MACD, space = "de",
            settings = FloorAlgoStore.Settings(seats = 20, liveOn = true, liveSince = on),
            trades = listOf(sold.copy(day = "2026-10-20"), held.copy(day = "2026-10-30")))
        val b = FloorLive.afresh(a, on, "2026-11-02", "real money switched on")
        assertTrue(b.trades.isEmpty())                                   // every seat empty
        val l = b.paperLedgers.single()
        assertEquals(a.trades, l.trades)                                  // the record kept whole
        assertEquals("2026-10-20", l.fromDay)
        assertEquals("2026-11-02", l.toDay)
        assertEquals(a.settings, b.settings)                              // nothing else changes
        // Nothing held: nothing to set aside.
        assertEquals(b, FloorLive.afresh(b, on, "2026-11-02", "again"))
    }
}
