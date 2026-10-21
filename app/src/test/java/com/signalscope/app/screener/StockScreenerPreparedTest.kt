package com.signalscope.app.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The fast path must be a pure speed-up, not a re-tune.
 *
 * StockScreener.Prepared exists only because screen() re-sorted every symbol's full history on
 * every call, which made the walk-forward backtest appear to hang. Swapping in a faster ranking
 * would silently change which companies every seat ever held — so this pins the two to the same
 * numbers, symbol for symbol and score for score, across a range of as-of dates.
 */
class StockScreenerPreparedTest {

    private fun universe(nSym: Int, nDays: Int): Map<String, List<DailyBar>> {
        var seed = 0x1234_5678L
        fun rnd(): Double { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7)
            seed = seed xor (seed shl 17); return ((seed ushr 11).toDouble() / (1L shl 53)) }
        val start = LocalDate.of(2022, 1, 3)
        return (0 until nSym).associate { k ->
            var px = 50.0 + k * 7.0
            val bars = ArrayList<DailyBar>(nDays)
            for (d in 0 until nDays) {
                px *= 1.0 + (rnd() - 0.48) * 0.04
                if (d % 97 == 0) px *= 1.0 + (rnd() - 0.3) * 0.15   // regime kicks
                bars.add(DailyBar(start.plusDays(d.toLong()), px))
            }
            "SYM$k" to bars.toList()
        }
    }

    @Test
    fun preparedMatchesScreenExactly() {
        val cfg = ScreenerConfig(topN = 5)
        val u = universe(nSym = 25, nDays = 400)
        val prep = StockScreener.Prepared(cfg, u)
        var compared = 0
        for (offset in listOf(120L, 200L, 275L, 333L, 399L)) {
            val asOf = LocalDate.of(2022, 1, 3).plusDays(offset)
            val slow = StockScreener.screen(cfg, u, asOf)
            val fast = prep.screen(cfg, asOf)
            assertEquals("ranked count at $asOf", slow.size, fast.size)
            for (i in slow.indices) {
                assertEquals("symbol at rank ${i + 1} on $asOf", slow[i].symbol, fast[i].symbol)
                assertEquals("rank on $asOf", slow[i].rank, fast[i].rank)
                assertEquals("score for ${slow[i].symbol} on $asOf", slow[i].score, fast[i].score, 1e-9)
                assertEquals("trailing return", slow[i].traits.trailingReturnPct, fast[i].traits.trailingReturnPct, 1e-9)
                assertEquals("volatility", slow[i].traits.annualizedVolPct, fast[i].traits.annualizedVolPct, 1e-9)
                assertEquals("pct above sma", slow[i].traits.pctDaysAboveSma, fast[i].traits.pctDaysAboveSma, 1e-9)
                assertEquals("bars in window", slow[i].traits.barsInWindow, fast[i].traits.barsInWindow)
                compared++
            }
        }
        assertTrue("expected rankings to compare, got $compared", compared >= 50)
        println("SCREENER PARITY: $compared ranked rows identical between screen() and Prepared")
    }

    @Test
    fun preparedIsSubstantiallyFaster() {
        val cfg = ScreenerConfig(topN = 5)
        val u = universe(nSym = 60, nDays = 500)
        val days = (200L..400L step 2).map { LocalDate.of(2022, 1, 3).plusDays(it) }

        var t0 = System.nanoTime()
        for (d in days) StockScreener.screen(cfg, u, d)
        val slowMs = (System.nanoTime() - t0) / 1_000_000

        t0 = System.nanoTime()
        val prep = StockScreener.Prepared(cfg, u)
        for (d in days) prep.screen(cfg, d)
        val fastMs = (System.nanoTime() - t0) / 1_000_000

        println("SCREENER SPEED over ${days.size} days x 60 symbols: screen()=${slowMs}ms  Prepared=${fastMs}ms " +
                "(${if (fastMs > 0) "%.1fx".format(slowMs.toDouble() / fastMs) else ">${slowMs}x"})")
        assertTrue("Prepared should not be slower (was ${fastMs}ms vs ${slowMs}ms)", fastMs <= slowMs)
    }
}
