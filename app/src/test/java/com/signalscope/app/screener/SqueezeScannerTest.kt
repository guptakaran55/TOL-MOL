package com.signalscope.app.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The squeeze thresholds are calibrated numbers, not opinions — 1.5× and 2×
 * mean something specific, and a scanner that is subtly wrong would report
 * compressions that are not there. So it is tested against series whose answer
 * is known by construction rather than against the market.
 */
class SqueezeScannerTest {

    private fun bars(n: Int, f: (Int) -> Triple<Double, Double, Double>): List<DailyBar> {
        val start = LocalDate.of(2026, 1, 1)
        return (0 until n).map { i ->
            val (c, h, l) = f(i)
            DailyBar(start.plusDays(i.toLong()), c, h, l)
        }
    }

    /**
     * Dead-flat price with a steady daily range: the bands collapse to nothing
     * while the channel keeps its width, so the bands fit inside even the
     * NARROWEST channel — the strongest squeeze, which is 1.0 and not 2.0.
     *
     * The original version of this test asserted 2.0 and passed, because the
     * scanner and the test shared the same inverted idea of which end was tight.
     * A test written from the same misunderstanding as the code confirms the
     * misunderstanding; only the 40%-of-the-market number gave it away.
     */
    @Test
    fun aQuietStockScoresTheStrongestTier() {
        val v = SqueezeScanner.scan(bars(120) { Triple(100.0, 100.5, 99.5) })
        assertTrue("should have been scoreable", v.ok)
        assertEquals("a flat price in a steady range is high compression: 1.0",
            1.0, v.tier, 1e-9)
        assertTrue("should report a long compression, got ${v.days}", v.days > 20)
        assertFalse("nothing has released", v.fired)
    }

    /**
     * The ordering that matters: a stock that only just qualifies must land on a
     * WIDER tier than a dead-quiet one. Without this, the inversion could come
     * back and every test above would still pass.
     */
    @Test
    fun aLooserStockScoresAWiderTierThanAQuietOne() {
        val quiet = SqueezeScanner.scan(bars(120) { Triple(100.0, 100.5, 99.5) })
        // Same daily range, but the close wanders — wider bands, looser fit.
        val looser = SqueezeScanner.scan(bars(120) { i ->
            val c = 100.0 + (if (i % 2 == 0) 1.6 else -1.6)
            Triple(c, c + 0.5, c - 0.5)
        })
        assertTrue(quiet.ok && looser.ok)
        assertTrue("a quiet stock must score at least as strong (smaller) a tier " +
            "than a wandering one: quiet=${quiet.tier} looser=${looser.tier}",
            quiet.tier <= looser.tier || looser.tier == 0.0)
    }

    /** A strong trend widens the bands past the channel — the opposite case. */
    @Test
    fun aTrendingStockIsNotSqueezed() {
        val v = SqueezeScanner.scan(bars(120) { i ->
            val c = 100.0 * Math.pow(1.02, i.toDouble())
            Triple(c, c * 1.005, c * 0.995)
        })
        assertTrue(v.ok)
        assertEquals("a stock moving 2% a day is not coiled", 0.0, v.tier, 1e-9)
    }

    /** Quiet for a long stretch, then a decisive break on the last bar. */
    @Test
    fun aBreakoutFiresAndReportsWhatItBrokeOutOf() {
        val v = SqueezeScanner.scan(bars(120) { i ->
            if (i < 119) Triple(100.0, 100.5, 99.5)
            else Triple(118.0, 119.0, 117.0)
        })
        assertTrue(v.ok)
        assertTrue("the release should be reported", v.fired)
        assertTrue("it should say how tight the coil was", v.firedFrom > 0.0)
        assertEquals("and it is no longer squeezed", 0.0, v.tier, 1e-9)
        assertTrue("an upward break should lean positive, got ${v.momentum}", v.momentum > 0.0)
    }

