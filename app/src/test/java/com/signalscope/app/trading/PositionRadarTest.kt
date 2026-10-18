package com.signalscope.app.trading

import com.signalscope.app.trading.PositionRadar.Kind
import com.signalscope.app.trading.PositionRadar.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pinned notification's position summary: what counts as near, and in what order. */
class PositionRadarTest {

    private fun pos(sym: String, paid: Double, price: Double, peak: Double = maxOf(paid, price),
                    target: Double = 0.0, stop: Double = 0.0) =
        Pos("k|$sym", sym, "DE", paid, price, peak, target, stop)

    @Test fun `in profit and within 5 percent of its high - near its high`() {
        assertEquals(Kind.NEAR_HIGH, PositionRadar.kindOf(pos("A", 100.0, 108.0, peak = 110.0)))
        // 6% under its high: not near.
        assertNull(PositionRadar.kindOf(pos("B", 100.0, 103.4, peak = 110.0)))
    }

    @Test fun `down since the buy is never near its high, though the high is what was paid`() {
        assertNull(PositionRadar.kindOf(pos("C", 100.0, 98.0, peak = 100.0)))
    }

    @Test fun `at or above its week's high it is at a short-term high - in profit or not`() {
        // MBG on 16 Oct: bought at 48.39, now 41.03, its week's high 40.41.
        val mbg = pos("MBG", 48.39, 41.03, peak = 48.39).copy(weekHigh = 40.41, threeHigh = 40.33)
        assertEquals(Kind.SHORT_HIGH, PositionRadar.kindOf(mbg))
        assertEquals("🔼 MBG −15.2% · above its 1-week high (40.41)", PositionRadar.line(Kind.SHORT_HIGH, mbg))
        // Under the week's high but at the three days' high: the shorter one is named.
        val three = pos("X", 50.0, 40.30, peak = 50.0).copy(weekHigh = 41.0, threeHigh = 40.33)
        assertTrue(PositionRadar.line(Kind.SHORT_HIGH, three).contains("at its 3-day high"))
        // Below both: nothing to say.
        assertNull(PositionRadar.kindOf(pos("Y", 50.0, 39.0, peak = 50.0).copy(weekHigh = 41.0, threeHigh = 40.0)))
        // Near its high since buy, in profit, still says THAT — the stronger fact.
        assertEquals(Kind.NEAR_HIGH, PositionRadar.kindOf(pos("Z", 100.0, 108.0, peak = 110.0).copy(weekHigh = 107.0)))
    }

    @Test fun `near or through the stop beats everything else`() {
        // 1.5% above its stop, and also near its high: the stop is what matters.
        assertEquals(Kind.NEAR_STOP, PositionRadar.kindOf(pos("D", 100.0, 101.5, target = 102.0, stop = 100.0)))
        assertEquals(Kind.NEAR_STOP, PositionRadar.kindOf(pos("E", 100.0, 95.0, stop = 96.0)))
        assertNull(PositionRadar.kindOf(pos("F", 100.0, 97.0, stop = 90.0)))
    }

    @Test fun `within 2 percent of the target, or past it - near its target`() {
        assertEquals(Kind.NEAR_TARGET, PositionRadar.kindOf(pos("G", 100.0, 103.0, target = 104.5)))
        assertEquals(Kind.NEAR_TARGET, PositionRadar.kindOf(pos("H", 100.0, 106.0, target = 105.0)))
    }

    @Test fun `headline counts each kind, lines put stops first`() {
        val s = PositionRadar.summarize(listOf(
            pos("HIGH1", 100.0, 109.0, peak = 110.0),
            pos("HIGH2", 50.0, 52.0),
            pos("TGT", 100.0, 103.0, target = 104.0),
            pos("STOP", 100.0, 97.0, stop = 96.0),
            pos("QUIET", 100.0, 99.0, stop = 90.0)), now = 0L)
        assertEquals("🟢 2 near their high · 🎯 1 near target · 🔴 1 near stop", s.headline)
        assertEquals(5, s.held)
        assertTrue(s.lines[0].startsWith("🔴 STOP"))
        assertTrue(s.lines[1].startsWith("🎯 TGT"))
        // At its high is said so; under it, by how much.
        assertTrue(s.lines.any { it.startsWith("🟢 HIGH2") && it.contains("at its high since buy") })
        assertTrue(s.lines.any { it.startsWith("🟢 HIGH1") && it.contains("0.9% under its high (110.00)") })
    }

    @Test fun `a frozen price is flagged first, never read as a gain`() {
        val frozen = Pos("k|PFSE", "PFSE", "DE", 62.6, 66.6, 66.6, target = 64.98, stop = 0.0,
            staleSinceMs = 1_791_400_000_000L, priceStale = true)
        assertEquals(Kind.NO_PRICE, PositionRadar.kindOf(frozen))
        val s = PositionRadar.summarize(listOf(frozen, pos("STOP", 100.0, 97.0, stop = 96.0)), now = 0L)
        assertTrue(s.headline.endsWith("⚠ 1 unpriced"))
        assertTrue(s.lines[0].startsWith("⚠ PFSE · no current price since "))
        assertTrue(s.lines[1].startsWith("🔴 STOP"))
    }

    @Test fun `nothing near anything - says so rather than staying blank`() {
        val s = PositionRadar.summarize(listOf(pos("Q", 100.0, 99.0)), now = 0L)
        assertEquals("1 held · none near a stop, target or high", s.headline)
        assertTrue(s.lines.isEmpty())
    }

    @Test fun `each widget row carries its market's flag and who manages the exit`() {
        val de = pos("HAG", 81.52, 83.4, target = 83.9).copy(exitBy = "watchlist · stop 80.30 checked by the app")
        val us = Pos("k|ROP", "ROP", "US", 400.0, 428.0, 428.0, 0.0, 0.0)
        val s = PositionRadar.summarize(listOf(de, us), now = 0L)
        val rows = s.rows!!
        assertEquals("🇩🇪", rows[0].flag)
        assertEquals(s.full!![0], rows[0].text)
        assertEquals("watchlist · stop 80.30 checked by the app", rows[0].detail)
        assertEquals("🇺🇸", rows[1].flag)
    }

    @Test fun `more than fits - the rest are counted, not dropped silently`() {
        val many = (1..9).map { pos("S$it", 100.0, 100.0 + it * 0.1, peak = 101.0) }
        val s = PositionRadar.summarize(many, now = 0L)
        assertEquals(PositionRadar.MAX_LINES, s.lines.size)
        assertEquals("+4 more — open the app", s.lines.last())
    }
}
