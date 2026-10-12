package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT AN INDIAN TRADE ACTUALLY COSTS — the depository fee once per sale, and a
 * same-day trade on the intraday card.
 */
class ZerodhaChargesTest {

    private fun fill(side: String, qty: Double, price: Double, at: String) =
        ClosedTrades.Fill(ticker = "RAYMOND", symbol = "RAYMOND", qty = qty, price = price,
            cash = qty * price, side = side, at = at)

    private fun costed(fills: List<ClosedTrades.Fill>): ClosedTrades.Report =
        ClosedTrades.costed(ClosedTrades.match(fills).second, "in", "INR")

    @Test fun `one sale filled in three parts pays the depository fee once`() {
        val rep = costed(listOf(
            fill("BUY", 10.0, 100.0, "2026-09-21T10:00:00"),
            fill("SELL", 4.0, 110.0, "2026-09-24T11:00:00"),
            fill("SELL", 3.0, 110.0, "2026-09-24T11:00:05"),
            fill("SELL", 3.0, 110.0, "2026-09-24T11:00:09")))
        assertEquals(3, rep.trips.size)
        val dp = rep.trips.sumOf { it.chargeParts?.get("DP charge") ?: 0.0 }
        assertEquals(TradingCosts.ZERODHA_DP_CASH_SELL_ONLY, dp, 1e-9)
        assertTrue(rep.trips.none { it.sameDay })
    }

    @Test fun `two companies sold the same day each pay their own fee`() {
        val f = listOf(
            fill("BUY", 10.0, 100.0, "2026-09-21T10:00:00"),
            ClosedTrades.Fill("SYRMA", "SYRMA", 5.0, 200.0, 1000.0, "BUY", "2026-09-21T10:01:00"),
            fill("SELL", 10.0, 105.0, "2026-09-24T11:00:00"),
            ClosedTrades.Fill("SYRMA", "SYRMA", 5.0, 210.0, 1050.0, "SELL", "2026-09-24T11:01:00"))
        val rep = costed(f)
        val dp = rep.trips.sumOf { it.chargeParts?.get("DP charge") ?: 0.0 }
        assertEquals(2 * TradingCosts.ZERODHA_DP_CASH_SELL_ONLY, dp, 1e-9)
    }

    @Test fun `bought and sold the same day is charged as intraday`() {
        // RAYMOND, 5 Oct: 12 bought at 1232.41 and sold at 1282.00 the same day.
        val rep = costed(listOf(
            fill("BUY", 12.0, 1232.41, "2026-10-05T09:30:00"),
            fill("SELL", 12.0, 1282.00, "2026-10-05T14:10:00")))
        val t = rep.trips.single()
        assertTrue(t.sameDay)
        val p = t.chargeParts!!
        assertNull("no depository fee — the shares never left the account", p["DP charge"])
        val buy = 12 * 1232.41; val sell = 12 * 1282.0
        assertEquals(sell * 0.025 / 100, p["STT"]!!, 1e-6)
        assertEquals((buy + sell) * 0.03 / 100, p["brokerage"]!!, 1e-6)
        assertEquals(buy * 0.003 / 100, p["stamp duty"]!!, 1e-6)
        // About ₹16, against the ₹49.55 the delivery card charged for one piece.
        assertTrue(t.charges in 15.0..17.5)
    }

    @Test fun `intraday brokerage stops at twenty rupees an order`() {
        val rep = costed(listOf(
            fill("BUY", 100.0, 1000.0, "2026-10-05T09:30:00"),
            fill("SELL", 100.0, 1010.0, "2026-10-05T14:10:00")))
        assertEquals(2 * TradingCosts.ZERODHA_INTRADAY_BROKERAGE_CAP,
            rep.trips.single().chargeParts!!["brokerage"]!!, 1e-9)
    }

    @Test fun `held overnight keeps the delivery card`() {
        val rep = costed(listOf(
            fill("BUY", 5.0, 1057.05, "2026-09-21T10:00:00"),
            fill("SELL", 5.0, 1191.00, "2026-09-24T10:00:00")))
        val t = rep.trips.single()
        assertFalse(t.sameDay)
        // RAYMOND, 21 → 24 Sep: the app showed 28.42, and that one was right.
        assertEquals(28.42, t.charges, 0.02)
        assertNull(t.chargeParts!!["brokerage"])
    }
}
