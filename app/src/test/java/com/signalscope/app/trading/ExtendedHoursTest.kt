package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore.TrailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LINE BETWEEN "MAY RAISE THE PEAK" AND "MAY FIRE THE STOP".
 *
 * Extended-hours prints were invisible to the app entirely, so a US stop saw
 * nothing between 22:00 and 15:30 German time. Letting them in is right for the
 * high-water mark and dangerous for the trigger: those books are thin enough that
 * a couple of hundred shares set a level no real seller could have obtained, and
 * a stop that sold into one would be selling on somebody else's odd lot.
 *
 * So the two prices are deliberately separate doors, and these pin that they stay
 * separate — a single shared accessor would collapse the distinction silently.
 */
class ExtendedHoursTest {

    // 2026-09-18 is a Friday.
    private fun et(h: Int, m: Int, day: Int = 18): Long =
        java.time.ZonedDateTime.of(2026, 9, day, h, m, 0, 0,
            java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test fun `the regular session is the exchange's own hours`() {
        assertTrue("just after the open", MarkToMarket.inUsRegularSession(et(9, 31)))
        assertTrue("the open itself", MarkToMarket.inUsRegularSession(et(9, 30)))
        assertTrue("midday", MarkToMarket.inUsRegularSession(et(12, 0)))
        assertTrue("a minute before the close", MarkToMarket.inUsRegularSession(et(15, 59)))
    }

    @Test fun `pre-market and after-hours are outside it`() {
        assertFalse("pre-market", MarkToMarket.inUsRegularSession(et(5, 42)))
        assertFalse("one minute before the open", MarkToMarket.inUsRegularSession(et(9, 29)))
        assertFalse("the close is exclusive", MarkToMarket.inUsRegularSession(et(16, 0)))
        assertFalse("after hours", MarkToMarket.inUsRegularSession(et(18, 30)))
    }

    @Test fun `the German morning falls in US pre-market`() {
        // The case from the screenshots: 11:43 in Germany is 05:43 in New York.
        // The app showed yesterday's close while the broker showed a pre-market
        // quote, and the difference was read as latency. It was a closed market.
        assertFalse(MarkToMarket.inUsRegularSession(et(5, 43)))
    }

    @Test fun `a weekend is never the regular session`() {
        assertFalse("Saturday midday", MarkToMarket.inUsRegularSession(et(12, 0, day = 19)))
        assertFalse("Sunday midday", MarkToMarket.inUsRegularSession(et(12, 0, day = 20)))
    }

    // ── what each price is allowed to do ────────────────────────────────────

    @Test fun `an extended-hours high lifts the peak`() {
        // Bought at 100, ran to 112 in after-hours trade. The floor must follow it
        // up — this is the whole point of letting these prices in.
        val peak = ManualTrailingStop.nextPeak(105.0, 100.0,
            ManualTrailingStop.peakCandidate(TrailSource.TICK_PEAK, 112.0, 105.0))
        assertEquals(112.0, peak, 1e-9)
        assertEquals("and the floor with it", 103.04,
            ManualTrailingStop.levelFor(peak, 8.0), 1e-9)
    }

    @Test fun `an extended-hours low may not fire the stop`() {
        // The dangerous direction. A thin after-hours print at 95 sits below a
        // floor of 103.04, but the firing price is the regular-session one — and
        // on that price nothing has broken.
        val level = ManualTrailingStop.levelFor(112.0, 8.0)      // 103.04
        val regular = 106.0
        assertFalse("the regular price has not broken the floor",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK_PEAK, 95.0, regular), level))
        // And in tick mode, where the live price IS the trigger, the value handed
        // in is still the regular one — the sweep never passes the extended price
        // to the trigger side at all.
        assertFalse("tick mode fires on the regular price, not the odd lot",
            ManualTrailingStop.breached(
                ManualTrailingStop.triggerPrice(TrailSource.TICK, regular, regular), level))
    }

    @Test fun `a bar closing under the floor still fires normally`() {
        // The guard must not have made the stop unable to act. A genuine
        // regular-session close below the level sells, exactly as before.
        val level = ManualTrailingStop.levelFor(112.0, 8.0)      // 103.04
        assertTrue(ManualTrailingStop.breached(
            ManualTrailingStop.triggerPrice(TrailSource.TICK_PEAK, 99.0, 102.0), level))
    }

    @Test fun `a peak raised out of hours survives into the next session`() {
        // Overnight run, then a normal open. The floor must already be measured
        // from the overnight high rather than starting the day from yesterday's
        // close — that gap is what the whole change exists to close.
        var peak = ManualTrailingStop.nextPeak(0.0, 100.0, 104.0)   // regular session
        peak = ManualTrailingStop.nextPeak(peak, 100.0, 118.0)      // after hours
        peak = ManualTrailingStop.nextPeak(peak, 100.0, 110.0)      // next open, lower
        assertEquals("the overnight high stands", 118.0, peak, 1e-9)
        assertEquals(108.56, ManualTrailingStop.levelFor(peak, 8.0), 1e-9)
    }
}
