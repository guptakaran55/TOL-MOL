package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUB-€1 LEFTOVERS (4 Nov 2026). Trading 212 refuses a sell that would leave under €1
 * of a stock behind. WAF: 2.795 held, the watchlist's target for 2.794 refused every
 * hour over a 0.001 leftover; AIXA: an algo's 0.001 remainder retried for ever.
 */
class DustTest {
    @Test fun `an unclaimed sliver worth under 1 euro goes with the target`() {
        assertEquals(0.001, T212WeekTargets.dustLeft(have = 2.795, own = 2.794, others = 0.0, price = 63.97), 1e-9)
    }

    @Test fun `a leftover worth 1 euro or more is somebody's holding and stays`() {
        assertEquals(0.0, T212WeekTargets.dustLeft(have = 3.0, own = 2.794, others = 0.0, price = 63.97), 1e-9)
    }

    @Test fun `another row's claim is never swept`() {
        assertEquals(0.0, T212WeekTargets.dustLeft(have = 4.492, own = 4.491, others = 0.001, price = 34.62), 1e-9)
    }

    @Test fun `nothing left, or an unreadable price, sweeps nothing`() {
        assertEquals(0.0, T212WeekTargets.dustLeft(have = 2.794, own = 2.794, others = 0.0, price = 63.97), 1e-9)
        assertEquals(0.0, T212WeekTargets.dustLeft(have = 2.795, own = 2.794, others = 0.0, price = 0.0), 1e-9)
    }

    @Test fun `an algo position down to under 1 euro is dust, a real one is not`() {
        assertTrue(FloorLive.isDust(0.001, 34.01))
        assertFalse(FloorLive.isDust(0.295, 34.01))
        assertFalse(FloorLive.isDust(0.0, 34.01))
    }
}
