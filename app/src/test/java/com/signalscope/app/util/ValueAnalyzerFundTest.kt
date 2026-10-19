package com.signalscope.app.util

import com.signalscope.app.network.YahooFinanceClient.FundamentalData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueAnalyzerFundTest {

    // What Yahoo hands back for a gold ETF: a basket "PE" and a 52-week range.
    private val goldEtf = FundamentalData(
        trailingPe = 8.0, priceToBook = 0.9, dividendYield = 0.0,
        fiftyTwoWeekLow = 60.0, fiftyTwoWeekHigh = 90.0, quoteType = "ETF"
    )

    @Test fun etfGetsNoValueScore() {
        val r = ValueAnalyzer.analyze(goldEtf, currentPrice = 61.0)
        assertEquals(0, r.valueScore)
        assertEquals("N/A", r.valueRating)
        assertEquals(ValueAnalyzer.FUND_SECTOR, r.sector)
        assertNull(r.trailingPe)
        assertNull(r.priceToBook)
        assertEquals(90.0, r.fiftyTwoWeekHigh!!, 0.0)
    }

    @Test fun sameNumbersOnAShareAreScored() {
        val r = ValueAnalyzer.analyze(goldEtf.copy(quoteType = "EQUITY"), currentPrice = 61.0)
        assertTrue(r.valueScore > 0)
    }

    @Test fun savedFundRowStaysAFundOnRescore() {
        assertTrue(ValueAnalyzer.isFund(null, ValueAnalyzer.FUND_SECTOR))
        assertTrue(ValueAnalyzer.isFund("mutualfund"))
        assertTrue(ValueAnalyzer.isFund("FUTURE"))
        assertFalse(ValueAnalyzer.isFund("EQUITY", "Technology"))
        assertFalse(ValueAnalyzer.isFund(null, null))
    }
}
