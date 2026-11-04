package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Positions widget leaves out what the autopilot algos own — but never your
 * own shares in the same stock. Of a holding, what is yours is what the
 * autopilot does not own, unless that is only a sliver.
 */
class AutopilotHoldingsTest {
    private val eps = 1e-9

    @Test fun `nothing on autopilot — the whole holding is yours`() {
        assertEquals(10.0, PositionRadar.yoursBeside(10.0, 0.0, 50.0), eps)
    }

    @Test fun `all of it on autopilot — nothing to list`() {
        assertEquals(0.0, PositionRadar.yoursBeside(0.19, 0.19, 51.31), eps)
    }

    @Test fun `your own shares beside the autopilot's still show, and only those`() {
        assertEquals(3.0, PositionRadar.yoursBeside(3.19, 0.19, 51.31), eps)
    }

    @Test fun `a sliver left over by rounding is not listed as yours`() {
        assertEquals(0.0, PositionRadar.yoursBeside(1.0, 0.995, 40.0), eps)     // 0.5% of the holding
        assertEquals(0.0, PositionRadar.yoursBeside(2.0, 1.9, 30.0), eps)       // worth 3 — under 5
    }

    @Test fun `the broker reading less than the autopilot owns lists nothing`() {
        assertEquals(0.0, PositionRadar.yoursBeside(0.18, 0.19, 51.31), eps)
    }
}
