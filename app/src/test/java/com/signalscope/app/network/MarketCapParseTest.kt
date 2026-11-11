package com.signalscope.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** COMPANY SIZE on the stock page (11 Nov 2026): the market cap from Yahoo's price module. */
class MarketCapParseTest {
    @Test fun `market cap and currency are read from the price module`() {
        val body = """{"quoteSummary":{"result":[{"price":{"currency":"INR","marketCap":{"raw":317150000000,"fmt":"3.17T"}}}]}}"""
        val (cap, cur) = YahooFinanceClient.parseMarketCap(body)
        assertEquals(317150000000.0, cap!!, 1.0)
        assertEquals("INR", cur)
    }

    @Test fun `no market cap reads as none`() {
        assertNull(YahooFinanceClient.parseMarketCap("""{"quoteSummary":{"result":[{"price":{"currency":"EUR"}}]}}""").first)
        assertNull(YahooFinanceClient.parseMarketCap("not json").first)
    }
}
