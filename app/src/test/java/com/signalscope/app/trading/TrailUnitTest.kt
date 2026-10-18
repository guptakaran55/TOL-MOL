package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore.TrailUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE WATCHLIST TRAIL IN THE STOCK'S OWN DAILY RANGE — a percentage of the high,
 * multiples of the 14-day ATR, or multiples of half the Bollinger width — and a
 * floor that still only rises when that range breathes.
 */
class TrailUnitTest {

    @Test fun `a percentage trail is unchanged`() {
        val w = ManualTrailingStop.widthFor(TrailUnit.PCT, 1.5, 100.0, 4.0, 3.0)
        assertEquals(1.5, w, 1e-9)
        assertEquals(98.5, ManualTrailingStop.levelForWidth(100.0, w), 1e-9)
        // Same answer as the old percentage arithmetic.
        assertEquals(ManualTrailingStop.levelFor(100.0, 1.5), ManualTrailingStop.levelForWidth(100.0, w), 1e-9)
    }

    @Test fun `an ATR trail sits that many average days below the high`() {
        // HAG: high 84, daily ATR 2.10 — a 1.5 ATR trail is 3.15 under the high.
        val w = ManualTrailingStop.widthFor(TrailUnit.ATR, 1.5, 84.0, 2.10, 0.0)
        assertEquals(80.85, ManualTrailingStop.levelForWidth(84.0, w), 1e-9)
    }

    @Test fun `a band trail uses half the band width`() {
        val w = ManualTrailingStop.widthFor(TrailUnit.BB, 1.0, 50.0, 0.0, 2.5)
        assertEquals(47.5, ManualTrailingStop.levelForWidth(50.0, w), 1e-9)
    }

    @Test fun `an unknown range means no floor rather than a guess`() {
        assertEquals(0.0, ManualTrailingStop.widthFor(TrailUnit.ATR, 1.5, 84.0, 0.0, 2.0), 1e-9)
        assertEquals(0.0, ManualTrailingStop.levelForWidth(84.0, 0.0), 1e-9)
    }

    @Test fun `a width as big as the price gives no floor`() {
        assertEquals(0.0, ManualTrailingStop.levelForWidth(10.0, 12.0), 1e-9)
    }

    @Test fun `a wider day cannot pull the floor down`() {
        val b = ManualTrailingStop.basis(TrailUnit.ATR, 1.5)
        assertEquals(80.85, ManualTrailingStop.ratchet(80.85, b, b, 80.10), 1e-9)
        assertEquals(81.20, ManualTrailingStop.ratchet(80.85, b, b, 81.20), 1e-9)
        // No range this pass: keep the last floor.
        assertEquals(80.85, ManualTrailingStop.ratchet(80.85, b, b, 0.0), 1e-9)
    }

    @Test fun `changing the setting starts the floor afresh`() {
        val old = ManualTrailingStop.basis(TrailUnit.ATR, 1.0)
        val now = ManualTrailingStop.basis(TrailUnit.ATR, 2.0)
        assertEquals(79.80, ManualTrailingStop.ratchet(81.90, old, now, 79.80), 1e-9)
    }

    @Test fun `half the Bollinger width is two standard deviations`() {
        // Twenty closes alternating 98 and 102: mean 100, standard deviation 2.
        val closes = List(20) { if (it % 2 == 0) 98.0 else 102.0 }
        assertEquals(4.0, ManualTrailingStop.bbHalfWidth(closes), 1e-9)
        // Fewer than twenty closes: unknown.
        assertEquals(0.0, ManualTrailingStop.bbHalfWidth(closes.take(19)), 1e-9)
    }

    @Test fun `daily range is worked out from bars`() {
        val n = 30
        val closes = List(n) { 100.0 + if (it % 2 == 0) -1.0 else 1.0 }
        val highs = closes.map { it + 1.0 }
        val lows = closes.map { it - 1.0 }
        val v = DailyVolatility.compute(highs, lows, closes)!!
        assertEquals(2.0, v.bbHalf, 1e-9)
        // Each day's true range reaches from the previous close to the far end
        // of today's bar: 3.
        assertEquals(3.0, v.atr, 0.1)
    }
}