    /**
     * The failure this most needed guarding against: a feed that drops the high
     * and low sends flat bars, and a scanner that trusted them would report a
     * permanent maximum squeeze on every stock in the universe.
     */
    @Test
    fun barsWithNoRangeAreRefusedRatherThanScored() {
        val flat = (0 until 120).map {
            DailyBar(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), 100.0)
        }
        val v = SqueezeScanner.scan(flat)
        assertFalse("no range means no verdict, not a maximum one", v.ok)
        assertEquals(0.0, v.tier, 1e-9)
    }

    /**
     * Weekly aggregation. Week boundaries are the kind of thing that looks right
     * and is quietly off by a day, and the whole weekly study rests on it.
     */
    @Test
    fun weeklyBarsTakeTheWeeksTrueRangeAndLastClose() {
        // Mon 5 Jan 2026 through Fri 16 Jan: two full trading weeks.
        val start = LocalDate.of(2026, 1, 5)
        val days = ArrayList<DailyBar>()
        var d = start
        var i = 0
        while (d.isBefore(LocalDate.of(2026, 1, 17))) {
            if (d.dayOfWeek.value <= 5) {
                // Deliberately spiky, so a wrong high or low shows up immediately.
                days.add(DailyBar(d, 100.0 + i, 100.0 + i + (if (i == 2) 9.0 else 1.0),
                    100.0 + i - (if (i == 7) 8.0 else 1.0)))
                i++
            }
            d = d.plusDays(1)
        }
        val wk = SqueezeScanner.toWeekly(days)
        assertEquals("two calendar weeks in, two weekly bars out", 2, wk.size)
        // Week 1 = the first five entries (i = 0..4)
        assertEquals("close of the week is the last day's close", 104.0, wk[0].close, 1e-9)
        assertEquals("high of the week is the highest day's high", 111.0, wk[0].high, 1e-9)
        assertEquals("low of the week is the lowest day's low", 99.0, wk[0].low, 1e-9)
        // Week 2 = i = 5..9, with the deep low on i = 7
        assertEquals(109.0, wk[1].close, 1e-9)
        assertEquals(99.0, wk[1].low, 1e-9)
    }

    /** A week split across a year boundary must stay one bar, not two stubs. */
    @Test
    fun aWeekSpanningNewYearStaysOneBar() {
        val days = listOf(
            DailyBar(LocalDate.of(2025, 12, 30), 10.0, 11.0, 9.0),   // Tue
            DailyBar(LocalDate.of(2025, 12, 31), 12.0, 13.0, 11.0),  // Wed
            DailyBar(LocalDate.of(2026, 1, 1), 14.0, 15.0, 13.0),    // Thu
            DailyBar(LocalDate.of(2026, 1, 2), 16.0, 17.0, 15.0)     // Fri
        )
        val wk = SqueezeScanner.toWeekly(days)
        assertEquals("all four days share one Monday, so one weekly bar", 1, wk.size)
        assertEquals(16.0, wk[0].close, 1e-9)
        assertEquals(17.0, wk[0].high, 1e-9)
        assertEquals(9.0, wk[0].low, 1e-9)
    }

    /** The weekly series must be short enough to be plausible and long enough to
     *  score — a year of dailies is only about 52 weeks. */
    @Test
    fun aQuietStockIsAlsoSqueezedOnTheWeeklyClock() {
        val daily = bars(600) { Triple(100.0, 100.5, 99.5) }
        val weekly = SqueezeScanner.toWeekly(daily)
        assertTrue("600 calendar days should fold to roughly 86 weeks, got ${weekly.size}",
            weekly.size in 80..90)
        val v = SqueezeScanner.scan(weekly)
        assertTrue("a flat stock is coiled on any clock", v.ok && v.tier > 0.0)
    }

    @Test
    fun tooLittleHistoryIsRefused() {
        val v = SqueezeScanner.scan(bars(20) { Triple(100.0, 100.5, 99.5) })
        assertFalse("20 bars cannot support a 20-period study of a 20-period study", v.ok)
    }

    /** Momentum must follow direction, since its sign is what suggests which way
     *  a release will go. */
    @Test
    fun momentumSignFollowsDirection() {
        val up = SqueezeScanner.scan(bars(120) { i ->
            val c = 100.0 + i * 0.05; Triple(c, c + 0.3, c - 0.3)
        })
        val down = SqueezeScanner.scan(bars(120) { i ->
            val c = 100.0 - i * 0.05; Triple(c, c + 0.3, c - 0.3)
        })
        assertTrue(up.ok && down.ok)
        assertTrue("a drifting-up series should read positive", up.momentum > 0.0)
        assertTrue("a drifting-down series should read negative", down.momentum < 0.0)
    }
}
