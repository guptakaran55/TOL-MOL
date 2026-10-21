package com.signalscope.app.trading

import com.signalscope.app.data.ZerodhaTradeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Indian half of the closed-trade analysis.
 *
 * The matching itself is the shared engine and is pinned by the Trading 212
 * tests, so what is asserted here is the two things that are genuinely India's:
 * the timestamp translation, and the fact that rupee fills need no cash/price
 * reconciliation because the account settles in the instrument's own currency.
 */
class ZerodhaClosedTradesTest {

    private fun buy(sym: String, q: Double, p: Double, at: String) =
        ClosedTrades.Fill(sym, sym, q, p, q * p, "BUY", at)

    private fun sell(sym: String, q: Double, p: Double, at: String) =
        ClosedTrades.Fill(sym, sym, q, p, q * p, "SELL", at)

    // ── the timestamp ───────────────────────────────────────────────────────

    @Test fun `a Kite fill time becomes a sortable UTC stamp`() {
        // Kite answers in IST with a space, and every other time this app keeps is
        // UTC ISO. Left as-is, the matcher would sort two formats against each
        // other and pair the wrong buys with the wrong sells.
        assertEquals("2026-09-17T04:34:11", ZerodhaTradeStore.isoOf("2026-09-17 10:04:11"))
        assertEquals("2026-09-17T03:45:00", ZerodhaTradeStore.isoOf("2026-09-17 09:15:00"))
    }

    @Test fun `a fill just after midnight IST lands on the previous UTC day`() {
        // The one case a naive "chop the time off" conversion gets wrong.
        assertEquals("2026-09-16T18:45:00", ZerodhaTradeStore.isoOf("2026-09-17 00:15:00"))
    }

    @Test fun `an unparseable stamp is passed through rather than invented`() {
        // A guessed timestamp reorders trades silently; a visibly odd one does not.
        assertEquals("not-a-time", ZerodhaTradeStore.isoOf("not-a-time"))
    }

    @Test fun `translated stamps sort in real chronological order`() {
        val morning = ZerodhaTradeStore.isoOf("2026-09-17 09:20:00")
        val afternoon = ZerodhaTradeStore.isoOf("2026-09-17 15:10:00")
        val nextDay = ZerodhaTradeStore.isoOf("2026-09-18 09:20:00")
        assertTrue(morning < afternoon)
        assertTrue(afternoon < nextDay)
    }

    // ── the matching, on rupee fills ────────────────────────────────────────

    @Test fun `a plain round trip reports the rupees it made`() {
        val (trips, r) = ClosedTrades.match(listOf(
            buy("SHOPERSTOP", 23.0, 413.40, "2026-09-15T04:00:00"),
            sell("SHOPERSTOP", 23.0, 430.00, "2026-09-17T05:00:00")))
        assertEquals(1, trips.size)
        assertEquals(23.0 * (430.00 - 413.40), trips[0].pnl, 1e-6)
        assertEquals(2, trips[0].heldDays)
        assertEquals(1, r.wins)
        assertEquals(0, r.losses)
    }

    @Test fun `a partial sell closes only what it sold`() {
        val (trips, r) = ClosedTrades.match(listOf(
            buy("TATASTEEL", 100.0, 180.0, "2026-09-10T04:00:00"),
            sell("TATASTEEL", 40.0, 190.0, "2026-09-12T04:00:00")))
        assertEquals(1, trips.size)
        assertEquals(40.0, trips[0].qty, 1e-9)
        assertEquals(400.0, trips[0].pnl, 1e-6)
        // The other 60 are still held and must not be valued at anything.
        assertEquals(60.0, r.stillOpen["TATASTEEL"]!!, 1e-9)
        assertEquals(400.0, r.totalPnl, 1e-6)
    }

    @Test fun `oldest shares go first when two lots exist`() {
        val (trips, _) = ClosedTrades.match(listOf(
            buy("INFY", 10.0, 100.0, "2026-09-01T04:00:00"),
            buy("INFY", 10.0, 120.0, "2026-09-05T04:00:00"),
            sell("INFY", 15.0, 130.0, "2026-09-09T04:00:00")))
        assertEquals("one lot and a piece of the next", 2, trips.size)
        assertEquals(100.0, trips.first { it.qty == 10.0 }.buyPrice, 1e-9)
        assertEquals(120.0, trips.first { it.qty == 5.0 }.buyPrice, 1e-9)
        // 10 × 30 + 5 × 10
        assertEquals(350.0, trips.sumOf { it.pnl }, 1e-6)
    }

    @Test fun `a sell whose buy predates the record is reported, never guessed`() {
        // The normal state of this account for a while: the store starts the day
        // the app does, so early sells have no matching buy. Counting them against
        // a made-up entry price would invent profit.
        val (trips, r) = ClosedTrades.match(listOf(
            sell("RELIANCE", 5.0, 1400.0, "2026-09-17T05:00:00")))
        assertTrue(trips.isEmpty())
        assertEquals(5.0, r.unmatchedSells["RELIANCE"]!!, 1e-9)
        assertEquals(0.0, r.totalPnl, 1e-9)
    }

    @Test fun `rupee fills need no currency reconciliation`() {
        // The Trading 212 side second-guesses cash against price because a dollar
        // fill on a euro account makes the two disagree. A rupee account holding
        // rupee stock cannot, so the cash figure must survive untouched — if the
        // sanity check fired here it would silently swap in price × quantity.
        assertEquals(413.40, ClosedTrades.cashPerShare(23.0 * 413.40, 23.0, 413.40), 1e-9)
        assertEquals(1400.0, ClosedTrades.cashPerShare(5.0 * 1400.0, 5.0, 1400.0), 1e-9)
    }

    @Test fun `the report says how far back its fills reach`() {
        val (_, r) = ClosedTrades.match(listOf(
            buy("A", 1.0, 10.0, "2026-09-01T04:00:00"),
            sell("A", 1.0, 11.0, "2026-09-05T04:00:00")))
        assertEquals("2026-09-01T04:00:00", r.firstFillAt)
        assertEquals("2026-09-05T04:00:00", r.lastFillAt)
    }
}
