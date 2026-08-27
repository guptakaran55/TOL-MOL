package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-demand phase-2 style analysis.
 *
 * This intentionally does heavier statistical work than StockAnalyzer's scan-time
 * style score, so it should be called from a detail-view button, not for every
 * stock in the discovery universe.
 */
object DeepStyleAnalyzer {

    data class Result(
        val deepSwingScore: Int,
        val deepHoldScore: Int,
        val label: String,
        val confidence: Int,
        val regime: String,
        val verdict: String,
        val hurst: Double,
        val hurstR2: Double,
        val varianceRatio5: Double,
        val varianceRatio20: Double,
        val acf1: Double,
        val acf5: Double,
        val dominantCycleDays: Double,
        val cycleStrength: Int,
        val cycleStability: Int,
        val swingTrades: Int,
        val swingWinRate: Double,
        val swingExpectancyPct: Double,
        val avgSwingHoldDays: Double,
        val buyHoldReturnPct: Double,
        val maxDrawdownPct: Double,
        val timeAboveMaPct: Double,
        val trendConsistencyPct: Double,
        val report: String
    )

    private data class Regression(val slope: Double, val r2: Double)
    private data class Cycle(val period: Double, val strength: Int, val stability: Int)
    private data class Trade(val returnPct: Double, val holdDays: Int)

