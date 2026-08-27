package com.signalscope.app.screener

import java.time.LocalDate
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
// MOMENTUM SCREENER — Part A of SCREENER_REINVEST_KOTLIN_PORT.md
// ════════════════════════════════════════════════════════════════════════════
// Ranks a universe of symbols by a momentum-quality score built from three
// trailing-90d traits — return, annualized volatility, and %-days-above-SMA50 —
// each percentile-ranked across the surviving universe and blended by fixed,
// validated weights (0.72 / 0.57 / 0.45). Faithful port; see the spec's parity
// checklist (§8) and test vectors (§3.7). Pure + deterministic — no I/O here.
// ════════════════════════════════════════════════════════════════════════════

data class DailyBar(val date: LocalDate, val close: Double)

data class ScreenerConfig(
    val lookbackDays: Long = 90,
    val topN: Int = 20,
    val smaPeriod: Int = 50,
    val smaMinPeriods: Int = 10,
    val minBarsInWindow: Int = 30,
    val tradingDaysPerYear: Double = 252.0,
    val weightReturn: Double = 0.72,
    val weightVol: Double = 0.57,
    val weightAboveSma: Double = 0.45,
)

data class SymbolTraits(
    val symbol: String,
    val trailingReturnPct: Double,
    val annualizedVolPct: Double,
    val pctDaysAboveSma: Double,
    val barsInWindow: Int,
)

data class RankedSymbol(
    val symbol: String,
    val rank: Int,
    val score: Double,
    val traits: SymbolTraits,
    val pctReturn: Double,   // percentile of trailing return
    val pctVol: Double,      // percentile of volatility
    val pctAboveSma: Double, // percentile of %>SMA50
)

object StockScreener {

    /** Rolling mean, period with minPeriods, NaN before minPeriods reached.
     *  Matches pandas rolling(period, min_periods). */
    fun rollingMean(values: DoubleArray, period: Int, minPeriods: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        val window = ArrayDeque<Double>()
        for (i in values.indices) {
            window.addLast(values[i]); sum += values[i]
            if (window.size > period) sum -= window.removeFirst()
            if (window.size >= minPeriods) out[i] = sum / window.size
        }
        return out
    }

    /** Population standard deviation (ddof = 0), matching NumPy np.std. */
    fun populationStd(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        return sqrt(variance)
    }

    fun computeTraits(cfg: ScreenerConfig, symbol: String, bars: List<DailyBar>, asOf: LocalDate): SymbolTraits? {
        // one bar per date, ascending, only dates <= asOf (NO LOOKAHEAD)
        val sorted = bars.asSequence()
            .filter { !it.date.isAfter(asOf) }
            .groupBy { it.date }.values.map { it.last() }   // dedup by date, keep last
            .sortedBy { it.date }
        if (sorted.size < cfg.smaMinPeriods) return null

        val closesFull = DoubleArray(sorted.size) { sorted[it].close }
        val smaFull = rollingMean(closesFull, cfg.smaPeriod, cfg.smaMinPeriods)

        val cutoff = asOf.minusDays(cfg.lookbackDays)               // window = date STRICTLY AFTER cutoff
        val windowIdx = sorted.indices.filter { sorted[it].date.isAfter(cutoff) }
        if (windowIdx.size < cfg.minBarsInWindow) return null

        val c = windowIdx.map { closesFull[it] }
        val s = windowIdx.map { smaFull[it] }

        val trailingReturn = (c.last() / c.first() - 1.0) * 100.0

        val dailyReturns = (1 until c.size).map { (c[it] - c[it - 1]) / c[it - 1] }
        val vol = if (dailyReturns.isNotEmpty())
            populationStd(dailyReturns) * sqrt(cfg.tradingDaysPerYear) * 100.0 else 0.0

        val validIdx = c.indices.filter { !s[it].isNaN() }
        val pctAbove = if (validIdx.isNotEmpty())
            validIdx.count { c[it] > s[it] }.toDouble() / validIdx.size * 100.0 else 0.0

        return SymbolTraits(symbol, trailingReturn, vol, pctAbove, c.size)
    }

    /** Percentile ranks 0..100, stable, ties get consecutive ranks. n==1 → 50. */
    fun percentileRanks(values: DoubleArray): DoubleArray {
        val n = values.size
        if (n == 0) return DoubleArray(0)
        if (n == 1) return doubleArrayOf(50.0)
        val order = (0 until n).sortedBy { values[it] }            // stable ascending
        val out = DoubleArray(n)
        for ((rankPos, idx) in order.withIndex()) out[idx] = rankPos.toDouble() / (n - 1) * 100.0
        return out
    }

    fun screen(cfg: ScreenerConfig, universe: Map<String, List<DailyBar>>, asOf: LocalDate): List<RankedSymbol> {
        val traits = universe.mapNotNull { (sym, bars) -> computeTraits(cfg, sym, bars, asOf) }
        if (traits.isEmpty()) return emptyList()
        val pm = percentileRanks(DoubleArray(traits.size) { traits[it].trailingReturnPct })
        val pv = percentileRanks(DoubleArray(traits.size) { traits[it].annualizedVolPct })
        val pa = percentileRanks(DoubleArray(traits.size) { traits[it].pctDaysAboveSma })
        val wsum = cfg.weightReturn + cfg.weightVol + cfg.weightAboveSma
        return traits.indices.map { i ->
            val score = (cfg.weightReturn * pm[i] + cfg.weightVol * pv[i] + cfg.weightAboveSma * pa[i]) / wsum
            RankedSymbol(traits[i].symbol, 0, score, traits[i], pm[i], pv[i], pa[i])
        }.sortedByDescending { it.score }
            .mapIndexed { i, r -> r.copy(rank = i + 1) }
    }

    fun topN(cfg: ScreenerConfig, universe: Map<String, List<DailyBar>>, asOf: LocalDate): List<String> =
        screen(cfg, universe, asOf).take(cfg.topN).map { it.symbol }
}
