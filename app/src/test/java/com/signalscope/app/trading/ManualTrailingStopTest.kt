package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore.TrailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A trailing stop decides when real money leaves a position, so the arithmetic is
 * pinned to numbers rather than trusted. The two properties that make it worth
 * having at all are that it never retreats and that it never fires on silence —
 * both are asserted directly, not inferred.
 */
class ManualTrailingStopTest {

    private val entry = 100.0
    private val pct = 8.0

    @Test fun `the floor sits the chosen percentage below the peak`() {
        assertEquals(101.20, ManualTrailingStop.levelFor(110.0, pct), 1e-9)
        assertEquals(0.0, ManualTrailingStop.levelFor(110.0, 0.0), 1e-9)
        assertEquals(0.0, ManualTrailingStop.levelFor(0.0, pct), 1e-9)
    }

    @Test fun `a position that never rose is still protected from its cost`() {
        // Bought at 100 and it fell immediately. The peak must not be zero, or the
        // stop would be zero and the position would carry no floor at all.
        val peak = ManualTrailingStop.nextPeak(0.0, entry, 94.0)
        assertEquals(100.0, peak, 1e-9)
        assertEquals(92.0, ManualTrailingStop.levelFor(peak, pct), 1e-9)
    }

    @Test fun `the high-water mark only ever rises`() {
        var p = ManualTrailingStop.nextPeak(0.0, entry, 105.0)
        p = ManualTrailingStop.nextPeak(p, entry, 118.0)
        assertEquals(118.0, p, 1e-9)
        p = ManualTrailingStop.nextPeak(p, entry, 99.0)   // a fall must not lower it
        assertEquals(118.0, p, 1e-9)
    }

    @Test fun `the level never retreats, because the peak never does`() {
        // The monotonicity is a CONSEQUENCE of the high-water mark, not a separate
        // guard. An earlier version kept a stored "previous level" and a test that
        // exercised it — while production recomputed from the peak and never called
        // it, so the test proved a property no position relied on.
        var peak = 0.0
        var last = 0.0
        listOf(104.0, 111.0, 103.0, 97.0, 112.0).forEach { px ->
            peak = ManualTrailingStop.nextPeak(peak, entry, px)
            val level = ManualTrailingStop.levelFor(peak, pct)
            assertTrue("the floor may never fall: $last -> $level", level >= last - 1e-9)
            last = level
        }
    }

    @Test fun `widening the percentage does lower the floor, and should`() {
        // The one case that legitimately moves it down. That is the user changing
        // their mind, not the stop retreating on its own — a ratchet here would
        // have frozen the old level and silently ignored the new setting.
        //
        // THE NAME IS THE ASSERTION. This test was called "lowering the
        // percentage" while asserting 8% then 10% — which is raising it. The
        // numbers were right and the sentence was backwards, which is the worse
        // failure of the two: nobody re-reads arithmetic they have been told the
        // meaning of.
        val peak = ManualTrailingStop.nextPeak(0.0, entry, 120.0)
        // 8% below 120 is the TIGHTER floor; widening to 10% drops it.
        assertEquals(110.4, ManualTrailingStop.levelFor(peak, 8.0), 1e-9)
        assertEquals(108.0, ManualTrailingStop.levelFor(peak, 10.0), 1e-9)
        assertTrue("a wider percentage must sit lower",
            ManualTrailingStop.levelFor(peak, 10.0) < ManualTrailingStop.levelFor(peak, 8.0))
        // And the other direction, so the name can never drift off the numbers
        // again without a failure: tightening RAISES the floor.
        assertTrue("a tighter percentage must sit higher",
            ManualTrailingStop.levelFor(peak, 5.0) > ManualTrailingStop.levelFor(peak, 8.0))
    }

    @Test fun `silence is not a breach`() {
        // No quote is not a low price. Selling on a missing number is how a
        // rate-limited call once read as "you own nothing".
        assertFalse(ManualTrailingStop.breached(0.0, 92.0))
        assertFalse("no level means no rule", ManualTrailingStop.breached(50.0, 0.0))
        assertTrue(ManualTrailingStop.breached(91.99, 92.0))
        assertTrue("exactly on the level counts", ManualTrailingStop.breached(92.0, 92.0))
    }

    @Test fun `tighter means closer to price, which for a long is higher`() {
        // The trap: "tighter" reads as "lower" and would pick the looser stop every
        // time while looking careful.
        assertEquals(96.0, ManualTrailingStop.tighterOf(96.0, 92.0), 1e-9)
        assertEquals("a side with no opinion never wins",
            92.0, ManualTrailingStop.tighterOf(0.0, 92.0), 1e-9)
        assertEquals(96.0, ManualTrailingStop.tighterOf(96.0, 0.0), 1e-9)
        assertEquals(0.0, ManualTrailingStop.tighterOf(0.0, 0.0), 1e-9)
    }

