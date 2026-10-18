package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RANGE TARGET: where it sits (week's high, or just short of the month's),
 * when there is none, and what the keeper does with the order resting at the
 * broker on each pass.
 */
class WeekTargetTest {

    private val day0 = 1_790_000_000L / 86_400L

    /** Hourly bars, 8 a day, with the given high on hour 3 of each day. */
    private fun bars(highs: List<Double>): List<CandleData> {
        val out = ArrayList<CandleData>()
        highs.forEachIndexed { d, hi ->
            for (h in 0 until 8) {
                val t = (day0 + d) * 86_400L + 8 * 3600L + h * 3600L
                val high = if (h == 3) hi else 100.0
                out.add(CandleData(t, 99.0, high, 98.0, 99.5, 1000))
            }
        }
        return out
    }

    private fun at(day: Int, hour: Int) = (day0 + day) * 86_400L + hour * 3600L

    @Test fun `the high of the five trading days before the buy`() {
        // Ten days; the buy on day 9 at 10:30. Days 4-8 are the week before;
        // day 2's 130 is outside it, and day 9's own 125 at 11:00 is after the buy.
        val highs = listOf(101.0, 101.0, 130.0, 101.0, 101.0, 112.0, 101.0, 101.0, 101.0, 125.0)
        val hi = WeekTarget.highBefore(bars(highs), at(9, 10) + 1800, 3600)
        // Day 9's bars that CLOSED before 10:30 count (08:00 and 09:00 → high 100),
        // so the window is days 5-9 and its high is day 5's 112.
        assertEquals(112.0, hi!!, 1e-9)
    }

    @Test fun `a bar still open at the buy is left out`() {
        val highs = listOf(101.0, 101.0, 101.0, 101.0, 101.0, 140.0)
        // Buy at 11:30 on day 5: the 11:00 bar (the 140 spike) has not closed.
        val hi = WeekTarget.highBefore(bars(highs), at(5, 11) + 1800, 3600)
        assertEquals(101.0, hi!!, 1e-9)
    }

    @Test fun `not a week of history - no answer`() {
        assertNull(WeekTarget.highBefore(bars(listOf(101.0, 101.0)), at(2, 10), 3600))
    }

    private fun r(wLo: Double, wHi: Double, mLo: Double?, mHi: Double?) =
        WeekTarget.Ranges(WeekTarget.Range(wLo, wHi), if (mLo != null && mHi != null) WeekTarget.Range(mLo, mHi) else null)