    fun analyze(candles: List<CandleData>): Result? {
        if (candles.size < 90) return null

        val closes = candles.map { it.close }.filter { it > 0.0 }
        if (closes.size < 90) return null
        val highs = candles.takeLast(closes.size).map { it.high }
        val lows = candles.takeLast(closes.size).map { it.low }
        val logPrices = closes.map { ln(it) }
        val returns = logPrices.zipWithNext { a, b -> b - a }
        val windowReturns = returns.takeLast(min(252, returns.size))

        val hurstPair = estimateHurst(logPrices.takeLast(min(504, logPrices.size)))
        val hurst = hurstPair.slope.coerceIn(0.0, 1.0)
        val vr5 = varianceRatio(windowReturns, 5)
        val vr20 = varianceRatio(windowReturns, 20)
        val acf1 = autocorr(windowReturns, 1)
        val acf5 = autocorr(windowReturns, 5)
        val cycle = dominantCycle(logPrices.takeLast(min(252, logPrices.size)))
        val trades = swingBacktest(highs, lows, closes)
        val trend = trendStats(closes)

        val meanReversionScore = weightedScore(
            scoreHurstMeanReversion(hurst) to 35.0,
            scoreVarianceMeanReversion((vr5 + vr20) / 2.0) to 35.0,
            scoreNegativeAutocorr(acf1, acf5) to 30.0
        )
        val cycleScore = weightedScore(
            cycle.strength.toDouble() to 65.0,
            cycle.stability.toDouble() to 35.0
        )
        val backtestScore = scoreSwingBacktest(trades)
        val deepSwingScore = weightedScore(
            meanReversionScore.toDouble() to 35.0,
            cycleScore.toDouble() to 25.0,
            backtestScore.toDouble() to 40.0
        )

        val persistenceScore = weightedScore(
            scoreHurstPersistence(hurst) to 35.0,
            scoreVariancePersistence((vr5 + vr20) / 2.0) to 35.0,
            scorePositiveAutocorr(acf1, acf5) to 30.0
        )
        val trendScore = weightedScore(
            trend.timeAboveMaPct to 30.0,
            trend.trendConsistencyPct to 30.0,
            scoreBuyHoldReturn(trend.buyHoldReturnPct) to 25.0,
            scoreDrawdown(trend.maxDrawdownPct) to 15.0
        )
        val deepHoldScore = weightedScore(
            persistenceScore.toDouble() to 40.0,
            trendScore.toDouble() to 60.0
        )

        val label = when {
            deepSwingScore >= 68 && deepHoldScore >= 68 -> "DUAL"
            deepSwingScore >= 65 && deepSwingScore >= deepHoldScore + 10 -> "RIDE"
            deepHoldScore >= 65 && deepHoldScore >= deepSwingScore + 10 -> "HOLD"
            deepSwingScore < 40 && deepHoldScore < 40 -> "WEAK"
            else -> "MIXED"
        }
        val regime = when {
            meanReversionScore >= 65 && cycleScore >= 55 -> "Ranging / mean-reverting"
            persistenceScore >= 65 && trendScore >= 60 -> "Trending / persistent"
            cycle.strength >= 65 && trendScore >= 60 -> "Trending with tradable pullbacks"
            else -> "Mixed or noisy"
        }
        val confidence = weightedScore(
            hurstPair.r2 * 100.0 to 25.0,
            cycle.stability.toDouble() to 25.0,
            min(100.0, trades.size * 18.0) to 25.0,
            (if (closes.size >= 240) 100.0 else closes.size / 240.0 * 100.0) to 25.0
        )

        val verdict = when (label) {
            "DUAL" -> "Can be held, but also gives measurable swing opportunities."
            "RIDE" -> "Better suited to repeated support/resistance wave riding than passive holding."
            "HOLD" -> "Better suited to trend holding than repeated support/resistance cycling."
            "WEAK" -> "Weak evidence for both swing riding and long-term holding."
            else -> "Mixed evidence; use the current setup and fundamentals as tie-breakers."
        }

        val winRate = if (trades.isEmpty()) 0.0 else trades.count { it.returnPct > 0.0 } * 100.0 / trades.size
        val expectancy = if (trades.isEmpty()) 0.0 else trades.map { it.returnPct }.average()
        val avgHold = if (trades.isEmpty()) 0.0 else trades.map { it.holdDays.toDouble() }.average()

        val report = listOf(
            "Mean reversion|$meanReversionScore|H=${hurst.round(2)}, VR20=${vr20.round(2)}, acf1=${acf1.round(2)}",
            "Cycle quality|$cycleScore|dominant ${cycle.period.round(1)}d, strength ${cycle.strength}, stability ${cycle.stability}",
            "Swing backtest|$backtestScore|${trades.size} trades, win ${winRate.round(0)}%, expectancy ${expectancy.round(2)}%",
            "Trend persistence|$persistenceScore|H=${hurst.round(2)}, VR avg=${((vr5 + vr20) / 2.0).round(2)}, acf5=${acf5.round(2)}",
            "Hold trend|$trendScore|return ${trend.buyHoldReturnPct.round(1)}%, max DD ${trend.maxDrawdownPct.round(1)}%, above MA ${trend.timeAboveMaPct.round(0)}%"
        ).joinToString("\n")

        return Result(
            deepSwingScore = deepSwingScore,
            deepHoldScore = deepHoldScore,
            label = label,
            confidence = confidence,
            regime = regime,
            verdict = verdict,
            hurst = hurst.round(3),
            hurstR2 = hurstPair.r2.round(3),
            varianceRatio5 = vr5.round(3),
            varianceRatio20 = vr20.round(3),
            acf1 = acf1.round(3),
            acf5 = acf5.round(3),
            dominantCycleDays = cycle.period.round(1),
            cycleStrength = cycle.strength,
            cycleStability = cycle.stability,
            swingTrades = trades.size,
            swingWinRate = winRate.round(1),
            swingExpectancyPct = expectancy.round(2),
            avgSwingHoldDays = avgHold.round(1),
            buyHoldReturnPct = trend.buyHoldReturnPct.round(1),
            maxDrawdownPct = trend.maxDrawdownPct.round(1),
            timeAboveMaPct = trend.timeAboveMaPct.round(1),
            trendConsistencyPct = trend.trendConsistencyPct.round(1),
            report = report
        )
    }

    private data class TrendStats(
        val buyHoldReturnPct: Double,
        val maxDrawdownPct: Double,
        val timeAboveMaPct: Double,
        val trendConsistencyPct: Double
    )

    private fun estimateHurst(logPrices: List<Double>): Regression {
        val n = logPrices.size
        val lags = listOf(2, 4, 8, 16, 32).filter { it < n / 4 }
        if (lags.size < 3) return Regression(0.5, 0.0)
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (lag in lags) {
            val diffs = (lag until n).map { logPrices[it] - logPrices[it - lag] }
            val tau = sqrt(diffs.map { it * it }.average()).coerceAtLeast(1e-12)
            xs.add(ln(lag.toDouble()))
            ys.add(ln(tau))
        }
        return linearRegression(xs, ys)
    }

    private fun varianceRatio(returns: List<Double>, k: Int): Double {
        if (returns.size < k * 4) return 1.0
        val v1 = variance(returns)
        if (v1 <= 1e-12) return 1.0
        val kReturns = (k - 1 until returns.size).map { i ->
            var sum = 0.0
            for (j in i - k + 1..i) sum += returns[j]
            sum
        }
        return (variance(kReturns) / (k * v1)).coerceIn(0.0, 3.0)
    }