    @Test fun `bar-close mode ignores the intraday spike entirely`() {
        // Spiked to 120 intraday, closed at 104. On closes the peak is 104, so the
        // floor stays low and the position is not sold on the fade.
        val peak = ManualTrailingStop.nextPeak(0.0, entry,
            ManualTrailingStop.peakCandidate(TrailSource.BAR_CLOSE, 120.0, 104.0))
        assertEquals(104.0, peak, 1e-9)
        val level = ManualTrailingStop.levelFor(peak, pct)
        assertFalse("a close of 104 is nowhere near a floor of 95.68",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.BAR_CLOSE, 103.0, 104.0), level))
    }

    @Test fun `tick mode captures the spike and can fire inside the bar`() {
        val peak = ManualTrailingStop.nextPeak(0.0, entry,
            ManualTrailingStop.peakCandidate(TrailSource.TICK, 120.0, 104.0))
        assertEquals(120.0, peak, 1e-9)
        val level = ManualTrailingStop.levelFor(peak, pct)   // 110.40
        assertTrue("a live price of 109 is through a 110.40 floor",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK, 109.0, 118.0), level))
    }

    @Test fun `tick-peak mode lifts on the wick but waits for a close to fire`() {
        // The whole reason the third option exists: the peak follows the spike,
        // the trigger does not.
        val peak = ManualTrailingStop.nextPeak(0.0, entry,
            ManualTrailingStop.peakCandidate(TrailSource.TICK_PEAK, 120.0, 104.0))
        assertEquals("the wick raised the mark", 120.0, peak, 1e-9)
        val level = ManualTrailingStop.levelFor(peak, pct)   // 110.40
        assertFalse("a live dip to 109 must NOT fire in this mode",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK_PEAK, 109.0, 118.0), level))
        assertTrue("but a bar closing at 109 does",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK_PEAK, 109.0, 109.0), level))
    }

    @Test fun `a rising position drags its floor up behind it`() {
        var peak = 0.0
        var level = 0.0
        listOf(100.0, 106.0, 113.0, 119.0, 117.0, 111.0).forEach { px ->
            peak = ManualTrailingStop.nextPeak(peak, entry, px)
            level = ManualTrailingStop.levelFor(peak, pct)
        }
        assertEquals("the peak was 119", 119.0, peak, 1e-9)
        assertEquals("so the floor is 8% under it", 109.48, level, 1e-9)
        assertTrue("and 111 has not yet broken it", 111.0 > level)
    }

    @Test fun `a quiet feed cannot fire the tick-mode stop`() {
        // What the price-freshness cap buys. The sweep hands over 0 when the mark
        // is older than TICK_MAX_AGE_MS, and 0 must read as silence — not as a
        // price of zero, which is below every floor ever set and would sell the
        // whole book the moment a feed hiccupped.
        val peak = ManualTrailingStop.nextPeak(0.0, entry, 120.0)
        val level = ManualTrailingStop.levelFor(peak, pct)   // 110.40
        assertFalse("no recent tick means no fire",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK, 0.0, 104.0), level))
        // And it must NOT quietly fall back to the bar close — that is the OTHER
        // radio button, and silently becoming it would be a mode the user did not
        // choose selling stock under a name they did choose.
        assertEquals(0.0,
            ManualTrailingStop.triggerPrice(TrailSource.TICK, 0.0, 104.0), 1e-9)
    }

    @Test fun `the tick freshness bound is minutes, not the general grace window`() {
        // Pinned as a number because the whole defect was a bound that LOOKED
        // right: a streamed mark carries no bar size, so the general rule floors
        // it at 45 minutes and "live ticks" quietly meant three quarters of an
        // hour. Against a sweep that runs every 15 minutes this has to stay well
        // under that, or a pass that just priced the symbol would not qualify.
        assertTrue("must be tighter than the 45-minute general window",
            ManualTrailingStop.TICK_MAX_AGE_MS < 45 * 60 * 1000L)
        assertTrue("but comfortably longer than the 15-minute sweep interval is short",
            ManualTrailingStop.TICK_MAX_AGE_MS in (5 * 60 * 1000L)..(15 * 60 * 1000L))
    }

    // ── pricing the sweep's own symbols ─────────────────────────────────────

    /** Does this mode need a live price fetched before it can judge? */
    private fun needsOwnPrice(pct: Double, src: TrailSource) =
        pct > 0.0 && src != TrailSource.BAR_CLOSE

    @Test fun `the tick-driven modes fetch their own price, bar close does not`() {
        // The stop used to read whatever an asynchronous background refresh had
        // left behind. On any pass where that had not landed, the mark was a whole
        // tick old and the ten-minute rule refused it — so with the app closed the
        // trail declined most of the time, waiting on a thread rather than on the
        // market. It now asks for what it needs.
        assertTrue("live ticks judges a price, so it must have a current one",
            needsOwnPrice(8.0, TrailSource.TICK))
        assertTrue("so does the wick-following mode, for its peak",
            needsOwnPrice(8.0, TrailSource.TICK_PEAK))
        assertFalse("bar close reads completed candles and never this",
            needsOwnPrice(8.0, TrailSource.BAR_CLOSE))
    }

    @Test fun `a disarmed trail fetches nothing at all`() {
        // The high-water mark still updates when the trail is off, but from bars
        // already in hand. Spending a quote per holding per pass for a stop that
        // cannot fire is a cost with no outcome attached.
        listOf(TrailSource.TICK, TrailSource.TICK_PEAK, TrailSource.BAR_CLOSE).forEach {
            assertFalse("no percentage set, nothing to judge", needsOwnPrice(0.0, it))
        }
    }

    @Test fun `the pricing bound sits inside the firing bound`() {
        // A price fetched at the top of the sweep must still be actionable when
        // the loop reaches the last holding. Equal bounds would make that a
        // coin-flip on a slow pass.
        val fetchIfOlderThan = ManualTrailingStop.TICK_MAX_AGE_MS / 2
        assertTrue("refreshed well before it could expire",
            fetchIfOlderThan < ManualTrailingStop.TICK_MAX_AGE_MS)
        assertEquals(5 * 60 * 1000L, fetchIfOlderThan)
    }
}
