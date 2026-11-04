package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The quality · cheap · uptrend filter, its quarters and its news veto. */
class QualityCheapTest {

    private fun report(quality: Int, traps: Int) =
        "Valuation|12|30|PE 11\nQuality|$quality|30|low debt\nGrowth|4|18|x\nYield|3|10|x\n" +
            "Discount|0|12|x\nValue traps|$traps|12|" + (if (traps == 0) "none" else "revenue -8%")

    private fun row(sym: String, pe: Double?, quality: Int = 24, traps: Int = 0, px: Double = 110.0,
                    sma: Double? = 100.0, fcf: Double? = 1e9, name: String = sym) =
        QualityCheap.Row(sym, name, px, sma, pe, fcf, report(quality, traps))

    @Test fun readsOnePartOfTheBreakdown() {
        assertEquals(17, QualityCheap.partOf(report(17, -12), "Quality"))
        assertEquals(-12, QualityCheap.partOf(report(17, -12), "Value traps"))
        assertNull(QualityCheap.partOf("", "Quality"))
    }

    @Test fun eachRuleKeepsAStockOut() {
        assertTrue(QualityCheap.passes(row("A", 12.0)))
        assertFalse("quality under 20", QualityCheap.passes(row("A", 12.0, quality = 19)))
        assertFalse("a value-trap penalty", QualityCheap.passes(row("A", 12.0, traps = -5)))
        assertFalse("free cash flow negative", QualityCheap.passes(row("A", 12.0, fcf = -1.0)))
        assertFalse("a loss-maker", QualityCheap.passes(row("A", -4.0)))
        assertFalse("no P/E", QualityCheap.passes(row("A", null)))
        assertFalse("below its 200-day average", QualityCheap.passes(row("A", 12.0, px = 95.0)))
        assertFalse("no 200-day average", QualityCheap.passes(row("A", 12.0, sma = null)))
        assertTrue("no free-cash-flow figure is not a negative one", QualityCheap.passes(row("A", 12.0, fcf = null)))
    }

    @Test fun cheapestFirstOneClassPerCompanyCapped() {
        val rows = listOf(
            row("SAP.DE", 30.8), row("MUV2.DE", 10.4), row("TMV.DE", 9.5),
            row("RHM.DE", 50.0, px = 90.0),                          // below its average
            row("GOOGL", 20.0, name = "Alphabet Inc."), row("GOOG", 20.0, name = "Alphabet Inc."))
        assertEquals(listOf("TMV", "MUV2", "GOOGL", "SAP"), QualityCheap.pick(rows, 30))
        assertEquals(listOf("TMV", "MUV2"), QualityCheap.pick(rows, 2))
    }

    @Test fun theQuarterThatHasEnded() {
        assertEquals("2026-Q3", QualityCheap.lastQuarter(LocalDate.of(2026, 10, 26)))
        assertEquals("2026-Q3", QualityCheap.lastQuarter(LocalDate.of(2026, 12, 31)))
        assertEquals("2026-Q4", QualityCheap.lastQuarter(LocalDate.of(2027, 1, 2)))
        assertEquals("2027-Q1", QualityCheap.lastQuarter(LocalDate.of(2027, 4, 1)))
        assertTrue("quarters compare as text", "2026-Q4" > "2026-Q3" && "2027-Q1" > "2026-Q4")
    }

    @Test fun onlyClearlyBadNewsVetoes() {
        assertTrue(QualityCheap.isVeto("STRONG AVOID"))
        assertTrue(QualityCheap.isVeto("SELL"))
        assertFalse(QualityCheap.isVeto("AVOID"))
        assertFalse(QualityCheap.isVeto("WATCH"))
        assertFalse(QualityCheap.isVeto(""))
    }

    @Test fun bareTickers() {
        assertEquals("MUV2", QualityCheap.bare("muv2.de"))
        assertEquals("TCS", QualityCheap.bare("TCS.NS"))
        assertEquals("GOOGL", QualityCheap.bare("GOOGL"))
    }
}
