package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore.TrailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE HAND-SET TARGET, the other half of the hand-set trail.
 *
 * Pinned to numbers for the same reason the trail is: it decides when real money
 * leaves a position. The two properties worth having are that it NEVER MOVES —
 * unlike the floor, which follows the high — and that it never fires on silence.
 *
 * The third thing pinned here is the precedence between the two, because both
 * can genuinely be through at the same instant and the reason written into the
 * book is the part anyone reads afterwards.
 */
class ManualTakeProfitTest {

    private val entry = 100.0
    private val tp = 6.0

    // ── the level ───────────────────────────────────────────────────────────

    @Test fun `the target sits the chosen percentage above what you paid`() {
        assertEquals(106.0, ManualTakeProfit.targetFor(entry, tp), 1e-9)
        assertEquals(0.0, ManualTakeProfit.targetFor(entry, 0.0), 1e-9)
        assertEquals(0.0, ManualTakeProfit.targetFor(0.0, tp), 1e-9)
    }

    @Test fun `the target does not move when the position runs`() {
        // THE WHOLE DIFFERENCE FROM THE TRAIL, stated as a test so it cannot be
        // quietly "improved" into a second trailing stop. The floor is a function
        // of the PEAK and rises with it; the ceiling is a function of the FILL and
        // is the same number on every pass for the life of the position.
        var peak = 0.0
        val levels = mutableListOf<Double>()
        val targets = mutableListOf<Double>()
        listOf(101.0, 108.0, 117.0, 112.0).forEach { px ->
            peak = ManualTrailingStop.nextPeak(peak, entry, px)
            levels.add(ManualTrailingStop.levelFor(peak, 8.0))
            targets.add(ManualTakeProfit.targetFor(entry, tp))
        }
        assertTrue("the floor climbed", levels.last() > levels.first())
        assertEquals("the ceiling did not", 1, targets.distinct().size)
        assertEquals(106.0, targets.first(), 1e-9)
    }

    @Test fun `anchoring a target on the high would make it unreachable`() {
        // Why the anchor is the fill and not the peak. Measured from the high, the
        // target runs away upward exactly as fast as price approaches it: price IS
        // the high on a new high, so a positive percentage above it is always in
        // front. Demonstrated rather than asserted in prose, because it reads as a
        // reasonable idea right up until the arithmetic is written down.
        var peak = 0.0
        listOf(104.0, 112.0, 130.0).forEach { px ->
            peak = ManualTrailingStop.nextPeak(peak, entry, px)
            val wrong = ManualTakeProfit.targetFor(peak, tp)
            assertFalse("a target hung off the peak can never be reached",
                ManualTakeProfit.reached(px, wrong))
        }
        // The real one, measured from the fill, was reached on the first of those.
        assertTrue(ManualTakeProfit.reached(104.0, ManualTakeProfit.targetFor(entry, 3.0)))
    }

    // ── firing ──────────────────────────────────────────────────────────────

    @Test fun `silence is not an arrival`() {
        // The mirror of the stop's rule, and needed just as much. A quiet feed
        // hands over 0, and 0 is below every target ever set — but it is also
        // below every stop, and the danger only became obvious on one side. An
        // absent quote must decide nothing in either direction.
        assertFalse(ManualTakeProfit.reached(0.0, 106.0))
        assertFalse("no target means no rule", ManualTakeProfit.reached(120.0, 0.0))
        assertTrue(ManualTakeProfit.reached(106.01, 106.0))
        assertTrue("exactly on the level counts", ManualTakeProfit.reached(106.0, 106.0))
        assertFalse(ManualTakeProfit.reached(105.99, 106.0))
    }

    @Test fun `a quiet feed cannot fire the tick-mode target either`() {
        // Same bound, same reason, other direction: the sweep hands over 0 when the
        // mark is older than the freshness cap, and it must not fall back to the
        // bar close, which is a mode the user did not choose.
        val target = ManualTakeProfit.targetFor(entry, tp)
        assertFalse(ManualTakeProfit.reached(
            ManualTrailingStop.triggerPrice(TrailSource.TICK, 0.0, 130.0), target))
    }

    @Test fun `bar-close mode ignores a spike through the target`() {
        // Touched 130 intraday, closed at 104. On closes that is not a sale, which
        // is the same treatment the stop gives a wick down — one clock for both
        // levels, so a single bar cannot be a hit for one and a miss for the other.
        val target = ManualTakeProfit.targetFor(entry, tp)   // 106
        assertFalse(ManualTakeProfit.reached(
            ManualTrailingStop.triggerPrice(TrailSource.BAR_CLOSE, 130.0, 104.0), target))
        assertTrue("and a close above it is",
            ManualTakeProfit.reached(
                ManualTrailingStop.triggerPrice(TrailSource.BAR_CLOSE, 130.0, 107.0), target))
    }

