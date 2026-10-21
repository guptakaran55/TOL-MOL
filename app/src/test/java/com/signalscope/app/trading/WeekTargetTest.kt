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
        // Month target 100.8 on a 100 fill: 0.8% clears the fee-free 0.5% but not US 5% or India's 1%.
        assertEquals(5.0, WeekTarget.minGapFor("us"), 0.0)
        assertEquals(1.0, WeekTarget.minGapFor("in"), 0.0)
        assertEquals(0.5, WeekTarget.minGapFor("de"), 0.0)
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

    // ── US: no short targets ──

    /** Daily bars from closes, each with a one-point range either side. */
    private fun daily(closes: List<Double>) = closes.mapIndexed { i, c ->
        com.signalscope.app.screener.DailyBar(java.time.LocalDate.parse("2026-01-01").plusDays(i.toLong()), c, c + 1.0, c - 1.0)
    }
    private fun line(from: Double, to: Double, n: Int) = (1..n).map { from + (to - from) * it / n }

    /** The ABNB shape: a slow base, a run to a 190.50 peak, a fall to 158, a recovery to 172. */
    private val abnb = daily(List(60) { 150.0 + it * 0.2 } + line(161.8, 190.5, 30) + line(190.5, 158.0, 30) + line(158.0, 172.0, 20))

    @Test fun `a US algo buy rests no target - it sells on its own rules`() {
        val p = WeekTarget.usPlan(watcher = false, bars = abnb, paid = 174.63, priceNow = null, preExisting = false)
        assertFalse(p.ok)
        assertTrue(p.why.contains("algo"))
    }

    @Test fun `standing monthly peaks - broken ones, and a rise still going, are not resistance`() {
        val peaks = WeekTarget.standingPeaks(abnb)
        // The base's top (161.8) was closed above in the run: support now, not resistance.
        // The recovery's last closes are still rising and too recent to be a peak.
        assertEquals(listOf(190.5), peaks.map { it.close })
    }

    @Test fun `a US watcher buy rests just under its last monthly peak`() {
        val p = WeekTarget.usPlan(true, abnb, 174.63, null, false)
        assertTrue(p.ok)
        assertEquals("monthpeak", p.kind)
        assertEquals(WeekTarget.floorCent(190.5 - WeekTarget.monthBuffer(abnb)!!), p.price, 1e-9)
        assertTrue(p.price < 190.5 && p.price > 188.0)
        assertEquals("just under its last monthly peak", p.label)
        assertTrue(p.detail.contains("190.50"))
    }

    @Test fun `a nearer peak too close for a US sale is passed over for the next one up`() {
        // A bounce to 178 then a dip to 165: two standing peaks, 178 and 190.50.
        val two = daily(abnb.take(120).map { it.close } + line(158.0, 178.0, 6) + line(178.0, 165.0, 14))
        assertEquals(listOf(178.0, 190.5), WeekTarget.standingPeaks(two).map { it.close }.sorted())
        val p = WeekTarget.usPlan(true, two, 170.0, null, false)
        assertEquals("monthpeak", p.kind)
        assertTrue(p.price > 189.0)        // 178 less its buffer is under 5% above 170
        // Every peak too close: no target, and it says which.
        val none = WeekTarget.usPlan(true, two, 186.0, null, false)
        assertFalse(none.ok); assertTrue(none.why.contains("190.50"))
    }

    @Test fun `above every peak of the year - the projected monthly zone stands in`() {
        val rising = daily(List(120) { 100.0 + it * 0.5 + (if (it % 2 == 0) 2.0 else -2.0) })
        val paid = rising.last().close
        val any = WeekTarget.usPlan(true, rising, paid, null, false, minGapPct = -100.0)
        assertEquals("monthzone", any.kind)
        assertEquals(WeekTarget.monthZoneBottom(rising)!!, any.price, 1e-9)
        val strict = WeekTarget.usPlan(true, rising, paid, null, false, minGapPct = 50.0)
        assertFalse(strict.ok); assertTrue(strict.why.contains("above every monthly peak"))
    }

    @Test fun `a US watcher target - no history, or held from before and already past it - none`() {
        assertFalse(WeekTarget.usPlan(true, null, 174.63, null, false).ok)
        val past = WeekTarget.usPlan(true, abnb, 174.63, priceNow = 195.0, preExisting = true)
        assertFalse(past.ok); assertTrue(past.why.contains("already above"))
    }

    @Test fun `the monthly zone is the campaign algo's own level, rounded down to the cent`() {
        // 90 rising daily bars: the projection carries the trend a month ahead.
        val bars = (0 until 90).map { i ->
            val c = 100.0 + i * 0.5 + (if (i % 2 == 0) 1.0 else -1.0)
            com.signalscope.app.screener.DailyBar(java.time.LocalDate.parse("2026-01-01").plusDays(i.toLong()), c, c + 1.0, c - 1.0)
        }
        val l = CampaignRules.levels(bars, WeekTarget.ZONE_ATR_FRAC, "projected")!!
        assertEquals("projected", l.source)
        val z = WeekTarget.monthZoneBottom(bars)!!
        assertEquals(WeekTarget.floorCent(l.monthRes - l.bufMonth), z, 1e-9)
        assertTrue(z > bars.last().close)     // a rising stock's zone sits above it
        assertNull(WeekTarget.monthZoneBottom(bars.take(10)))
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
