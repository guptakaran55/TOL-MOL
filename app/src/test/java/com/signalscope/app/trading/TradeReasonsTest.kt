package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHY EACH CLOSED TRADE WAS BOUGHT AND SOLD (6 Nov 2026): the wording, and the
 * order ids carried from the fills to each trade so they can be looked up.
 */
class TradeReasonsTest {
    @Test fun `target levels read as the user names them`() {
        assertEquals("weekly", TradeReasons.levelWord("high1w"))
        assertEquals("weekly", TradeReasons.levelWord("week"))
        assertEquals("monthly", TradeReasons.levelWord("month"))
        assertEquals("3-month", TradeReasons.levelWord("high3m"))
        assertEquals("yearly", TradeReasons.levelWord("high1y"))
        assertEquals("", TradeReasons.levelWord(null))
    }

    @Test fun `an order no record claims is read from Trading 212's own fields`() {
        assertEquals("manual on broker", TradeReasons.fromBroker("LIMIT|ANDROID", buy = true))
        assertEquals("app", TradeReasons.fromBroker("LIMIT|API", buy = true))
        assertEquals("SL hit · set by hand", TradeReasons.fromBroker("STOP|IOS", buy = false))
        assertEquals("set by hand", TradeReasons.fromBroker("LIMIT|WEB", buy = false))
        assertEquals("sold by hand", TradeReasons.fromBroker("MARKET|ANDROID", buy = false))
        assertEquals("", TradeReasons.fromBroker("|", buy = false))          // archived before the fields existed
        assertEquals("", TradeReasons.fromBroker(null, buy = true))
    }

    @Test fun `exit notes are shortened for the table`() {
        assertEquals("MACD turned down", TradeReasons.shortExit("MACD slope flipped + to -"))
        assertEquals("set by hand", TradeReasons.shortExit("your own order at Trading 212 (a target, stop or sale you set)"))
        assertEquals("sold", TradeReasons.shortExit(null))
    }

    @Test fun `each exit says what set it`() {
        val L = TradeReasons
        assertEquals("SL −4% fixed", L.exitLabel("stop loss -4% (fine bar)"))
        assertEquals("SL −9% from buy", L.exitLabel("stop", hardStopPct = 9.0))
        assertEquals("SL −3% from fill", L.exitLabel("real stop -3% from fill"))
        assertEquals("TP +6% from fill", L.exitLabel("take profit +6.00% from a fill of 22.10"))
        assertEquals("target · 3-month high", L.exitLabel("reached its target of 145.00 (near its 3-month high)"))
        assertEquals("target · 1-week high", L.exitLabel("reached its 1-week high target of 50.10"))
        assertEquals("target · range top", L.exitLabel("range target"))
        assertEquals("your target", L.exitLabel("reached its target of 12.00 (your own price)"))
        assertEquals("MACD turned down", L.exitLabel("MACD slope flipped + to -"))
        // since 9 Nov: the real drop and the band multiple are both in the note
        assertEquals("trail −3.4% from high (1.5×BB/2)",
            L.exitLabel("trailing stop -3.40% from a high of 70.20 (1.50× half Bollinger width)"))
        assertEquals("trail −2.5% from high (2×ATR)",
            L.exitLabel("trailing stop -2.50% from a high of 70.20 (2.00× ATR)"))
        // a plain percentage trail
        assertEquals("trail −1.5% from high", L.exitLabel("trailing stop -1.50% from a high of 14.24", "PCT:1.5"))
        // before 9 Nov the band multiple was written as if it were the drop: worked out from the sale
        val old = L.exitLabel("trailing stop -1.50% from a high of 70.00", "BB:1.5")
        assertEquals("trail −3.0% from high (1.5×BB/2)", L.fillHighMarks(old, 67.90))
    }

    @Test fun `the built-in algos' exits use their own settings`() {
        assertEquals("SL −4%", TradeReasons.floorExit("stop", 4.0, "PCT", 7.0))
        assertEquals("SL 2×ATR", TradeReasons.floorExit("stop", 2.0, "ATR", 7.0))
        assertEquals("TP +7%", TradeReasons.floorExit("target", 4.0, "PCT", 7.0))
        assertEquals("target reached", TradeReasons.floorExit("target", 4.0, "PCT", 0.0))
    }

    @Test fun `each trade carries the orders that made it`() {
        val fills = listOf(
            ClosedTrades.Fill("SAP_EQ", "SAP", 2.0, 100.0, 200.0, "BUY", "2026-11-01T10:00:00", "11", "LIMIT|API"),
            ClosedTrades.Fill("SAP_EQ", "SAP", 2.0, 110.0, 220.0, "SELL", "2026-11-05T10:00:00", "22", "STOP|ANDROID"))
        val t = ClosedTrades.match(fills).first.single()
        assertEquals("11", t.buyOrderId); assertEquals("22", t.sellOrderId)
        assertEquals("STOP|ANDROID", t.sellMeta)
    }
}
