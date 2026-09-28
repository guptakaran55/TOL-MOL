package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BROKERS' PRICE LISTS, PINNED TO NUMBERS.
 *
 * Trading cost is the one assumption that can flip a champion into a loser
 * without changing a single entry or exit, and it is invisible: it arrives as one
 * blended percentage that no screen used to decompose. A phantom currency charge
 * lived inside the Indian figure for months precisely because the total was
 * arithmetically self-consistent and nobody could take it apart.
 *
 * So every rate is asserted individually AND the totals are asserted, which means
 * a rate change has to be made deliberately: edit the constant, watch this fail,
 * update the total. Silent drift is the failure mode being defended against.
 */
class TradingCostsTest {

    private val EUR = "EUR"

    // ── Germany ─────────────────────────────────────────────────────────────

    @Test fun `a euro account trading German shares pays the broker nothing`() {
        val b = TradingCosts.brokerCosts("de__value", EUR)
        assertTrue("no charge should be listed at all", b.components.isEmpty())
        assertEquals(0.0, b.pctRoundTrip, 1e-9)
        assertEquals(0.0, b.cashRoundTrip, 1e-9)
        assertEquals(0.0, b.totalPct(1000.0), 1e-9)
    }

    // ── US ──────────────────────────────────────────────────────────────────

    @Test fun `a euro account trading US shares pays conversion twice`() {
        val b = TradingCosts.brokerCosts("us__growth", EUR)
        assertEquals(1, b.components.size)
        assertEquals("FX conversion", b.components[0].label)
        // 0.15% per execution, and a round trip executes twice.
        assertEquals(0.30, b.pctRoundTrip, 1e-9)
        assertEquals("nothing fixed", 0.0, b.cashRoundTrip, 1e-9)
        // Position size cannot change a purely proportional charge.
        assertEquals(0.30, b.totalPct(200.0), 1e-9)
        assertEquals(0.30, b.totalPct(200_000.0), 1e-9)
    }

    @Test fun `a dollar account trading US shares converts nothing`() {
        val b = TradingCosts.brokerCosts("us__growth", "USD")
        assertTrue(b.components.isEmpty())
        assertEquals(0.0, b.pctRoundTrip, 1e-9)
    }

    // ── India ───────────────────────────────────────────────────────────────

    @Test fun `each Zerodha delivery charge is what the rate card says`() {
        val b = TradingCosts.brokerCosts("in__b8alhc", EUR)
        val by = b.components.associateBy { it.label }
        // Brokerage is free on delivery, so it is not listed at all.
        assertTrue("free brokerage must not appear as a charge", !by.containsKey("brokerage"))
        // STT: BOTH legs on delivery. The sell-side-only figure is the INTRADAY
        // rule and using it here is a 0.1% error on every round trip.
        assertEquals(0.20, by.getValue("STT").pct, 1e-9)
        assertEquals("buy side only", 0.015, by.getValue("stamp duty").pct, 1e-9)
        assertEquals(0.00670, by.getValue("exchange").pct, 1e-9)
        assertEquals(0.00020, by.getValue("SEBI").pct, 1e-9)
        // The depository fee is CASH, on the sell leg only — once per round trip,
        // not twice like a brokerage.
        assertEquals(13.5, by.getValue("DP charge").cash, 1e-9)
        assertEquals("and it does not scale with turnover", 0.0, by.getValue("DP charge").pct, 1e-9)
        // GST rides on the service charges and never on the taxes.
        assertEquals((0.00670 + 0.00020) * 0.18, by.getValue("GST").pct, 1e-12)
        assertEquals(13.5 * 0.18, by.getValue("GST").cash, 1e-9)
    }

    @Test fun `a euro account trading Indian shares converts nothing`() {
        // A rupee account funded from a rupee bank. The conversion happened once,
        // when money was moved in; it is not a cost any trade carries.
        val b = TradingCosts.brokerCosts("in__b8alhc", EUR)
        assertTrue("no FX line may appear for India",
            b.components.none { it.label.contains("FX", true) })
    }

    @Test fun `the Indian round trip on a 30000 rupee seat`() {
        // The real algo: 150,000 over 5 seats.
        val seat = 150_000.0 / 5
        val b = TradingCosts.brokerCosts("in__b8alhc", EUR)
        // Turnover-scaled part.
        assertEquals(0.2231420, b.pctRoundTrip, 1e-7)
        // Fixed part: the depository fee plus its GST.
        assertEquals(15.93, b.cashRoundTrip, 1e-9)
        assertEquals(0.0531, b.cashRoundTrip / seat * 100.0, 1e-4)
        assertEquals("everything the broker takes", 0.276242, b.totalPct(seat), 1e-6)
    }

    @Test fun `the fixed charge is punishing on a small seat and trivial on a large one`() {
        // The entire reason the depository fee is modelled as cash and converted
        // here, where the seat size is known, rather than baked in as a percentage.
        val b = TradingCosts.brokerCosts("in__b8alhc", EUR)
        val small = b.totalPct(2_000.0)
        val large = b.totalPct(500_000.0)
        assertEquals(0.2231420 + 15.93 / 2_000.0 * 100.0, small, 1e-6)
        assertTrue("a 2,000 seat pays far more proportionally", small > 1.0)
        assertTrue("a 500,000 seat barely notices it", large < 0.23)
    }

    // ── Crypto ──────────────────────────────────────────────────────────────

    @Test fun `crypto is left unmodelled rather than guessed`() {
        val b = TradingCosts.brokerCosts("binance__btc", EUR)
        assertTrue("a plausible default is still a number nobody chose",
            b.components.isEmpty())
        assertEquals(0.0, b.totalPct(1000.0), 1e-9)
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test fun `the listed components always add up to the stated totals`() {
        // The defence against a line being shown that is not charged, or charged
        // and not shown — which is the failure this whole breakdown exists to stop.
        listOf("de__x", "us__x", "in__x", "binance__x").forEach { ns ->
            val b = TradingCosts.brokerCosts(ns, EUR)
            assertEquals(ns, b.components.sumOf { it.pct }, b.pctRoundTrip, 1e-12)
            assertEquals(ns, b.components.sumOf { it.cash }, b.cashRoundTrip, 1e-12)
            assertTrue("$ns lists a zero charge", b.components.all { it.pct > 0.0 || it.cash > 0.0 })
        }
    }
}
