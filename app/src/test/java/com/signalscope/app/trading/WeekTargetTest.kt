package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE 1-WEEK HIGH AS A TARGET: where it sits, when there is none, and what the
 * keeper does with the order resting at the broker on each pass.
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

    @Test fun `target sits at the week high, rounded down to the cent`() {
        val p = WeekTarget.plan(112.348, 108.0, null, preExisting = false)
        assertTrue(p.ok)
        assertEquals(112.34, p.price, 1e-9)
        // Floating error must not knock a whole cent off a round number.
        assertEquals(145.2, WeekTarget.plan(145.2, 140.0, null, false).price, 1e-9)
    }

    @Test fun `bought at the week high - no target`() {
        val p = WeekTarget.plan(100.5, 100.0, null, preExisting = false)
        assertFalse(p.ok)
        assertTrue(p.why.contains("too close"))
    }

    @Test fun `held from before and already past it - left to you, not sold`() {
        val p = WeekTarget.plan(110.0, 100.0, priceNow = 111.0, preExisting = true)
        assertFalse(p.ok)
        assertTrue(p.why.contains("already above"))
        // A new position past it is the target doing its job: still set.
        assertTrue(WeekTarget.plan(110.0, 100.0, priceNow = 111.0, preExisting = false).ok)
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
