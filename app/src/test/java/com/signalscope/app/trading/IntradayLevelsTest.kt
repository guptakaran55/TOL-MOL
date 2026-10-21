package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import com.signalscope.app.util.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOCAL SUPPORT AND RESISTANCE ON THE WATCHER'S OWN BARS.
 *
 * These numbers do not move money by themselves — nothing sells on them. What
 * they do is sit next to a Buy button and next to a stop, which is arguably
 * worse: a wrong level here is acted on by a person who has no way to check it.
 *
 * So the properties pinned are the ones that decide whether the display can be
 * trusted: a level must be on the correct side of price, a guess must be
 * distinguishable from a level price has actually turned at, and the whole thing
 * must decline rather than invent an answer when there is not enough history.
 */
class IntradayLevelsTest {

    /**
     * A clean triangle wave between 90 and 110, ten bars up and ten down.
     *
     * Deliberately synthetic. A real series would make this a test of the
     * finder's taste rather than of the rules around it, and the finder already
     * earns its keep on the Discovery screen — what is being checked here is the
     * layer that decides what may be SHOWN.
     */
    private fun wave(bars: Int, from: Int = 0): List<CandleData> =
        (from until from + bars).map { i ->
            val t = i % 20
            val p = if (t < 10) 90.0 + 2.0 * t else 110.0 - 2.0 * (t - 10)
            CandleData(
                timestamp = 1_700_000_000L + i * 3600L,
                open = p, high = p + 0.5, low = p - 0.5, close = p, volume = 1000L)
        }

    // ── refusing to answer ──────────────────────────────────────────────────

    @Test fun `too little history produces nothing at all`() {
        // A young listing, or a feed that returned a handful of bars. The wrong
        // answer here is a confident pair of levels off nine candles.
        assertNull(IntradayLevels.from("ABC", wave(IntradayLevels.MIN_BARS - 1), 60))
        assertNotNull(IntradayLevels.from("ABC", wave(206), 60))
    }

    @Test fun `an empty series is not a crash`() {
        assertNull(IntradayLevels.from("ABC", emptyList(), 60))
    }

    // ── the levels themselves ───────────────────────────────────────────────

    @Test fun `a floor and a ceiling land on the right sides of price`() {
        // 206 bars ends mid-range, at 100 — the whole point of that length.
        val c = wave(206)
        assertEquals("the series ends mid-range", 100.0, c.last().close, 1e-9)
        val lv = IntradayLevels.from("abc", c, 60)!!
        assertTrue("support must sit below price", lv.support in 1.0..99.9999)
        assertTrue("resistance must sit above price", lv.resistance > 100.0)
        // And near the turning points the wave actually has.
        assertTrue("the floor is around the lows", lv.support in 88.0..92.0)
        assertTrue("the ceiling is around the highs", lv.resistance in 108.0..112.0)
    }

    @Test fun `repeatedly tested levels are reported as tested`() {
        // Ten full cycles: both levels were visited many times, and that count is
        // the difference between a level and a number.
        val lv = IntradayLevels.from("ABC", wave(206), 60)!!
        assertTrue("the floor was tested more than once", lv.supportTouches > 1)
        assertTrue("so was the ceiling", lv.resistanceTouches > 1)
    }

    @Test fun `the symbol is stamped on, and upper-cased`() {
        // Not decoration. These are read back through an object-level side
        // channel with more than one caller, and the name is what stops another
        // company's levels being painted onto this row.
        assertEquals("ABC", IntradayLevels.from(" abc ", wave(206), 60)!!.symbol)
    }

    @Test fun `the timeframe and the bar count travel with the answer`() {
        val lv = IntradayLevels.from("ABC", wave(206), 60)!!
        assertEquals(60, lv.barMin)
        assertEquals(206, lv.bars)
        assertEquals(100.0, lv.price, 1e-9)
    }

