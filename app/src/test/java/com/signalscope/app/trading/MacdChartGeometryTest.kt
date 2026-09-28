package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NOTIFICATION CHART MUST BE DISCOVERY'S CHART.
 *
 * Same vertical scale, same spacing, same colours — otherwise a stock looks one
 * way in the shade and another in the app, and one of them is lying.
 */
class MacdChartGeometryTest {

    private val w = 300f; private val h = 80f; private val pad = 4f

    @Test fun `zero sits exactly in the middle, whatever the curve does`() {
        // Discovery makes the range symmetric about zero; a curve that is almost
        // all positive must still have its zero line centred, not at the bottom.
        val g = MacdChartGeometry.layout(listOf(0.1, 0.5, 2.0, 1.2), w, h, pad)!!
        assertEquals(h / 2, g.zeroY, 1e-4f)
    }

    @Test fun `the largest swing touches the edge of the plot`() {
        val g = MacdChartGeometry.layout(listOf(-1.0, 0.0, 3.0), w, h, pad)!!
        assertEquals("the peak is at the top", pad, g.ys[2], 1e-4f)
        assertTrue("the dip is above the bottom, scaled against the peak", g.ys[0] < h - pad)
        assertTrue(g.ys[0] > g.zeroY)
    }

    @Test fun `oldest on the left, today on the right, evenly spaced`() {
        val g = MacdChartGeometry.layout(List(30) { it.toDouble() - 15 }, w, h, pad)!!
        assertEquals(pad, g.xs.first(), 1e-4f)
        assertEquals(w - pad, g.xs.last(), 1e-4f)
        val step = g.xs[1] - g.xs[0]
        for (i in 1 until g.xs.size) assertEquals(step, g.xs[i] - g.xs[i - 1], 1e-3f)
    }

    @Test fun `a flat curve draws on the zero line instead of failing`() {
        val g = MacdChartGeometry.layout(listOf(0.0, 0.0, 0.0), w, h, pad)!!
        g.ys.forEach { assertEquals(g.zeroY, it, 1e-4f) }
    }

    @Test fun `nothing to draw gives nothing, not a broken picture`() {
        assertNull(MacdChartGeometry.layout(emptyList(), w, h, pad))
        assertNull(MacdChartGeometry.layout(listOf(1.0), w, h, pad))
        assertNull("bad values are dropped before counting",
            MacdChartGeometry.layout(listOf(Double.NaN, 1.0), w, h, pad))
    }

    // ── the price dot ────────────────────────────────────────────────────────

    @Test fun `velocity is Discovery's close-on-close change, taken to the price now`() {
        assertEquals(1.0, MacdChartGeometry.velocityPct(100.0, 101.0)!!, 1e-9)
        assertEquals(-2.5, MacdChartGeometry.velocityPct(40.0, 39.0)!!, 1e-9)
    }

    @Test fun `no price means no dot, never a coloured guess`() {
        assertNull(MacdChartGeometry.velocityPct(0.0, 101.0))
        assertNull(MacdChartGeometry.velocityPct(100.0, 0.0))
        assertNull(MacdChartGeometry.velocityPct(Double.NaN, 101.0))
    }

    @Test fun `green up, red down, grey when it has no direction`() {
        assertEquals(0xFF059669.toInt(), MacdChartGeometry.velocityColor(0.4))
        assertEquals(0xFFDC2626.toInt(), MacdChartGeometry.velocityColor(-0.4))
        // RHM at €973.15 against a €973.10 close is a rounding wobble, not a trend.
        assertEquals(0xFF94A3B8.toInt(),
            MacdChartGeometry.velocityColor(MacdChartGeometry.velocityPct(973.10, 973.15)))
        assertEquals(0xFF94A3B8.toInt(), MacdChartGeometry.velocityColor(null))
    }

    @Test fun `phase colours are Discovery's`() {
        assertEquals(0xFF059669.toInt(), MacdChartGeometry.phaseColor("BUY FLIP"))
        assertEquals(0xFF34D399.toInt(), MacdChartGeometry.phaseColor("early buy"))
        assertEquals(0xFFDC2626.toInt(), MacdChartGeometry.phaseColor("SELL FLIP"))
        assertEquals(0xFF64748B.toInt(), MacdChartGeometry.phaseColor("NEUTRAL"))
        assertEquals("unknown is slate", 0xFF94A3B8.toInt(), MacdChartGeometry.phaseColor(null))
    }
}
