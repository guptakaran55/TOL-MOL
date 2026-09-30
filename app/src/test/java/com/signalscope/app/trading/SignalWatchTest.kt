package com.signalscope.app.trading

import com.signalscope.app.data.SignalTrackStore
import com.signalscope.app.screener.DailyBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * "TURNING UP AT A FLOOR" AND ITS TRACK RECORD.
 */
class SignalWatchTest {

    private fun arr(vararg v: Double) = v

    // ── when it turned ──────────────────────────────────────────────────────

    @Test fun `the dip ending - both short slopes up now, not both up an hour ago`() {
        val s15 = arr(-0.3, -0.2, -0.1, -0.05, 0.02, 0.05)   // turned up in the last hour
        val s1h = arr(-0.4, -0.1, 0.03)
        assertTrue(MacdSlopes.turnedUp(s15, s1h))
    }

    @Test fun `no turn when both were already up an hour ago`() {
        // Sitting green all afternoon must not "turn up" on every pass.
        val s15 = arr(0.1, 0.1, 0.1, 0.1, 0.1, 0.1)
        val s1h = arr(0.2, 0.2, 0.2)
        assertFalse(MacdSlopes.turnedUp(s15, s1h))
    }

    @Test fun `no turn while either short slope is still falling`() {
        assertFalse(MacdSlopes.turnedUp(arr(-0.3, -0.2, -0.1, -0.05, 0.02, 0.05), arr(0.1, -0.02)))
        assertFalse(MacdSlopes.turnedUp(arr(0.1, 0.1, 0.1, 0.1, 0.1, -0.01), arr(-0.1, 0.1)))
    }

    @Test fun `too short a history is never a turn`() {
        assertFalse(MacdSlopes.turnedUp(arr(0.1), arr(0.1)))
    }

    @Test fun `all five newly green, and not merely still green`() {
        val s15 = DoubleArray(12) { if (it < 6) -0.1 else 0.1 }
        val s1h = arr(-0.1, -0.1, 0.1, 0.1)
        val s4h = arr(0.1, 0.1)
        assertTrue(MacdSlopes.turnedAllGreen(s15, s1h, s4h))
        val allAlong = DoubleArray(12) { 0.1 }
        assertFalse(MacdSlopes.turnedAllGreen(allAlong, arr(0.1, 0.1, 0.1, 0.1), s4h))
        assertFalse("4-hour still falling", MacdSlopes.turnedAllGreen(s15, s1h, arr(0.1, -0.1)))
    }

    @Test fun `the series ends on the same value the single reading gives`() {
        val closes = DoubleArray(80) { 100.0 + it * 0.3 + if (it % 7 == 0) 2.0 else 0.0 }
        assertEquals(MacdSlopes.latest(closes, 5)!!, MacdSlopes.series(closes, 5)!!.last(), 1e-12)
        assertNull(MacdSlopes.series(DoubleArray(10), 5))
    }

    // ── what happened next ──────────────────────────────────────────────────

    private fun bars(from: LocalDate, vararg closes: Double) =
        closes.mapIndexed { i, c -> DailyBar(from.plusDays(i.toLong()), close = c) }

    @Test fun `returns are measured from the alert price, one three and five bars on`() {
        val b = bars(LocalDate.of(2026, 9, 28), 100.0, 101.0, 99.0, 103.0, 104.0, 110.0)
        val (r1, r3, r5) = SignalWatch.outcomes(b, "2026-09-28", 100.0)
        assertEquals(1.0, r1!!, 1e-9)
        assertEquals(3.0, r3!!, 1e-9)
        assertEquals(10.0, r5!!, 1e-9)
    }

    @Test fun `days not traded yet stay unknown`() {
        val b = bars(LocalDate.of(2026, 9, 28), 100.0, 101.0)
        val (r1, r3, r5) = SignalWatch.outcomes(b, "2026-09-28", 100.0)
        assertEquals(1.0, r1!!, 1e-9)
        assertNull(r3); assertNull(r5)
    }

    @Test fun `trading days, not calendar days - a weekend counts for nothing`() {
        // Friday 2 Oct, then Monday 5 Oct: "one day later" is Monday's close.
        val b = listOf(DailyBar(LocalDate.of(2026, 10, 2), close = 100.0),
                       DailyBar(LocalDate.of(2026, 10, 5), close = 102.0))
        assertEquals(2.0, SignalWatch.outcomes(b, "2026-10-02", 100.0).first!!, 1e-9)
    }

    @Test fun `no bar for the alert's own day - nothing is guessed`() {
        val b = bars(LocalDate.of(2026, 9, 29), 100.0, 101.0, 102.0)
        val (r1, _, _) = SignalWatch.outcomes(b, "2026-09-28", 100.0)
        assertNull(r1)
    }

    @Test fun `one event per stock per rule per day`() {
        val a = SignalTrackStore.id(SignalTrackStore.RULE_FLOOR_TURN, "de", "hen", "2026-09-30")
        val b = SignalTrackStore.id(SignalTrackStore.RULE_FLOOR_TURN, "de", "HEN", "2026-09-30")
        assertEquals("case does not make a second event", a, b)
        assertNotEquals(a, SignalTrackStore.id(SignalTrackStore.RULE_ALL_GREEN, "de", "HEN", "2026-09-30"))
        assertNotEquals(a, SignalTrackStore.id(SignalTrackStore.RULE_FLOOR_TURN, "de", "HEN", "2026-10-01"))
    }
}
