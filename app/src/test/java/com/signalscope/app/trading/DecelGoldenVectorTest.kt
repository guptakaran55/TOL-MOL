package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * The deceleration exit's indicator chain, asserted against the desktop's own numbers
 * (ANDROID decel-exit brief §4, SYRMA 1D frame). Tolerance 1e-6 as the brief specifies.
 */
class DecelGoldenVectorTest {

    private fun cfg(): StrategyLabConfig {
        val f = File("src/test/resources/nse_decel_config.json")
        return (StrategyLabConfig.fromJson(f.readText(), "nse_decel_config.json")
            as StrategyLabConfig.Companion.ParseResult.Ok).config
    }

    private fun daily(closes: DoubleArray) = closes.mapIndexed { i, c ->
        CandleData(1_661_472_000L + i * 86_400L, c, c, c, c, 0L)
    }

    /** §4a — rows 0..7 depend only on the first 8 closes, so they are exact here. */
    @Test
    fun seedingMatchesDesktop() {
        val closes = doubleArrayOf(312.0, 326.0, 309.8, 311.0, 307.8, 297.2, 294.15, 298.5)
        val macd = doubleArrayOf(0.0, 1.11680912, 0.68676715, 0.43773978, -0.01762628, -1.21977774, -2.39103854, -2.93443616)
        val slope = doubleArrayOf(0.0, 0.74453941, -0.03851484, -0.17885653, -0.36319622, -0.92249971, -1.08834044, -0.72504523)
        val accel = doubleArrayOf(0.0, 0.74453941, -0.78305425, -0.14034169, -0.18433969, -0.55930349, -0.16584072, 0.36329521)
        val s = StrategyLabEngine.exitSeries(daily(closes), cfg())
        for (i in closes.indices) {
            assertEquals("macd[$i]", macd[i], s.macd[i], 1e-6)
            assertEquals("exit_slope[$i]", slope[i], s.exitSlope[i], 1e-6)
            assertEquals("exit_accel[$i]", accel[i], s.exitAccel[i], 1e-6)
            assertTrue("sigma[$i] must be NaN", s.exitAccelSigma[i].isNaN())
        }
    }

    /** §4b — sigma is NaN through i=48, first value at i=49 over 50 values with ddof=1. */
    @Test
    fun sigmaOnsetIsMinPeriods50SampleStd() {
        val closes = DoubleArray(260) { 100.0 + 10.0 * kotlin.math.sin(it / 7.0) + it * 0.05 }
        val s = StrategyLabEngine.exitSeries(daily(closes), cfg())
        for (i in 0..48) assertTrue("sigma[$i] must be NaN", s.exitAccelSigma[i].isNaN())
        fun sampleStd(from: Int, to: Int): Double {
            val xs = s.exitAccel.sliceArray(from..to); val m = xs.average()
            return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        }
        assertEquals("sigma[49] over 0..49, ddof=1", sampleStd(0, 49), s.exitAccelSigma[49], 1e-12)
        assertEquals("sigma[250] over a full 200 window", sampleStd(51, 250), s.exitAccelSigma[250], 1e-12)
    }

    @Test
    fun germanFeedCanServeThisConfig() {
        val plan = FeedPlan.plan(cfg(), "de")
        println("FEED PLAN (de): interval=${plan.interval} warmupDays=${"%.0f".format(plan.warmupTradingDays)} " +
                "capTradingDays=${"%.0f".format(plan.capTradingDays)} ok=${plan.ok} problem=${plan.problem}")
    }
}
