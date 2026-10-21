package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MACD SLOPE IS THE LAST BAR'S MOVE, unless a strategy file asks for
 * smoothing — the averaged version lagged the line it described.
 */
class SlopeSmoothingTest {

    private fun ema(x: DoubleArray, span: Int): DoubleArray {
        val a = 2.0 / (span + 1.0)
        val o = DoubleArray(x.size); o[0] = x[0]
        for (i in 1 until x.size) o[i] = a * x[i] + (1 - a) * o[i - 1]
        return o
    }

    private fun macd(c: DoubleArray): DoubleArray {
        val f = ema(c, 12); val s = ema(c, 26)
        return DoubleArray(c.size) { f[it] - s[it] }
    }

    @Test fun `stocks are not smoothed by default, crypto lightly`() {
        assertEquals(1, MacdSlopes.defaultSmoothing("de"))
        assertEquals(1, MacdSlopes.defaultSmoothing("us"))
        assertEquals(1, MacdSlopes.defaultSmoothing("in"))
        assertEquals(2, MacdSlopes.defaultSmoothing("binance"))
    }

    @Test fun `no smoothing is exactly the last bar's change in the MACD line`() {
        val closes = DoubleArray(60) { 100.0 + 5.0 * Math.sin(it / 6.0) + it * 0.1 }
        val m = macd(closes)
        assertEquals(m[59] - m[58], MacdSlopes.latest(closes, 1)!!, 1e-12)
    }

    @Test fun `a line that rose a week ago and fell for two days reads falling`() {
        // Hensoldt's daily MACD, 29 Sep – 8 Oct: a strong run, then two down days.
        val hag = listOf(-2.202, -2.124, -1.982, -1.658, -1.127, -0.991, -1.174, -1.340)
        assertTrue("unsmoothed: falling", MacdSlopes.latestFromMacd(hag, 1)!! < 0.0)
        assertEquals(-0.166, MacdSlopes.latestFromMacd(hag, 1)!!, 1e-9)
        // The five-bar average still said rising — the green dot on 8 Oct.
        assertTrue("five-bar smoothing said rising", MacdSlopes.latestFromMacd(hag, 5)!! > 0.0)
    }
}
