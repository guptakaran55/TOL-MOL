package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.FloorAlgoStore.Campaign
import com.signalscope.app.screener.DailyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * THE CAMPAIGN RULES: the zones, the entry price, the news verdict, the MACD
 * counts, and what one bar does to a campaign.
 */
class CampaignRulesTest {

    /** 30 daily bars, each with a 2-point range around a flat 100 — an ATR of 2. */
    private val flat = (0 until 30).map { i ->
        DailyBar(LocalDate.parse("2026-09-01").plusDays(i.toLong()), 100.0, 101.0, 99.0)
    }

    @Test fun `buffers are a fraction of the ATR of their own timescale`() {
        val l = CampaignRules.levels(flat, 0.1, "range")!!
        assertEquals(2.0, l.atr, 1e-9)
        assertEquals(0.2, l.bufDay, 1e-9)
        assertEquals(0.2 * sqrt(5.0), l.bufWeek, 1e-9)
        assertEquals(0.2 * sqrt(21.0), l.bufMonth, 1e-9)
        assertEquals(99.0, l.support, 1e-9)    // the month's low
        assertEquals(101.0, l.weekRes, 1e-9)
        assertEquals("range", l.source)
    }

    @Test fun `a candidate sits within a monthly buffer of its support - not above, not broken below`() {
        val l = CampaignRules.levels(flat, 0.1, "range")!!
        assertTrue(CampaignRules.isCandidate(l, 99.5))
        assertFalse(CampaignRules.isCandidate(l, 101.0))       // above the zone
        assertFalse(CampaignRules.isCandidate(l, 97.0))        // broken down
    }

    @Test fun `the entry bids at the top of the zone, the day's low if the MACD turned, higher on a BUY`() {
        val l = CampaignRules.levels(flat, 0.1, "range")!!
        assertEquals(99.0 + l.bufMonth, CampaignRules.entryPrice(l, false, ""), 1e-9)
        assertEquals(99.0 + l.bufDay, CampaignRules.entryPrice(l, true, "WATCH"), 1e-9)
        // A BUY bids a monthly buffer higher — here past yesterday's close of 100,
        // so it stops there: higher would be a market order, not a limit.
        assertEquals(100.0, CampaignRules.entryPrice(l, false, "BUY"), 1e-9)
        val wide = l.copy(prevClose = 105.0)
        assertEquals(99.0 + 2 * l.bufMonth, CampaignRules.entryPrice(wide, false, "BUY"), 1e-9)
        assertEquals(99.0 + 3 * l.bufMonth, CampaignRules.entryPrice(wide, false, "STRONG BUY"), 1e-9)
    }

    @Test fun `a bearish outlook keeps a campaign from starting`() {
        assertTrue(CampaignRules.blocks("AVOID"))
        assertTrue(CampaignRules.blocks("STRONG AVOID"))
        assertFalse(CampaignRules.blocks("WATCH"))
        assertFalse(CampaignRules.blocks(""))
    }

    @Test fun `MACD days down, a fresh turn up, a turn down`() {
        assertEquals(3, CampaignRules.negDays(doubleArrayOf(0.5, 0.2, -0.1, -0.2, -0.3)))
        assertEquals(0, CampaignRules.negDays(doubleArrayOf(-0.5, 0.1)))
        assertTrue(CampaignRules.turnedUp(doubleArrayOf(-0.2, -0.1, 0.1)))
        assertFalse(CampaignRules.turnedUp(doubleArrayOf(0.1, 0.2, 0.3)))   // rising all along: no turn
        assertTrue(CampaignRules.turnedDown(doubleArrayOf(0.2, -0.1)))
        assertFalse(CampaignRules.turnedDown(doubleArrayOf(-0.2, -0.1)))
    }

    // ── one bar through a campaign ──
    private fun bar(t: Long, o: Double, h: Double, l: Double, c: Double) = CandleData(t, o, h, l, c, 100)

