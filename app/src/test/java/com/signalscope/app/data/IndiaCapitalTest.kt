package com.signalscope.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** INDIA'S ₹1,000 ALGOS: the ones the ₹16 fee swamps (26 Oct 2026). */
class IndiaCapitalTest {
    private fun algo(space: String, kind: String, capital: Double) =
        FloorAlgoStore.Algo(id = "f1", name = "x", kind = kind, space = space,
            settings = FloorAlgoStore.Settings(capital = capital))

    @Test fun `a thousand-rupee floor or MACD algo is raised`() {
        assertTrue(FloorAlgoStore.indiaTooSmall(algo("in", FloorAlgoStore.KIND_FLOOR, 1000.0)))
        assertTrue(FloorAlgoStore.indiaTooSmall(algo("in", FloorAlgoStore.KIND_MACD, 1000.0)))
    }

    @Test fun `already large, another market or another kind is left alone`() {
        assertFalse(FloorAlgoStore.indiaTooSmall(algo("in", FloorAlgoStore.KIND_FLOOR, 100_000.0)))
        assertFalse(FloorAlgoStore.indiaTooSmall(algo("de", FloorAlgoStore.KIND_FLOOR, 1000.0)))
        assertFalse(FloorAlgoStore.indiaTooSmall(algo("in", FloorAlgoStore.KIND_CAMPAIGN, 1000.0)))
    }
}
