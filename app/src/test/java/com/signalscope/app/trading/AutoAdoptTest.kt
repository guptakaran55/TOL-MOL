package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO HAND-OVER (3 Nov 2026): a stock you buy by hand, worth at least the
 * minimum, joins the watchlist after half an hour — never one you held when it
 * began, nor shares an algo already manages.
 */
class AutoAdoptTest {
    private val t0 = 1_793_700_000_000L
    private val wait = ManualEntryWatcher.AUTO_ADOPT_WAIT_MS

    @Test fun `a new buy of yours waits half an hour, then is due`() {
        val held = mapOf("ROP" to (0.5 to 470.0))
        val (due1, seen1) = ManualEntryWatcher.autoAdoptDue(held, emptySet(), emptyMap(), emptyMap(), 100.0, t0)
        assertTrue(due1.isEmpty())
        assertEquals(t0, seen1["ROP"])
        val (due2, _) = ManualEntryWatcher.autoAdoptDue(held, emptySet(), emptyMap(), seen1, 100.0, t0 + wait)
        assertEquals(listOf("ROP"), due2)
    }

    @Test fun `what you held when it began is never taken over by itself`() {
        val held = mapOf("AIR" to (2.0 to 193.66))
        val (due, seen) = ManualEntryWatcher.autoAdoptDue(held, setOf("AIR"), emptyMap(),
            mapOf("AIR" to t0 - wait * 4), 100.0, t0)
        assertTrue(due.isEmpty()); assertTrue(seen.isEmpty())
    }

    @Test fun `shares an algo manages are not yours`() {
        val held = mapOf("SDF" to (0.764 to 12.9))
        val (due, _) = ManualEntryWatcher.autoAdoptDue(held, emptySet(), mapOf("SDF" to 0.764),
            mapOf("SDF" to t0 - wait * 2), 1.0, t0)
        assertTrue(due.isEmpty())
    }

    @Test fun `under the minimum stays yours, and its wait starts again`() {
        val held = mapOf("MRVL" to (0.0045 to 169.0))
        val (due, seen) = ManualEntryWatcher.autoAdoptDue(held, emptySet(), emptyMap(),
            mapOf("MRVL" to t0 - wait * 2), 100.0, t0)
        assertTrue(due.isEmpty()); assertTrue(seen.isEmpty())
    }

    @Test fun `only your part counts toward the minimum`() {
        // 2 shares at 100, an algo holds 1.5: your 0.5 is worth 50, under 100.
        val held = mapOf("X" to (2.0 to 100.0))
        val (due, _) = ManualEntryWatcher.autoAdoptDue(held, emptySet(), mapOf("X" to 1.5),
            mapOf("X" to t0 - wait), 100.0, t0)
        assertTrue(due.isEmpty())
    }

    @Test fun `the widget line lists your unprotected holdings, largest first, dust left out`() {
        fun pos(sym: String, market: String, v: Double) = PositionRadar.Pos(sym, sym, market,
            paid = 1.0, price = 1.0, peak = 1.0, target = 0.0, stop = 0.0, unprotected = v)
        val line = PositionRadar.unprotectedLine(listOf(
            pos("HAG", "DE", 109.0), pos("AIR", "DE", 387.3), pos("MRVL", "US", 0.76),
            pos("PFOCUS", "IN", 3795.0), pos("CBK", "DE", 0.0)))
        assertEquals(listOf("PFOCUS ₹3795", "AIR €387", "HAG €109"), line)
    }
}