    private val base = Campaign(id = "X|d", symbol = "X", state = "HOLDING", cash = 0.0, qty = 2.0, cost = 200.0,
        legId = "X|d|1", legs = 1, peak = 100.0, prevHigh = 104.0, prevLow = 98.0, atr = 2.0,
        bufDay = 0.2, bufWeek = 0.45, bufMonth = 0.9, weekRes = 110.0, monthRes = 120.0)

    @Test fun `a waiting order fills when the price trades below it`() {
        val w = base.copy(state = "WAITING", orderPrice = 99.0, orderFromSec = 1000, legId = "", qty = 0.0)
        val r = CampaignRules.onBar(w, bar(1000, 99.5, 99.6, 98.8, 99.2), "PCT", 4.0)
        assertEquals("HOLDING", r.c.state)
        assertTrue(r.events.single().buy)
        assertEquals(99.0, r.events.single().price, 1e-9)
    }

    @Test fun `near the day's high the swing is sold at a limit`() {
        val r = CampaignRules.onBar(base, bar(1000, 102.0, 103.9, 101.5, 103.5), "PCT", 4.0)
        assertEquals("OUT", r.c.state)
        assertEquals(103.8, r.events.single().price, 1e-9)    // the high less a day's buffer
        assertFalse(r.events.single().market)
    }

    @Test fun `breaking the day's high rides it until the price turns down`() {
        val r1 = CampaignRules.onBar(base, bar(1000, 103.0, 105.0, 102.9, 104.8), "PCT", 4.0)
        assertEquals("RIDING", r1.c.state)
        assertTrue(r1.events.isEmpty())
        val r2 = CampaignRules.onBar(r1.c, bar(1900, 104.8, 105.5, 104.7, 105.4), "PCT", 4.0)
        assertEquals("RIDING", r2.c.state)
        val r3 = CampaignRules.onBar(r2.c, bar(2800, 105.4, 105.4, 104.9, 105.0), "PCT", 4.0)
        assertEquals("OUT", r3.c.state)
        assertEquals(105.0, r3.events.single().price, 1e-9)
        assertTrue(r3.events.single().market)
    }

    @Test fun `in the weekly zone the target is the weekly resistance, and crossing it is announced`() {
        val c = base.copy(prevHigh = 112.0)
        val r = CampaignRules.onBar(c, bar(1000, 109.0, 110.5, 108.9, 110.2), "PCT", 4.0)
        assertEquals("OUT", r.c.state)
        assertEquals(110.0, r.events.single().price, 1e-9)
        assertTrue(r.events.single().notice.contains("weekly resistance"))
        assertEquals(1, r.c.weekCrosses)
    }

    @Test fun `the stop ends the campaign at the market`() {
        val r = CampaignRules.onBar(base, bar(1000, 99.0, 99.0, 95.5, 96.0), "PCT", 4.0)
        assertEquals("ENDED", r.c.state)
        assertEquals("stop", r.c.endReason)
        assertEquals(96.0, r.events.single().price, 1e-9)     // 4% under the 100 high
        assertTrue(r.events.single().ends)
    }

    @Test fun `entering the monthly resistance zone is announced and the swinging stops`() {
        val c = base.copy(prevHigh = 125.0, weekRes = 130.0, peak = 118.0)
        val r = CampaignRules.onBar(c, bar(1000, 118.5, 119.5, 118.4, 119.4), "PCT", 4.0)
        assertTrue(r.c.inMonthZone)
        assertEquals("HOLDING", r.c.state)
        assertTrue(r.events.single().notice.contains("monthly resistance"))
    }

    @Test fun `a pending exit sells at the next bar's open`() {
        val c = base.copy(exitPending = "daily MACD down 4 days in a row")
        val r = CampaignRules.onBar(c, bar(1000, 101.0, 101.5, 100.5, 101.2), "PCT", 4.0)
        assertEquals("ENDED", r.c.state)
        assertEquals(101.0, r.events.single().price, 1e-9)
    }
}
