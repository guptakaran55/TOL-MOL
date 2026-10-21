package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARGES ON A TRADE THAT ALREADY HAPPENED.
 *
 * Separate from [TradingCostsTest], which pins the RATES. This pins the money:
 * given two real legs, what did the account actually pay?
 *
 * The two views are derived from one rate card on purpose, so the last assertion
 * here checks that they still agree — a charge that appears in the forward quote
 * on an algo's card but not in the realised P&L, or the reverse, is exactly the
 * failure this file exists to prevent.
 */
class TradingChargesTest {

    private val EUR = "EUR"

    // ── India ───────────────────────────────────────────────────────────────

    @Test fun `a flat Indian round trip costs what the rate card says`() {
        // Bought 12,000 and sold 12,000 — the symmetric case the forward quote
        // assumes, so it must reproduce the per-seat figure exactly.
        val c = TradingCosts.chargesFor("in__b8alhc", 12_000.0, 12_000.0, EUR)
        val by = c.components.associateBy { it.label }
        assertEquals("STT, both legs", 24.0, by.getValue("STT").cash, 1e-9)
        assertEquals("stamp duty, buy only", 1.80, by.getValue("stamp duty").cash, 1e-9)
        assertEquals(0.804, by.getValue("exchange").cash, 1e-9)
        assertEquals(0.024, by.getValue("SEBI").cash, 1e-9)
        assertEquals("flat, sell only", 13.5, by.getValue("DP charge").cash, 1e-9)
        assertEquals("18% of the service charges only",
            (0.804 + 0.024 + 13.5) * 0.18, by.getValue("GST").cash, 1e-9)
        assertEquals(42.70704, c.cashRoundTrip, 1e-5)
    }

    @Test fun `the two legs are charged on their own values, not averaged`() {
        // A winner sells more than it bought, so it pays MORE transaction tax than
        // a per-seat estimate would suggest. Averaging the legs understates a good
        // trade's cost and overstates a bad one's.
        val won = TradingCosts.chargesFor("in__x", 12_000.0, 14_000.0, EUR)
        val lost = TradingCosts.chargesFor("in__x", 12_000.0, 10_000.0, EUR)
        val flat = TradingCosts.chargesFor("in__x", 12_000.0, 12_000.0, EUR)
        assertTrue("selling higher costs more", won.cashRoundTrip > flat.cashRoundTrip)
        assertTrue("selling lower costs less", lost.cashRoundTrip < flat.cashRoundTrip)
        // The gap is every turnover-scaled charge on the extra 2,000 of sell
        // value — the transaction tax dominates it, but the exchange and SEBI
        // fees and the GST riding on them move too. Spelled out rather than
        // approximated, so a rate change has to be acknowledged here.
        val extra = 2_000.0
        val expected = (TradingCosts.ZERODHA_STT_PCT_PER_SIDE +
                        TradingCosts.ZERODHA_EXCHANGE_PCT_PER_SIDE +
                        TradingCosts.ZERODHA_SEBI_PCT_PER_SIDE) / 100.0 * extra +
                       (TradingCosts.ZERODHA_EXCHANGE_PCT_PER_SIDE +
                        TradingCosts.ZERODHA_SEBI_PCT_PER_SIDE) / 100.0 * extra * TradingCosts.GST_RATE
        assertEquals(expected, won.cashRoundTrip - flat.cashRoundTrip, 1e-9)
    }

    @Test fun `the fixed fee dominates a small trade and vanishes on a large one`() {
        val small = TradingCosts.chargesFor("in__x", 2_000.0, 2_000.0, EUR)
        val large = TradingCosts.chargesFor("in__x", 500_000.0, 500_000.0, EUR)
        assertEquals(1.0196, small.cashRoundTrip / 2_000.0 * 100.0, 1e-4)
        assertEquals(0.2263, large.cashRoundTrip / 500_000.0 * 100.0, 1e-4)
        assertTrue("a small seat pays four times the percentage",
            small.cashRoundTrip / 2_000.0 > 4 * large.cashRoundTrip / 500_000.0)
    }

    @Test fun `a trade with no sell leg is charged only what the buy incurred`() {
        // Guards the shape of a half-finished round trip reaching this by mistake:
        // it must not levy the depository fee for a sale that never happened.
        val c = TradingCosts.chargesFor("in__x", 12_000.0, 0.0, EUR)
        val by = c.components.associateBy { it.label }
        assertEquals("STT on the buy alone", 12.0, by.getValue("STT").cash, 1e-9)
        assertTrue("no depository fee without a sale",
            c.components.none { it.label == "DP charge" })
    }

    // ── Trading 212 ─────────────────────────────────────────────────────────

