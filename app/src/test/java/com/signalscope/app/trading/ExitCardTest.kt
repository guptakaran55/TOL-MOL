package com.signalscope.app.trading

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE REVIEW CARD'S EXIT BOX EXPLAINS THE MOMENTUM EXIT (3 Nov 2026) — display only.
 * The slope row carries its smoothing and values, the MACD-distance qualifier shows
 * while a trade is open, the stop names its trigger price, the card says when each
 * check runs, and a blocked one-bar down-turn is flagged until the slope turns up.
 * The trades drawn are still the engine's own.
 */
class ExitCardTest {
    private fun candles(seed: Long, n: Int, startTs: Long, stepSec: Long): List<CandleData> {
        var s = seed
        fun rnd(): Double {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) - 0.5
        }
        val out = ArrayList<CandleData>(n)
        var px = 100.0
        for (i in 0 until n) {
            val drift = when ((i / 220) % 5) { 0 -> 0.0011; 1 -> -0.0014; 2 -> 0.0; 3 -> 0.0035; else -> -0.0040 }
            px *= (1.0 + drift + rnd() * 0.010)
            if (px < 1.0) px = 1.0
            val o = px * (1 + rnd() * 0.002)
            val hi = maxOf(o, px) * (1 + kotlin.math.abs(rnd()) * 0.004)
            val lo = minOf(o, px) * (1 - kotlin.math.abs(rnd()) * 0.004)
            out.add(CandleData(startTs + i * stepSec, o, hi, lo, px, 10_000L + i))
        }
        return out
    }

    private val bars = candles(seed = 0x5EED_1234L, n = 1200, startTs = 1_700_000_000L, stepSec = 900L)
    private val base = StrategyLabConfig().copy(resample = "60min", exitBars = "30min", warmupBars = 30)
    private val slow = base.copy(exit = base.exit.copy(slopeFlipDownSmoothing = 10))

    @Test fun `slope row shows its smoothing and values, and the card says when it is checked`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        val open = p.bars.filter { it.exitHasPos }
        assertTrue("some bars hold a position", open.isNotEmpty())
        val slopeRow = open.first().exitInd.first { it.label.contains("MACD slope flips") }
        assertTrue(slopeRow.label, slopeRow.label.startsWith("60min MACD slope flips + → − (smoothed 10: "))
        assertTrue(slopeRow.label, slopeRow.label.contains("→"))
        assertEquals("checked: slope each 60min close · stop each 30min close", open.first().exitCheckNote)
    }

    @Test fun `the qualifier shows while a trade is open, and the stop names its trigger`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        val holding = p.bars.filter { it.exitHasPos && !it.exitFires }
        assertTrue(holding.isNotEmpty())
        assertTrue("qualifier present while holding", holding.all { it.exitDist != null })
        assertTrue(holding.any { it.exitDist!!.label.contains("· next down-turn: ") })
        val stop = holding.first().exitHard.first { it.label.startsWith("stop −") }
        assertTrue(stop.label, stop.label.contains("fires ≤ ") && stop.label.contains("% away"))
    }

    @Test fun `a warning only ever shows with an open trade, and says so in the verdict`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        val warned = p.bars.filter { it.exitWarn != null }
        println("EXIT CARD: ${warned.size} bars carry the momentum-off warning")
        warned.forEach {
            assertTrue(it.exitHasPos)
            assertTrue(it.exitVerdict, it.exitFires || it.exitVerdict.contains("momentum exit off"))
        }
    }

    @Test fun `armed exits never carry the one-bar warning`() {
        val armed = base.copy(exit = base.exit.copy(slopeFlipDownPersistent = true))
        val p = StrategyLabEngine.reviewPayload("AAA", bars, armed, maxBars = 1200)
        assertTrue(p.bars.none { it.exitWarn != null })
    }

    @Test fun `the graph gets the raw slope, the averaging, and when the lines can next move`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        assertEquals(2, p.entrySmoothing)
        assertEquals(10, p.exitSmoothing)
        assertTrue(!p.fastExit)
        val withRaw = p.bars.filter { it.rawSlope != null && it.exitSlope != null && it.exitSlopePrev != null }
        assertTrue(withRaw.isNotEmpty())
        // 60min entry candles over 30min fine bars: every other fine bar closes no entry candle.
        val still = p.bars.filter { it.nextEntryCloseSec != null }
        assertTrue("some bars wait for the next entry close", still.isNotEmpty())
        still.forEach { assertTrue(it.nextEntryCloseSec!! > it.t) }
    }

    @Test fun `the candles-to-cross estimate lands at or below zero, and not a candle sooner`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        val a = 2.0 / (10 + 1)
        val cases = p.bars.filter { (it.exitSlope ?: 0.0) > 0.0 && (it.rawSlope ?: 0.0) < 0.0 }
        assertTrue(cases.isNotEmpty())
        for (b in cases) {
            val now = b.exitSlope!!; val r = b.rawSlope!!
            val k = kotlin.math.ceil(kotlin.math.ln(-r / (now - r)) / kotlin.math.ln(1 - a)).toInt()
            var s = now
            repeat(k - 1) { s += a * (r - s) }
            assertTrue("one candle short is still above 0", s > -1e-12)
            s += a * (r - s)
            assertTrue("k candles reach 0", s <= 1e-12)
        }
    }

    @Test fun `the trades drawn are the engine's own`() {
        val p = StrategyLabEngine.reviewPayload("AAA", bars, slow, maxBars = 1200)
        val run = StrategyLabEngine.run("AAA", bars, slow)
        assertNotNull(p.trades)
        assertEquals(run.trades.map { it.entryTs to it.exitTs }, p.trades.map { it.entryTs to it.exitTs })
    }
}
