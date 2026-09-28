package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limit an overnight entry may pay at the next open.
 *
 * THE CASE THIS EXISTS FOR. A German algo runs 120-minute bars against a market
 * that shuts at 17:30, so the day's last bar can only complete after the close
 * and its entry is always decided into a shut market. Parking that entry until
 * the morning is only safe if something refuses it when the name has gapped —
 * and the guard that was supposed to do it read the last COMPLETED bar, which at
 * the open is still last night's signal bar. It compared the signal with itself,
 * found no movement, and would have bought the gap.
 *
 * These cases pin the replacement: a limit measured from the signal price, tight
 * enough to refuse a gap, and never confused with the ladder's own cap — which
 * is measured from the live price and therefore rises with the gap it is meant
 * to be protecting against.
 */
class EntryCeilingTest {

    private val signal = 100.0     // the 18:00 bar's close
    private val stopPct = 4.0      // the German configs' stop
    private val atr = 1.50         // 14-day ATR in euros

    @Test fun `takes the tighter of the stop fraction and half an ATR`() {
        // stop fraction: 100 * (1 + 4*0.25/100) = 101.00
        // half an ATR:   100 + 1.50/2           = 100.75  ← binds
        val c = T212LiveMirror.entryCeiling(signal, stopPct, true, atr)
        assertEquals(100.75, c, 1e-9)
    }

    @Test fun `a placid name is held to the ATR line, a volatile one to the stop line`() {
        // Tiny ATR — volatility binds well inside the risk budget.
        assertEquals(100.10, T212LiveMirror.entryCeiling(signal, stopPct, true, 0.20), 1e-9)
        // Wide ATR — the risk budget binds first and caps how much drift is bought.
        assertEquals(101.00, T212LiveMirror.entryCeiling(signal, stopPct, true, 6.00), 1e-9)
    }

    @Test fun `the overnight gap that started all this is refused`() {
        val c = T212LiveMirror.entryCeiling(signal, stopPct, true, atr)
        val openedAt = 106.0                       // 6% gap up
        assertTrue("a 6% gap must be above the limit", openedAt > c)
    }

    @Test fun `a calm open is still allowed through`() {
        val c = T212LiveMirror.entryCeiling(signal, stopPct, true, atr)
        assertTrue("0.3% above the signal is inside the limit", 100.30 <= c)
    }

    @Test fun `the limit does not follow the market up`() {
        // The whole failure being fixed: the ladder's own cap is measured from the
        // live quote, so on a gap morning it sits ABOVE the gap and permits it.
        // This limit is measured from the signal and does not move at all.
        val fromSignal = T212LiveMirror.entryCeiling(signal, stopPct, true, atr)
        val ladderStyleCapAfterGap = 106.0 + 0.5 * atr      // what "live + half ATR" gives
        assertTrue("the ladder-style cap would permit the gap",
            ladderStyleCapAfterGap > 106.0)
        assertTrue("the signal-anchored limit refuses it", fromSignal < 106.0)
    }

    @Test fun `scales with each config's own risk appetite`() {
        // A 1% stop tolerates a quarter of a percent; an 8% stop, two percent.
        // Same shape, different appetite — with the ATR out of the way.
        assertEquals(100.25, T212LiveMirror.entryCeiling(signal, 1.0, true, 0.0), 1e-9)
        assertEquals(102.00, T212LiveMirror.entryCeiling(signal, 8.0, true, 0.0), 1e-9)
    }

    @Test fun `falls back to the ATR alone when the config has no stop`() {
        assertEquals(100.75, T212LiveMirror.entryCeiling(signal, 4.0, false, atr), 1e-9)
    }

    @Test fun `returns no limit when nothing can be measured`() {
        // No stop and no ATR leaves nothing to anchor to. 0 means "unlimited", so
        // the ladder's own cap applies — the behaviour that existed before, not a
        // new exposure.
        assertEquals(0.0, T212LiveMirror.entryCeiling(signal, 4.0, false, 0.0), 1e-9)
        // No signal price is the same story from the other end.
        assertEquals(0.0, T212LiveMirror.entryCeiling(0.0, 4.0, true, atr), 1e-9)
    }

    // ── THE FLOOR ───────────────────────────────────────────────────────────
    // The half nobody had. A ceiling alone stops a gap up and lets a nine-day
    // slide end in a purchase, which is the worse of the two: a fill below this
    // trade's own stop hands you a position whose every rule is anchored to a
    // price the market has left behind.

    @Test fun `the floor is where this trade's own stop would sit`() {
        // 4% stop on a 100.00 signal. Below 96 the strategy would already be out,
        // so there is no version of this entry left to take.
        assertEquals(96.0, T212LiveMirror.entryFloor(signal, 4.0, true, atr), 1e-9)
    }

    @Test fun `the floor is NOT the ceiling mirrored`() {
        // Deliberately asymmetric, and this is the point of the whole design: a
        // lower price is a BETTER entry, so it gets far more room than a higher
        // one. Ceiling 100.75, floor 96.00 — one euro above the signal is
        // refused, three euros below is fine.
        val ceil = T212LiveMirror.entryCeiling(signal, 4.0, true, atr)   // 100.75
        val fl = T212LiveMirror.entryFloor(signal, 4.0, true, atr)       //  96.00
        assertTrue("one euro ABOVE the signal must be refused", 101.00 > ceil)
        assertTrue("three euros BELOW must still be allowed", 97.00 > fl)
        assertTrue("the floor sits much further from the signal than the ceiling",
            (signal - fl) > (ceil - signal) * 3)
    }

    @Test fun `the nine-day slide is refused`() {
        // The worked example: signal 100.00, filled at 85.10 nine days later.
        // The strategy measures from 100, so it would open believing it was up
        // 15% while its stop sat above the fill.
        val fl = T212LiveMirror.entryFloor(signal, 4.0, true, atr)
        assertTrue("85.10 must be below the floor, i.e. refused", 85.10 <= fl)
    }

    @Test fun `two daily ranges stand in when no stop is configured`() {
        assertEquals(97.0, T212LiveMirror.entryFloor(signal, 4.0, false, atr), 1e-9)
    }

    @Test fun `no floor when nothing can be measured`() {
        assertEquals(0.0, T212LiveMirror.entryFloor(signal, 4.0, false, 0.0), 1e-9)
        assertEquals(0.0, T212LiveMirror.entryFloor(0.0, 4.0, true, atr), 1e-9)
    }

    @Test fun `a floor can never sit below zero`() {
        // A tiny price with a wide range must not produce a negative limit, which
        // would silently mean "no floor" to the caller.
        assertTrue(T212LiveMirror.entryFloor(1.0, 0.0, false, 5.0) >= 0.0)
    }
}
