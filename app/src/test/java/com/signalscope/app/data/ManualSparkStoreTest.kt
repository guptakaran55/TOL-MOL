package com.signalscope.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sparkline downsampler.
 *
 * Pure index arithmetic, which is exactly the kind of code that looks obviously
 * correct and is off by one at the end. The failure would be silent and ugly: a
 * chart whose right-hand edge is not today, so every candidate appears to have
 * stopped moving a few days ago.
 */
class ManualSparkStoreTest {

    @Test
    fun keepsTheNewestBarAndTheRequestedLength() {
        val xs = DoubleArray(252) { it.toDouble() }
        val out = ManualSparkStore.downsample(xs, points = 48, decimals = 2)
        assertEquals("must return exactly the requested number of points", 48, out.size)
        assertEquals("the last sample must be the newest bar", 251.0, out.last(), 1e-9)
        assertEquals("the first sample must be the oldest bar", 0.0, out.first(), 1e-9)
        // Order must be preserved — a sparkline drawn from a shuffled series is
        // not a wrong chart, it is a convincing one that means nothing.
        for (i in 1 until out.size)
            assertTrue("samples must stay in order at $i", out[i] >= out[i - 1])
    }

    @Test
    fun shortSeriesArePassedThroughWhole() {
        val xs = doubleArrayOf(1.0, 2.0, 3.0)
        val out = ManualSparkStore.downsample(xs, points = 48)
        assertEquals(3, out.size)
        assertEquals(3.0, out.last(), 1e-9)
    }

    @Test
    fun emptyAndDegenerateInputsDoNotThrow() {
        assertTrue(ManualSparkStore.downsample(DoubleArray(0)).isEmpty())
        assertEquals(1, ManualSparkStore.downsample(doubleArrayOf(5.0)).size)
        // NaN and infinity reach this from a thin listing with gaps in its history.
        // They must become a number the chart can plot rather than propagating into
        // the SVG, where they would silently blank the whole line.
        val messy = ManualSparkStore.downsample(
            doubleArrayOf(1.0, Double.NaN, Double.POSITIVE_INFINITY, 4.0), points = 4)
        assertTrue("no NaN may survive", messy.none { it.isNaN() })
        assertTrue("no infinity may survive", messy.none { it.isInfinite() })
    }

    @Test
    fun roundingIsAppliedAtTheRequestedPrecision() {
        val out = ManualSparkStore.downsample(doubleArrayOf(1.23456, 2.34567), decimals = 2)
        assertEquals(1.23, out[0], 1e-9)
        assertEquals(2.35, out[1], 1e-9)
    }
}
