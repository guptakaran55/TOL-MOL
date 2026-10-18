package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sin

class AreaScoreTest {

    private val day = 86_400L

    // Weekday-only daily bars starting 2 Jan 2023, close given per bar index.
    private fun bars(count: Int, close: (Int) -> Double): List<CandleData> {
        val out = ArrayList<CandleData>()
        var ts = 1_672_617_600L // 2023-01-02 00:00 UTC, a Monday
        var i = 0
        while (out.size < count) {
            val dow = ((ts / day) + 4) % 7 // 0 = Sunday
            if (dow != 0L && dow != 6L) { val c = close(i++); out.add(CandleData(ts, c, c, c, c, 1_000_000)) }
            ts += day
        }
        return out
    }

    // Slow reference: the formula written out directly.
    private fun reference(c: List<CandleData>, t: Int): Double {
        val p = c[t].close
        var sw = 0.0; var swg = 0.0
        for (k in 0 until t) {
            val m = (c[t].timestamp - c[k].timestamp) / day.toDouble() / (365.25 / 12)
            if (m <= 0 || m >= 12) continue
            val w = (m / 12).pow(1.8333) * (1 - m / 12).pow(3.6667)
            sw += w; swg += w * (c[k].close - p) / p * 100
        }
        return swg / sw
    }

    @Test fun scoreMatchesTheWrittenOutFormula() {
        val c = bars(520) { 100 + 30 * sin(it / 40.0) + it * 0.05 }
        val r = AreaScore.compute(c)!!
        assertEquals(reference(c, c.size - 1), r.score, 1e-9)
        assertEquals(r.score, r.green + r.red, 1e-9)
        assertTrue(r.green >= 0 && r.red <= 0)
    }

    @Test fun risingStockScoresNegativeFallingStockPositive() {
        val up = AreaScore.compute(bars(300) { 50.0 + it })!!
        val down = AreaScore.compute(bars(300) { 400.0 - it })!!
        assertTrue(up.score < 0 && up.green == 0.0)
        assertTrue(down.score > 0 && down.red == 0.0)
    }

    @Test fun needsAFullYearOfHistory() {
        assertNull(AreaScore.compute(bars(200) { 100.0 + it }))
    }

    @Test fun rankIsBetween0And100OnceThereIsEnoughHistory() {
        val r = AreaScore.compute(bars(520) { 100 + 30 * sin(it / 40.0) })!!
        assertNotNull(r.rank)
        assertTrue(r.rank!! in 0.0..100.0)
        // A short history gives a score but no rank.
        assertNull(AreaScore.compute(bars(300) { 100 + 30 * sin(it / 40.0) })!!.rank)
    }

    // Cross-check vectors from the independent Python reference
    // (backtest_results/area_score/area_score_reference.py, listed in AREA_SCORE_SPEC.md).
    @Test fun matchesTheReferenceVectors() {
        val a = AreaScore.compute(bars(520) { 100 + 30 * sin(it / 40.0) + 0.05 * it })!!
        assertEquals(-22.983728, a.score, 1e-6)
        assertEquals(0.200105, a.green, 1e-6)
        assertEquals(-23.183833, a.red, 1e-6)
        assertEquals(29.5720, a.rank!!, 1e-4)

        val b = AreaScore.compute(bars(300) { 50.0 + it })!!
        assertEquals(-28.404131, b.score, 1e-6); assertNull(b.rank)

        val c = AreaScore.compute(bars(300) { 400.0 - it })!!
        assertEquals(98.148926, c.score, 1e-6); assertNull(c.rank)

        val d = AreaScore.compute(bars(520) { if (it < 400) 100 + it * 0.4 else 260 - (it - 400) * 0.75 })!!
        assertEquals(36.861276, d.score, 1e-6)
        assertEquals(100.0, d.rank!!, 1e-9)

        assertEquals(0.0301730897, AreaScore.weight(4.0), 1e-10)
    }

    @Test fun millisecondTimestampsGiveTheSameScore() {
        val s = bars(400) { 100 + 20 * sin(it / 25.0) }
        val ms = s.map { it.copy(timestamp = it.timestamp * 1000) }
        assertEquals(AreaScore.compute(s)!!.score, AreaScore.compute(ms)!!.score, 1e-9)
    }
}