    @Test fun `tick mode takes the spike`() {
        val target = ManualTakeProfit.targetFor(entry, tp)
        assertTrue(ManualTakeProfit.reached(
            ManualTrailingStop.triggerPrice(TrailSource.TICK, 130.0, 104.0), target))
    }

    // ── tighter, for a ceiling ──────────────────────────────────────────────

    @Test fun `tighter means closer to price, which for a ceiling is lower`() {
        // The exact inversion of the stop's rule, and the reason both exist as
        // named functions. Reading "tighter" as "higher" here would pick the
        // further-away target every time while looking careful — and it is the
        // same word that means the opposite one file over.
        assertEquals(112.0, ManualTakeProfit.tighterOf(112.0, 125.0), 1e-9)
        assertEquals("a side with no opinion never wins",
            125.0, ManualTakeProfit.tighterOf(0.0, 125.0), 1e-9)
        assertEquals(112.0, ManualTakeProfit.tighterOf(112.0, 0.0), 1e-9)
        assertEquals(0.0, ManualTakeProfit.tighterOf(0.0, 0.0), 1e-9)
        // And the two really are opposites on the same pair of numbers.
        assertTrue(ManualTakeProfit.tighterOf(112.0, 125.0) <
            ManualTrailingStop.tighterOf(112.0, 125.0))
    }

    // ── which one fires ─────────────────────────────────────────────────────

    @Test fun `only the level actually reached fires`() {
        val floor = 92.0
        val ceiling = 106.0
        assertEquals(ManualTakeProfit.Verdict.HOLD,
            ManualTakeProfit.decide(99.0, floor, ceiling))
        assertEquals(ManualTakeProfit.Verdict.STOP,
            ManualTakeProfit.decide(91.0, floor, ceiling))
        assertEquals(ManualTakeProfit.Verdict.TARGET,
            ManualTakeProfit.decide(107.0, floor, ceiling))
    }

    @Test fun `either level works with the other switched off`() {
        assertEquals(ManualTakeProfit.Verdict.TARGET,
            ManualTakeProfit.decide(107.0, 0.0, 106.0))
        assertEquals(ManualTakeProfit.Verdict.STOP,
            ManualTakeProfit.decide(91.0, 92.0, 0.0))
        assertEquals("nothing set, nothing fires",
            ManualTakeProfit.Verdict.HOLD, ManualTakeProfit.decide(107.0, 0.0, 0.0))
    }

    @Test fun `when both are through, the stop is the reason recorded`() {
        // NOT A HYPOTHETICAL. The floor hangs off the peak and the ceiling off the
        // fill, so a position that ran to 200 and fell back to 150 sits below an
        // 8% trailed floor (184) while still far above a 6% target (106). That is
        // exactly what switching a target on after a big move looks like.
        val peak = ManualTrailingStop.nextPeak(0.0, entry, 200.0)
        val floor = ManualTrailingStop.levelFor(peak, 8.0)          // 184
        val ceiling = ManualTakeProfit.targetFor(entry, tp)         // 106
        assertTrue("both really are through", 150.0 <= floor && 150.0 >= ceiling)
        assertEquals("a trade that gave back the move is not a target being met",
            ManualTakeProfit.Verdict.STOP, ManualTakeProfit.decide(150.0, floor, ceiling))
    }

    @Test fun `a target already passed cannot rest at a broker`() {
        // The Indian branch. A sell trigger has to sit ABOVE the last price or
        // Zerodha refuses it, so a target the market is already past has nowhere to
        // be parked — and doing nothing there would leave the setting looking
        // broken in the one situation where a person is watching it. The rule this
        // pins is when that case is detected, not what is then done about it.
        val ceiling = ManualTakeProfit.targetFor(entry, tp)         // 106
        val ltp = 111.0
        assertTrue("the market is past the level", ManualTakeProfit.reached(ltp, ceiling))
        assertFalse("so it cannot be a resting instruction", ceiling > ltp)
        // Below the market it rests happily, which is the ordinary case.
        assertFalse(ManualTakeProfit.reached(103.0, ceiling))
        assertTrue(ceiling > 103.0)
    }

    @Test fun `the ceiling is only ever pulled tighter, never loosened`() {
        // What the broker-side move is allowed to do, mirroring the floor's
        // ratchet. A target already closer to price than the user's stays put; one
        // further away comes down to theirs. It never travels up, because that
        // would move a standing instruction away from the user's own decision.
        val userWants = 118.0
        assertEquals("further away comes down",
            118.0, ManualTakeProfit.tighterOf(130.0, userWants), 1e-9)
        assertEquals("already closer stays",
            112.0, ManualTakeProfit.tighterOf(112.0, userWants), 1e-9)
    }
}
