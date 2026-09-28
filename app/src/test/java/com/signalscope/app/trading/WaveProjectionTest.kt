package com.signalscope.app.trading

import com.signalscope.app.util.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PROJECTED CHANNEL, now computed for every watcher name instead of one at
 * a time behind a button.
 *
 * It was already trusted enough to size real targets on the India side, so what
 * is pinned here is not the arithmetic's taste but the properties that decide
 * whether it can be shown UNPROMPTED next to a Buy button: that it declines
 * rather than guesses on thin history, that its channel brackets the trend
 * rather than the price, and that it follows the trend's direction.
 *
 * The distinction the display rests on is that this is the only PREDICTION on
 * the panel. Everything beside it is a measurement of what happened.
 */
class WaveProjectionTest {

    private fun trend(n: Int, start: Double, perBar: Double, wobble: Double = 0.0): List<Double> =
        (0 until n).map { i ->
            // A deterministic saw so the residual spread is a known, repeatable size.
            val w = if (wobble == 0.0) 0.0 else wobble * (if (i % 4 < 2) 1.0 else -1.0)
            start + perBar * i + w
        }

    // ── when it refuses ─────────────────────────────────────────────────────

    @Test fun `thin history produces no projection at all`() {
        // Sixty closes is the floor, and below it the fifty-day average is still
        // warming up. Extrapolating a trend line that has not yet established
        // itself is the one failure mode that would look most confident.
        assertNull(Indicators.projectWaveRange(trend(59, 100.0, 0.1), 20))
        assertNotNull(Indicators.projectWaveRange(trend(60, 100.0, 0.1), 20))
        assertNull(Indicators.projectWaveRange(emptyList(), 20))
    }

    // ── the shape of the answer ─────────────────────────────────────────────

    @Test fun `the channel brackets the trend, not the price`() {
        val p = Indicators.projectWaveRange(trend(200, 100.0, 0.2, wobble = 1.5), 20)!!
        assertTrue("floor below middle", p.projectedFloor < p.projectedMidpoint)
        assertTrue("ceiling above middle", p.projectedCeiling > p.projectedMidpoint)
        assertEquals(20, p.daysAhead)
    }

    @Test fun `a rising trend projects above where it is now`() {
        // The property the display leans on when it says a floor can legitimately
        // sit ABOVE today's price: in an uptrend the channel is rising past you,
        // and that is information rather than a bug to be hidden.
        val closes = trend(200, 100.0, 0.25)
        val p = Indicators.projectWaveRange(closes, 20)!!
        assertTrue("the projected middle is ahead of the last close",
            p.projectedMidpoint > closes.last() - 5.0)
        val flat = Indicators.projectWaveRange(trend(200, 100.0, 0.0), 20)!!
        assertTrue("and a flat trend does not run away",
            Math.abs(flat.projectedMidpoint - 100.0) < 1.0)
    }

    @Test fun `a falling trend projects downward`() {
        val down = Indicators.projectWaveRange(trend(200, 200.0, -0.25), 20)!!
        val up = Indicators.projectWaveRange(trend(200, 200.0, 0.25), 20)!!
        assertTrue("direction is carried through", down.projectedMidpoint < up.projectedMidpoint)
    }

    @Test fun `a wilder stock gets a wider channel`() {
        // Two standard deviations of how far price strays from its own trend, so
        // the width is the stock's own habit rather than a fixed percentage. A
        // channel that ignored that would be too tight on one name and useless on
        // another, which is exactly what a fixed band does.
        val calm = Indicators.projectWaveRange(trend(200, 100.0, 0.1, wobble = 0.5), 20)!!
        val wild = Indicators.projectWaveRange(trend(200, 100.0, 0.1, wobble = 6.0), 20)!!
        val calmWidth = calm.projectedCeiling - calm.projectedFloor
        val wildWidth = wild.projectedCeiling - wild.projectedFloor
        assertTrue("the volatile one is bracketed more widely, $calmWidth vs $wildWidth",
            wildWidth > calmWidth * 3)
    }

    @Test fun `a dead flat series has essentially no channel`() {
        // Nothing has strayed from the trend, so there is nothing to bracket. It
        // must not invent a width, and it must not divide by anything either.
        val p = Indicators.projectWaveRange(trend(200, 50.0, 0.0), 20)!!
        assertTrue(p.projectedCeiling - p.projectedFloor < 0.5)
    }

    @Test fun `the horizon is honoured`() {
        val near = Indicators.projectWaveRange(trend(200, 100.0, 0.3), 5)!!
        val far = Indicators.projectWaveRange(trend(200, 100.0, 0.3), 40)!!
        assertEquals(5, near.daysAhead)
        assertEquals(40, far.daysAhead)
        assertTrue("looking further along a rising trend lands higher",
            far.projectedMidpoint > near.projectedMidpoint)
    }

    // ── what the row does with it ───────────────────────────────────────────

    /** The distance the strip prints, signed from the price outward. */
    private fun floorOffsetPct(price: Double, floor: Double): Double? =
        if (price <= 0.0 || floor <= 0.0) null else (floor / price - 1.0) * 100.0

    @Test fun `a floor above today's price reads positive, not as an error`() {
        assertTrue(floorOffsetPct(100.0, 104.0)!! > 0.0)
        assertTrue(floorOffsetPct(100.0, 96.0)!! < 0.0)
        assertNull(floorOffsetPct(0.0, 96.0))
        assertNull("an absent projection prints nothing", floorOffsetPct(100.0, 0.0))
    }
}
