package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** THE RESULTS RECORD and NEWS MOVES (6 Nov 2026). */
class ResultsBookTest {
    private val day = 86_400_000L

    @Test fun `quarters are read oldest first, surprise in percent`() {
        val body = """{"quoteSummary":{"result":[{"earningsHistory":{"history":[
            {"epsActual":{"raw":0.46},"epsEstimate":{"raw":0.42},"surprisePercent":{"raw":0.0969},"quarter":{"raw":1782777600}},
            {"epsActual":{"raw":0.30},"epsEstimate":{"raw":0.33},"surprisePercent":{"raw":-0.0909},"quarter":{"raw":1774915200}}]}}]}}"""
        val q = YahooFinanceClient.parseEarningsHistory(body)
        assertEquals(2, q.size)
        assertEquals(1774915200000L, q[0].quarterEndMs)
        assertEquals(9.69, q[1].surprisePct!!, 1e-6)
        assertEquals(-9.09, q[0].surprisePct!!, 1e-6)
    }

    @Test fun `batch quotes carry move, volume and the next results`() {
        val body = """{"quoteResponse":{"result":[{"symbol":"BC8.DE","regularMarketPrice":33.42,"regularMarketChangePercent":-3.24,
            "regularMarketVolume":95787,"averageDailyVolume10Day":228338,"earningsTimestamp":1794587400,"longName":"Bechtle AG"}]}}"""
        val q = YahooFinanceClient.parseQuotes(body)["BC8.DE"]!!
        assertEquals(-3.24, q.changePct, 1e-9); assertEquals(228338L, q.avgVolume10d)
        assertEquals(1794587400000L, q.earningsAtMs); assertEquals("Bechtle AG", q.name)
    }

    @Test fun `wording of a surprise`() {
        assertEquals("beat 9.7%", ResultsBook.word(9.69))
        assertEquals("miss 2.0%", ResultsBook.word(-2.0))
        assertEquals("in line", ResultsBook.word(0.4))
        assertEquals("?", ResultsBook.word(null))
    }

    @Test fun `read again every three days and right after a report`() {
        val now = 1_794_000_000_000L
        assertTrue(ResultsBook.stale(null, 0L, now))
        val r = ResultsBook.Rec(readAt = now - day)
        assertFalse(ResultsBook.stale(r, 0L, now))
        assertTrue(ResultsBook.stale(r, now - 3_600_000L, now))           // reported since it was read
        assertTrue(ResultsBook.stale(ResultsBook.Rec(readAt = now - 4 * day), 0L, now))
    }

    @Test fun `the reaction is two sessions against the close before`() {
        val d0 = 1_794_000_000_000L - (1_794_000_000_000L % day)    // midnight UTC
        val bars = listOf(d0 - 2 * day to 100.0, d0 - day to 100.0, d0 to 104.0, d0 + day to 106.0, d0 + 2 * day to 90.0)
        assertEquals(6.0, ResultsBook.reaction(bars, d0 + 20 * 3_600_000L)!!, 1e-9)   // reported in the evening
        assertNull(ResultsBook.reaction(bars, 0L))
    }

    @Test fun `a move worth reading the news for`() {
        assertTrue(NewsMoves.isMove(5.2, 2.1, false))
        assertFalse(NewsMoves.isMove(5.2, 1.4, false))       // big move, ordinary volume
        assertTrue(NewsMoves.isMove(-6.0, 3.0, false))
        assertTrue(NewsMoves.isMove(3.1, 1.6, true))          // around results, less needed
        assertFalse(NewsMoves.isMove(3.1, 1.6, false))
        assertEquals(0.2, NewsMoves.sessionShare(540, 1050, 545), 1e-9)   // just opened: floored
        assertEquals(0.5, NewsMoves.sessionShare(540, 1050, 795), 1e-9)
        assertEquals(1.0, NewsMoves.sessionShare(540, 1050, 1100), 1e-9)
    }

    @Test fun `the prompt states facts, newest first`() {
        val r = ResultsBook.Rec(quarters = listOf(
            ResultsBook.Quarter(1774915200000L, 0.30, 0.33, -9.09), ResultsBook.Quarter(1782777600000L, 0.46, 0.42, 9.69)))
        val p = ResultsBook.forPrompt(r)
        assertTrue(p.contains("Results record"))
        assertTrue(p.indexOf("beat 9.7%") < p.indexOf("miss 9.1%"))
        assertEquals("", ResultsBook.forPrompt(ResultsBook.Rec()))
    }
}