    @Test fun `near the week's low - the week's high, rounded down to the cent`() {
        // Month 90-130 (bought mid-month), week 108-112.348: bought at the week's low.
        val p = WeekTarget.plan(r(108.0, 112.348, 90.0, 130.0), 108.2, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals("week", p.kind)
        assertEquals(112.34, p.price, 1e-9)
        // Floating error must not knock a whole cent off a round number.
        assertEquals(145.2, WeekTarget.plan(r(140.0, 145.2, 120.0, 160.0), 140.0, null, false).price, 1e-9)
    }

    @Test fun `near the month's low - a tenth of the month's range short of its high`() {
        // Month 100-150, bought at 102 (bottom tenth): 150 - 5 = 145.
        val p = WeekTarget.plan(r(101.0, 108.0, 100.0, 150.0), 102.0, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals("month", p.kind)
        assertEquals(145.0, p.price, 1e-9)
    }

    @Test fun `near neither low - the month target too`() {
        // Bought on strength at the week's high, mid-month: month 75-85.04 → 84.03.
        val p = WeekTarget.plan(r(78.0, 81.81, 75.0, 85.04), 81.52, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals("month", p.kind)
        assertEquals(84.03, p.price, 1e-9)
    }

    @Test fun `week's high too close - falls back to the month target`() {
        // Bought at the week's low but the week is narrow (100-100.6): month 90-120 → 117.
        val p = WeekTarget.plan(r(100.0, 100.6, 90.0, 120.0), 100.0, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals("month", p.kind)
        assertEquals(117.0, p.price, 1e-9)
    }

    @Test fun `too close everywhere - no target, and the minimum depends on fees`() {
        // Month target 100.8 on a 100 fill: 0.8% clears the fee-free 0.5% but not 1%.
        val rr = r(99.0, 100.5, 99.0, 101.0)   // month: 101 - 0.2 = 100.8
        val us = WeekTarget.plan(rr, 100.0, null, false, WeekTarget.minGapFor("us"))
        assertFalse(us.ok)
        assertTrue(us.why.contains("too close"))
        val de = WeekTarget.plan(rr, 100.0, null, false, WeekTarget.minGapFor("de"))
        assertTrue(de.ok)
        assertEquals(100.8, de.price, 1e-9)
    }

    @Test fun `no month of history - the week's high alone`() {
        val p = WeekTarget.plan(r(95.0, 110.0, null, null), 100.0, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals("week", p.kind)
        assertEquals(110.0, p.price, 1e-9)
    }

    @Test fun `held from before and already past it - left to you, not sold`() {
        val rr = r(99.0, 110.0, null, null)
        val p = WeekTarget.plan(rr, 100.0, priceNow = 111.0, preExisting = true)
        assertFalse(p.ok)
        assertTrue(p.why.contains("already above"))
        // A new position past it is the target doing its job: still set.
        assertTrue(WeekTarget.plan(rr, 100.0, priceNow = 111.0, preExisting = false).ok)
    }

    @Test fun `week and month ranges come from bars closed before the buy`() {
        // 22 days of highs; day 2's 130 is inside the month but not the week.
        val highs = List(22) { d -> if (d == 2) 130.0 else if (d == 18) 112.0 else 101.0 }
        val rr = WeekTarget.rangesBefore(bars(highs), at(21, 10) + 1800, 3600)
        assertEquals(112.0, rr.week!!.high, 1e-9)
        assertEquals(130.0, rr.month!!.high, 1e-9)
        assertEquals(98.0, rr.month!!.low, 1e-9)
    }

    @Test fun `nothing resting - place it`() {
        assertEquals(WeekTarget.Step.PLACE,
            WeekTarget.step(0L, 3.0, emptyMap(), emptySet(), null, 0L, 0L))
    }

    @Test fun `resting for the right shares - keep, for the wrong ones - replace`() {
        assertEquals(WeekTarget.Step.KEEP,
            WeekTarget.step(7L, 3.0, mapOf(7L to 3.0), emptySet(), null, 0L, 0L))
        // The watcher trimmed this position to "your share only".
        assertEquals(WeekTarget.Step.REPLACE,
            WeekTarget.step(7L, 2.0, mapOf(7L to 3.0), emptySet(), null, 0L, 0L))
    }

    @Test fun `gone and in the history - it sold`() {
        assertEquals(WeekTarget.Step.FILLED,
            WeekTarget.step(7L, 3.0, emptyMap(), setOf(7L), true, 0L, 0L))
    }

    @Test fun `gone, shares still there - put back only after the history has had time`() {
        val now = 10_000_000L
        assertEquals(WeekTarget.Step.WAIT,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), true, 0L, now))
        assertEquals(WeekTarget.Step.WAIT,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), true, now - 5 * 60_000L, now))
        assertEquals(WeekTarget.Step.FORGET,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), true, now - WeekTarget.FORGET_AFTER_MS, now))
    }

    @Test fun `gone with the shares - wait for the fill, then give up and say so`() {
        val now = 100_000_000L
        assertEquals(WeekTarget.Step.WAIT,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), false, now - 60_000L, now))
        assertEquals(WeekTarget.Step.ABANDON,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), false, now - WeekTarget.GIVE_UP_MS - 1, now))
        // Holdings unreadable: never acts on a guess.
        assertEquals(WeekTarget.Step.WAIT,
            WeekTarget.step(7L, 3.0, emptyMap(), emptySet(), null, now - WeekTarget.GIVE_UP_MS - 1, now))
    }

    @Test fun `share counts are tried exact first and never rounded up`() {
        val q = WeekTarget.qtyAttempts(0.38314882)
        assertEquals(0.38314882, q.first(), 0.0)
        assertTrue(q.all { it <= 0.38314882 })
        assertEquals(listOf(3.0), WeekTarget.qtyAttempts(3.0))
    }
}
