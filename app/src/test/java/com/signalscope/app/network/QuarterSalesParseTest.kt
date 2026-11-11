package com.signalscope.app.network

import com.signalscope.app.trading.ResultsBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RESULTS WHERE YAHOO HAS NO ANALYST ESTIMATES (Hochtief, Fielmann…, 11 Nov 2026). */
class QuarterSalesParseTest {
    private val body = """{"quoteSummary":{"result":[{"earnings":{"financialCurrency":"EUR","financialsChart":{"quarterly":[
        {"date":"2Q2025","revenue":{"raw":9450000000},"earnings":{"raw":173210000}},
        {"date":"1Q2026","revenue":{"raw":9390000000},"earnings":{}},
        {"date":"bad","revenue":{}}]}}}]}}"""

    @Test fun `reported sales by quarter are read, oldest first`() {
        val (s, cur) = YahooFinanceClient.parseQuarterSales(body)
        assertEquals("EUR", cur)
        assertEquals(listOf("2Q2025", "1Q2026"), s.map { it.label })
        assertEquals(173210000.0, s[0].earnings!!, 1.0)
        assertNull(s[1].earnings)
    }

    @Test fun `with no beat-or-miss record the AI is given the sales instead`() {
        val r = ResultsBook.Rec(readAt = 1L, sales = listOf(ResultsBook.Sales("3Q2026", 649_480_000.0, 57_640_000.0)), salesCur = "EUR")
        val p = ResultsBook.forPrompt(r)
        assertTrue(p, p.contains("Quarter 3Q2026: sales 649 m EUR, profit 58 m EUR"))
    }
}