    @Test fun `a euro account pays conversion on both legs of a US trade`() {
        val c = TradingCosts.chargesFor("us__growth", 1_000.0, 1_100.0, EUR)
        assertEquals("0.15% of each leg's own value",
            0.15 / 100 * 1_000.0 + 0.15 / 100 * 1_100.0, c.cashRoundTrip, 1e-9)
    }

    @Test fun `a German trade on a euro account costs nothing at all`() {
        val c = TradingCosts.chargesFor("de__value", 1_000.0, 1_100.0, EUR)
        assertTrue(c.components.isEmpty())
        assertEquals(0.0, c.cashRoundTrip, 1e-9)
    }

    @Test fun `crypto is charged nothing because nothing is modelled`() {
        val c = TradingCosts.chargesFor("binance__btc", 1_000.0, 1_100.0, EUR)
        assertEquals(0.0, c.cashRoundTrip, 1e-9)
    }

    // ── the two views must not drift ────────────────────────────────────────

    @Test fun `the forward quote and the realised charge agree on a flat trip`() {
        // THE WHOLE REASON THE RATE CARD IS WRITTEN ONCE. An algo's card quotes a
        // percentage before any trade exists; the P&L panel charges money after
        // one has. If a charge were added to one path and not the other, the same
        // trade would cost two different amounts depending on which screen you
        // were looking at — and both would look authoritative.
        listOf("in__x", "us__x", "de__x", "binance__x").forEach { ns ->
            val seat = 20_000.0
            val quotedPct = TradingCosts.brokerCosts(ns, EUR).totalPct(seat)
            val chargedPct = TradingCosts.chargesFor(ns, seat, seat, EUR).cashRoundTrip / seat * 100.0
            assertEquals(ns, quotedPct, chargedPct, 1e-9)
        }
    }

    // ── what the panel shows ───────────────────────────────────────────────

    @Test fun `a round trip reports gross, charges and net separately`() {
        val (_, rep) = ClosedTrades.match(listOf(
            ClosedTrades.Fill("SHOPERSTOP", "SHOPERSTOP", 29.0, 413.40, 29 * 413.40, "BUY", "2026-09-15T04:00:00"),
            ClosedTrades.Fill("SHOPERSTOP", "SHOPERSTOP", 29.0, 430.00, 29 * 430.00, "SELL", "2026-09-17T05:00:00")))
        val costed = ClosedTrades.costed(rep, "in__b8alhc", "EUR")
        val t = costed.trips.single()
        assertEquals("gross is untouched", 29 * (430.00 - 413.40), t.pnl, 1e-6)
        assertTrue("charges were applied", t.charges > 0.0)
        assertEquals("net is gross minus charges", t.pnl - t.charges, t.netPnl, 1e-9)
        assertTrue("net is the smaller of the two", t.netPnl < t.pnl)
        assertEquals(t.charges, costed.totalCharges, 1e-9)
        assertTrue("and the panel may claim a net figure", costed.costed)
    }

    @Test fun `an uncosted market never claims a net figure`() {
        // Crypto has no price list modelled. Showing "net" there would read as
        // "these trades cost nothing", which is never true of any exchange.
        val (_, rep) = ClosedTrades.match(listOf(
            ClosedTrades.Fill("BTCUSDC", "BTCUSDC", 1.0, 100.0, 100.0, "BUY", "2026-09-01T00:00:00"),
            ClosedTrades.Fill("BTCUSDC", "BTCUSDC", 1.0, 110.0, 110.0, "SELL", "2026-09-02T00:00:00")))
        val costed = ClosedTrades.costed(rep, "binance__btc", "EUR")
        assertTrue("the panel must fall back to gross", !costed.costed)
        assertEquals(0.0, costed.totalCharges, 1e-9)
    }

    @Test fun `a thin winner can be green before charges and red after`() {
        // The case the net column exists for: a 2,000 position up 20 rupees is a
        // profit on the screen and a loss in the account, because the fixed fee
        // alone is 15.93.
        val (_, rep) = ClosedTrades.match(listOf(
            ClosedTrades.Fill("X", "X", 10.0, 200.0, 2_000.0, "BUY", "2026-09-01T04:00:00"),
            ClosedTrades.Fill("X", "X", 10.0, 202.0, 2_020.0, "SELL", "2026-09-02T04:00:00")))
        val t = ClosedTrades.costed(rep, "in__x", "EUR").trips.single()
        assertEquals(20.0, t.pnl, 1e-9)
        assertTrue("gross says a win", t.pnl > 0.0)
        assertTrue("the account says a loss", t.netPnl < 0.0)
    }
}
