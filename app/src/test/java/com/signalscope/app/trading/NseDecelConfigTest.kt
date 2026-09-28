package com.signalscope.app.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped NSE config must PARSE into the settings it claims to carry.
 *
 * A config is only as good as the parser reading it: a renamed key or a missing default
 * fails silently, leaving the engine on some other rule entirely while the file on disk
 * still looks correct. This reads the real artifact through the real parser.
 */
class NseDecelConfigTest {

    private fun cfg(): StrategyLabConfig {
        val f = File("src/test/resources/nse_decel_config.json")
        assertTrue("config artifact must be present at ${f.absolutePath}", f.exists())
        return when (val r = StrategyLabConfig.fromJson(f.readText(), "nse_decel_config.json")) {
            is StrategyLabConfig.Companion.ParseResult.Ok -> r.config
            is StrategyLabConfig.Companion.ParseResult.Rejected ->
                throw AssertionError("the shipped config was REJECTED by the app's own validator: ${r.reason}")
        }
    }

    @Test
    fun nseDecelConfigParsesIntoTheRulesItClaims() {
        val c = cfg()
        // the clock
        assertEquals("1D", c.resample)
        assertEquals("240min", c.exitBars)
        assertTrue("must be multi-timeframe", c.multiTimeframe)

        // the deceleration exit REPLACES the slope flip — both halves matter
        assertTrue("accelNegative must be enabled", c.exit.accelNegativeEnabled)
        assertEquals("sigma floor", 1.0, c.exit.accelNegativeMinSigma, 1e-12)
        assertFalse("slopeFlipDown must be OFF (this replaces it, not adds to it)",
            c.exit.slopeFlipDownEnabled)

        // the champion parts that must NOT have drifted
        assertEquals("stop loss", 4.0, c.exit.stopLossPct, 1e-12)
        assertTrue(c.exit.stopLossEnabled)
        assertEquals("macd distance", 30.0, c.exit.macdDistancePct, 1e-12)
        assertTrue(c.exit.macdDistanceEnabled)
        assertEquals("entry accel sigma", 0.7, c.entry.accelMinSigma, 1e-12)
        assertEquals("ema gate length", 50, c.gates.smaLength)
        assertFalse("fastMacdExit must stay off", c.exit.fastMacdExitEnabled)
        println("NSE CONFIG PARSED: ${c.summary()}")
    }

    @Test
    fun theFeedPlannerCanActuallyServeThisConfig() {
        val plan = FeedPlan.plan(cfg(), "in")
        println("FEED PLAN (in): interval=${plan.interval} warmupDays=${"%.0f".format(plan.warmupTradingDays)} " +
                "capTradingDays=${"%.0f".format(plan.capTradingDays)} ok=${plan.ok}")
        assertTrue("planner must be able to serve this config: ${plan.problem}", plan.ok)
    }
}
