package com.signalscope.app.trading

import com.signalscope.app.network.YahooFinanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** THE MARKET GATE (12 Nov 2026): buys only on days the index's daily MACD rose. */
class MarketGateTest {
    @Test fun `a rising index opens the gate, a falling one shuts it`() {
        val up = (0 until 60).map { 100.0 + it }
        assertEquals(true, MarketGate.dailyMacdRising(up))
        // a long rise, then a sharp fall: the MACD line turns down
        val turned = up + listOf(150.0, 140.0, 130.0)
        assertEquals(false, MarketGate.dailyMacdRising(turned))
        assertNull(MarketGate.dailyMacdRising(up.take(20)))      // too little history
    }

    @Test fun `daily closes are read with gaps left out`() {
        val body = """{"chart":{"result":[{"timestamp":[1,2,3],"indicators":{"quote":[{"close":[100.5,null,101.0]}]}}]}}"""
        assertEquals(listOf(1L to 100.5, 3L to 101.0), YahooFinanceClient.parseDailyCloses(body))
    }
}
