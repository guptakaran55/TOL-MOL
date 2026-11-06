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

    @Test fun `each trade carries the orders that made it`() {
        val fills = listOf(
            ClosedTrades.Fill("SAP_EQ", "SAP", 2.0, 100.0, 200.0, "BUY", "2026-11-01T10:00:00", "11", "LIMIT|API"),
            ClosedTrades.Fill("SAP_EQ", "SAP", 2.0, 110.0, 220.0, "SELL", "2026-11-05T10:00:00", "22", "STOP|ANDROID"))
        val t = ClosedTrades.match(fills).first.single()
        assertEquals("11", t.buyOrderId); assertEquals("22", t.sellOrderId)
        assertEquals("STOP|ANDROID", t.sellMeta)
    }
}