    @Test fun `the measurement price is kept, not the distance`() {
        // A stored percentage would be frozen at the moment of the pass and stop
        // being true the instant the market moved, while still looking precise.
        // Keeping both ends lets the panel recompute against whatever is current.
        val lv = IntradayLevels.from("ABC", wave(206), 60)!!
        val laterPrice = 104.0
        val d = IntradayLevels.distancePct(laterPrice, lv.resistance)!!
        val dAtMeasure = IntradayLevels.distancePct(lv.price, lv.resistance)!!
        assertTrue("the ceiling is nearer once price has risen toward it", d < dAtMeasure)
    }

    // ── a guess must look like a guess ──────────────────────────────────────

    @Test fun `a fallback level reports zero touches`() {
        // The finder falls back to pivot arithmetic off the newest bar when no
        // cluster of turning points qualifies. That number is a marker, not a
        // level, and the count is how the panel knows to say so. A monotonic ramp
        // has no turning points at all, which is the cleanest way to reach it.
        val ramp = (0 until 220).map { i ->
            val p = 50.0 + i * 0.5
            CandleData(1_700_000_000L + i * 3600L, p, p + 0.2, p - 0.2, p, 1000L)
        }
        val d = Indicators.findSupportResistanceDetailed(
            ramp.map { it.high }, ramp.map { it.low }, ramp.map { it.close })
        assertEquals("nothing turned above", 0, d.resistanceTouches)
        assertEquals("nothing turned below", 0, d.supportTouches)
    }

    @Test fun `the plain finder still answers exactly as it did`() {
        // Discovery has used this for a long time and its behaviour must not
        // change because a second caller wanted more detail out of it.
        val c = wave(206)
        val plain = Indicators.findSupportResistance(
            c.map { it.high }, c.map { it.low }, c.map { it.close })
        val detailed = Indicators.findSupportResistanceDetailed(
            c.map { it.high }, c.map { it.low }, c.map { it.close })
        assertEquals(plain.support, detailed.support, 1e-9)
        assertEquals(plain.resistance, detailed.resistance, 1e-9)
    }

    // ── the readings the panel takes off them ───────────────────────────────

    @Test fun `distance is signed outward from price`() {
        // A floor 3% below and a ceiling 3% above are opposite situations and an
        // unsigned distance reads identically for both.
        assertEquals(-3.0, IntradayLevels.distancePct(100.0, 97.0)!!, 1e-9)
        assertEquals(5.0, IntradayLevels.distancePct(100.0, 105.0)!!, 1e-9)
        assertNull(IntradayLevels.distancePct(0.0, 105.0))
        assertNull(IntradayLevels.distancePct(100.0, 0.0))
    }

    @Test fun `position in range reads zero at the floor and one at the ceiling`() {
        assertEquals(0.0, IntradayLevels.positionInRange(90.0, 90.0, 110.0)!!, 1e-9)
        assertEquals(1.0, IntradayLevels.positionInRange(110.0, 90.0, 110.0)!!, 1e-9)
        assertEquals(0.5, IntradayLevels.positionInRange(100.0, 90.0, 110.0)!!, 1e-9)
    }

    @Test fun `price outside the range is reported outside, not clamped`() {
        // The moment a level breaks is the moment this reading matters most, and
        // pinning it to the end of the bar would hide exactly that. The marker on
        // screen is clamped; the number behind it is not.
        assertTrue(IntradayLevels.positionInRange(115.0, 90.0, 110.0)!! > 1.0)
        assertTrue(IntradayLevels.positionInRange(85.0, 90.0, 110.0)!! < 0.0)
    }

    @Test fun `there is no position without both ends`() {
        assertNull(IntradayLevels.positionInRange(100.0, 0.0, 110.0))
        assertNull(IntradayLevels.positionInRange(100.0, 90.0, 0.0))
        assertNull("an inverted pair is not a range",
            IntradayLevels.positionInRange(100.0, 110.0, 90.0))
        assertNull(IntradayLevels.positionInRange(0.0, 90.0, 110.0))
    }
}