    private fun dominantCycle(logPrices: List<Double>): Cycle {
        val n = logPrices.size
        if (n < 90) return Cycle(0.0, 20, 20)
        val residuals = detrend(logPrices)
        val full = dominantPeriod(residuals)
        val half = n / 2
        val left = dominantPeriod(detrend(logPrices.take(half)))
        val right = dominantPeriod(detrend(logPrices.takeLast(half)))
        val base = if (full.period > 0.0) full.period else 1.0
        val spread = if (left.period > 0.0 && right.period > 0.0) {
            abs(left.period - right.period) / base
        } else 1.0
        val stability = (100.0 - spread * 180.0).coerceIn(10.0, 100.0).toInt()
        return Cycle(full.period, full.strength, stability)
    }

    private fun dominantPeriod(values: List<Double>): Cycle {
        val n = values.size
        if (n < 45) return Cycle(0.0, 20, 20)
        val powers = mutableListOf<Pair<Int, Double>>()
        for (period in 5..60) {
            if (period >= n / 2) break
            var re = 0.0
            var im = 0.0
            for (i in values.indices) {
                val angle = 2.0 * Math.PI * i / period
                re += values[i] * cos(angle)
                im += values[i] * sin(angle)
            }
            powers.add(period to (re * re + im * im) / n)
        }
        if (powers.isEmpty()) return Cycle(0.0, 20, 20)
        val top = powers.maxByOrNull { it.second } ?: return Cycle(0.0, 20, 20)
        val sorted = powers.map { it.second }.sorted()
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-12)
        val dominance = top.second / median
        val strength = ((dominance - 1.0) / 5.0 * 100.0).coerceIn(5.0, 100.0).toInt()
        return Cycle(top.first.toDouble(), strength, 50)
    }

    private fun swingBacktest(highs: List<Double>, lows: List<Double>, closes: List<Double>): List<Trade> {
        val n = min(closes.size, min(highs.size, lows.size))
        if (n < 90) return emptyList()
        val start = max(40, n - 252)
        val atr = Indicators.atr(highs, lows, closes, 14)
        val trades = mutableListOf<Trade>()
        var inTrade = false
        var entry = 0.0
        var target = 0.0
        var stop = 0.0
        var entryIdx = 0
        val lookback = 40

        for (i in start until n) {
            if (!inTrade) {
                if (i < lookback) continue
                val support = lows.subList(i - lookback, i).min()
                val resistance = highs.subList(i - lookback, i).max()
                val range = resistance - support
                val a = (atr.getOrNull(i - 1) ?: 0.0).coerceAtLeast(closes[i] * 0.01)
                if (range < a * 3.0) continue
                if (closes[i] <= support + range * 0.25) {
                    inTrade = true
                    entry = closes[i]
                    target = resistance - range * 0.08
                    stop = entry - a * 1.5
                    entryIdx = i
                }
            } else {
                val hold = i - entryIdx
                val exitPrice = when {
                    lows[i] <= stop -> stop
                    highs[i] >= target -> target
                    hold >= 30 -> closes[i]
                    else -> null
                }
                if (exitPrice != null) {
                    trades.add(Trade((exitPrice - entry) / entry * 100.0, hold))
                    inTrade = false
                }
            }
        }
        if (inTrade && entry > 0.0) {
            trades.add(Trade((closes.last() - entry) / entry * 100.0, n - 1 - entryIdx))
        }
        return trades
    }

    private fun trendStats(closes: List<Double>): TrendStats {
        val n = closes.size
        val start = max(0, n - 252)
        val slice = closes.subList(start, n)
        val buyHoldReturn = if (slice.first() > 0.0) (slice.last() - slice.first()) / slice.first() * 100.0 else 0.0
        var peak = slice.first()
        var maxDd = 0.0
        for (v in slice) {
            peak = max(peak, v)
            if (peak > 0.0) maxDd = min(maxDd, (v - peak) / peak * 100.0)
        }
        val maPeriod = if (n >= 200) 200 else 50
        val ma = Indicators.ema(closes, maPeriod)
        var above = 0
        var count = 0
        var positiveSlope = 0
        for (i in start until n) {
            val m = ma.getOrNull(i) ?: continue
            if (closes[i] > m) above++
            count++
            val prev = ma.getOrNull(i - 10)
            if (prev != null && m > prev) positiveSlope++
        }
        return TrendStats(
            buyHoldReturnPct = buyHoldReturn,
            maxDrawdownPct = abs(maxDd),
            timeAboveMaPct = if (count > 0) above * 100.0 / count else 0.0,
            trendConsistencyPct = if (count > 0) positiveSlope * 100.0 / count else 0.0
        )
    }

    private fun linearRegression(xs: List<Double>, ys: List<Double>): Regression {
        if (xs.size != ys.size || xs.size < 2) return Regression(0.0, 0.0)
        val mx = xs.average()
        val my = ys.average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            cov += dx * dy
            vx += dx * dx
            vy += dy * dy
        }
        val slope = if (vx > 0.0) cov / vx else 0.0
        val r2 = if (vx > 0.0 && vy > 0.0) (cov * cov / (vx * vy)).coerceIn(0.0, 1.0) else 0.0
        return Regression(slope, r2)
    }

    private fun detrend(values: List<Double>): List<Double> {
        if (values.size < 3) return values
        val xs = values.indices.map { it.toDouble() }
        val reg = linearRegression(xs, values)
        val intercept = values.average() - reg.slope * xs.average()
        return values.mapIndexed { i, v -> v - (intercept + reg.slope * i) }
    }

    private fun autocorr(values: List<Double>, lag: Int): Double {
        if (lag <= 0 || values.size <= lag + 2) return 0.0
        val xs = values.dropLast(lag)
        val ys = values.drop(lag)
        val mx = xs.average()
        val my = ys.average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            cov += dx * dy
            vx += dx * dx
            vy += dy * dy
        }
        val denom = sqrt(vx * vy)
        return if (denom > 0.0) (cov / denom).coerceIn(-1.0, 1.0) else 0.0
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun scoreHurstMeanReversion(h: Double): Double = ((0.58 - h) / 0.28 * 100.0).coerceIn(0.0, 100.0)
    private fun scoreHurstPersistence(h: Double): Double = ((h - 0.42) / 0.28 * 100.0).coerceIn(0.0, 100.0)
    private fun scoreVarianceMeanReversion(vr: Double): Double = ((1.20 - vr) / 0.55 * 100.0).coerceIn(0.0, 100.0)
    private fun scoreVariancePersistence(vr: Double): Double = ((vr - 0.80) / 0.55 * 100.0).coerceIn(0.0, 100.0)

    private fun scoreNegativeAutocorr(acf1: Double, acf5: Double): Double {
        val s1 = ((0.08 - acf1) / 0.22 * 100.0).coerceIn(0.0, 100.0)
        val s5 = ((0.05 - acf5) / 0.18 * 100.0).coerceIn(0.0, 100.0)
        return s1 * 0.65 + s5 * 0.35
    }

    private fun scorePositiveAutocorr(acf1: Double, acf5: Double): Double {
        val s1 = ((acf1 + 0.04) / 0.20 * 100.0).coerceIn(0.0, 100.0)
        val s5 = ((acf5 + 0.02) / 0.16 * 100.0).coerceIn(0.0, 100.0)
        return s1 * 0.45 + s5 * 0.55
    }

    private fun scoreSwingBacktest(trades: List<Trade>): Double {
        if (trades.isEmpty()) return 15.0
        val win = trades.count { it.returnPct > 0.0 } * 100.0 / trades.size
        val exp = trades.map { it.returnPct }.average()
        val tradeCountScore = min(100.0, trades.size * 20.0)
        val winScore = ((win - 35.0) / 35.0 * 100.0).coerceIn(0.0, 100.0)
        val expScore = ((exp + 1.0) / 5.0 * 100.0).coerceIn(0.0, 100.0)
        return tradeCountScore * 0.25 + winScore * 0.35 + expScore * 0.40
    }

    private fun scoreBuyHoldReturn(ret: Double): Double = ((ret + 10.0) / 45.0 * 100.0).coerceIn(0.0, 100.0)
    private fun scoreDrawdown(dd: Double): Double = (100.0 - (dd / 35.0 * 100.0)).coerceIn(0.0, 100.0)

    private fun weightedScore(vararg parts: Pair<Double, Double>): Int {
        val totalWeight = parts.sumOf { it.second }
        if (totalWeight <= 0.0) return 0
        val total = parts.sumOf { (score, weight) -> score.coerceIn(0.0, 100.0) * weight }
        return Math.round(total / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun Double.round(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
