package com.signalscope.app.trading

import com.signalscope.app.data.FloorAlgoStore.Algo
import com.signalscope.app.data.FloorAlgoStore.Trade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trades export: each result before and after the market's charges. */
class FloorTradesCsvTest {

    private val buy = 1_792_000_000L
    private fun rows(a: Algo) = FloorTradesCsv.csv(a, nowSec = buy + 86_400).trim().lines()
    private fun col(row: String, name: String) =
        row.split(",")[FloorTradesCsv.HEADER.split(",").indexOf(name)]

    @Test fun `a sale shows its result before and after the charges and the spread`() {
        val a = Algo(name = "Macd swing", kind = "macd", space = "in", trades = listOf(
            Trade(id = "1", symbol = "TCS", firstSec = buy, firstPrice = 100.0, qty = 10.0, cost = 1000.0, halves = 1,
                status = "SOLD", sellPrice = 107.0, sellSec = buy + 3 * 86_400, exitReason = "target",
                fees = 4.5, slip = 1.0)))
        val r = rows(a)
        assertEquals(FloorTradesCsv.HEADER, r[0])
        assertEquals("INR", col(r[1], "currency"))
        assertEquals("70.00", col(r[1], "pnl_before_charges"))
        assertEquals("7.00", col(r[1], "pct_before_charges"))
        assertEquals("4.50", col(r[1], "broker_charges"))
        assertEquals("1.00", col(r[1], "spread"))
        assertEquals("64.50", col(r[1], "pnl_after_charges"))
        assertEquals("3.0", col(r[1], "days_held"))
        assertEquals("target", col(r[1], "exit_reason"))
    }

    @Test fun `a held trade is valued at the latest price, a missed one is not counted, a turn never filled is left out`() {
        val a = Algo(name = "Floor, test", kind = "floor", space = "us", trades = listOf(
            Trade(id = "1", symbol = "AAPL", firstSec = buy, qty = 2.0, cost = 200.0, lastPrice = 95.0, lastSec = buy + 3600,
                status = "OPEN", fees = 0.6),
            Trade(id = "2", symbol = "MSFT", firstSec = buy + 60, qty = 1.0, cost = 300.0, lastPrice = 310.0, status = "MISSED"),
            Trade(id = "3", symbol = "NVDA", signalSec = buy, status = "OPEN")))
        val r = rows(a)
        assertEquals(3, r.size)
        assertTrue(r[1].startsWith("\"Floor, test\",US,floor,USD,AAPL"))
        assertEquals("-10.00", col(r[1].replace("\"Floor, test\"", "x"), "pnl_before_charges"))
        assertEquals("-10.60", col(r[1].replace("\"Floor, test\"", "x"), "pnl_after_charges"))
        assertEquals("no", col(r[2].replace("\"Floor, test\"", "x"), "counted"))
    }
}
