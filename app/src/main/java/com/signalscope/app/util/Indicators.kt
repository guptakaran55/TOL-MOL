package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Technical indicator calculations — direct port from Python app.py.
 * All algorithms produce identical results to the Python version.
 */
object Indicators {

    // ═══════════════════════════════════════════════════════
    // MOVING AVERAGES
    // ═══════════════════════════════════════════════════════

    fun sma(data: List<Double>, period: Int): List<Double?> {
        return data.mapIndexed { i, _ ->
            if (i < period - 1) null
            else data.subList(i - period + 1, i + 1).average()
        }
    }

    fun ema(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty()) return emptyList()
        val result = mutableListOf<Double>()
        val multiplier = 2.0 / (period + 1)
        result.add(data[0])
        for (i in 1 until data.size) {
            result.add(data[i] * multiplier + result[i - 1] * (1 - multiplier))
        }
        return result
    }

    // ═══════════════════════════════════════════════════════
    // RSI
    // ═══════════════════════════════════════════════════════

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size < period + 1) return List(closes.size) { null }
        val result = mutableListOf<Double?>()
        result.add(null) // first element has no diff

        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()

        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            gains.add(if (diff > 0) diff else 0.0)
            losses.add(if (diff < 0) -diff else 0.0)
        }

        // EMA-style (Wilder's) smoothing — matches pandas ewm(com=period-1)
        if (gains.size < period) return List(closes.size) { null }

        var avgGain = gains.subList(0, period).average()
        var avgLoss = losses.subList(0, period).average()

        for (i in 0 until period) result.add(null)

        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
            val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
            result.add(100.0 - (100.0 / (1.0 + rs)))
        }

        return result
    }

    // ═══════════════════════════════════════════════════════
    // BOLLINGER BANDS
    // ═══════════════════════════════════════════════════════

    data class BollingerBands(
        val middle: List<Double?>,
        val upper: List<Double?>,
        val lower: List<Double?>
    )

    fun bollingerBands(closes: List<Double>, period: Int = 20, stdDev: Double = 2.0): BollingerBands {
        val mid = sma(closes, period)
        val upper = mutableListOf<Double?>()
        val lower = mutableListOf<Double?>()

        for (i in closes.indices) {
            val m = mid[i]
            if (m == null || i < period - 1) {
                upper.add(null)
                lower.add(null)
            } else {
                val window = closes.subList(i - period + 1, i + 1)
                val mean = window.average()
                val std = kotlin.math.sqrt(window.map { (it - mean) * (it - mean) }.average())
                upper.add(m + stdDev * std)
                lower.add(m - stdDev * std)
            }
        }
        return BollingerBands(mid, upper, lower)
    }

    // ═══════════════════════════════════════════════════════
    // MACD
    // ═══════════════════════════════════════════════════════

    data class MACDResult(
        val macdLine: List<Double>,
        val signalLine: List<Double>,
        val histogram: List<Double>
    )

    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): MACDResult {
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val macdLine = emaFast.zip(emaSlow).map { (f, s) -> f - s }
        val signalLine = ema(macdLine, signal)
        val histogram = macdLine.zip(signalLine).map { (m, s) -> m - s }
        return MACDResult(macdLine, signalLine, histogram)
    }

    /**
     * EMA-smoothed first derivative of the MACD line.
     *
     * Raw `macdLine[i] - macdLine[i-1]` flips sign every 1-3 days on noisy
     * candles — useless for trend detection. Smoothing with a short EMA
     * (typical: 2 for fast/exit signals, 5 for slow/entry signals) collapses
     * the noise and exposes the underlying inflection. Returns a series the
     * same length as `macdLine`; first element is 0.0 (no prior to diff).
     *
     * Asymmetric smoothing across entry/exit windows is the standard
     * recipe: use a longer window for entries (slower, fewer fakeouts) and
     * a shorter window for exits (faster, less give-back when momentum dies).
     */
    fun macdSlopeSmoothed(macdLine: List<Double>, smoothingWindow: Int = 5): List<Double> {
        if (macdLine.size < 2) return List(macdLine.size) { 0.0 }
        val rawSlope = ArrayList<Double>(macdLine.size)
        rawSlope.add(0.0)
        for (i in 1 until macdLine.size) {
            rawSlope.add(macdLine[i] - macdLine[i - 1])
        }
        return ema(rawSlope, smoothingWindow.coerceAtLeast(2))
    }

    // ═══════════════════════════════════════════════════════
    // OBV
    // ═══════════════════════════════════════════════════════

    fun obv(closes: List<Double>, volumes: List<Long>): List<Double> {
        val result = mutableListOf<Double>()
        result.add(0.0)
        for (i in 1 until closes.size) {
            val direction = sign(closes[i] - closes[i - 1])
            result.add(result.last() + direction * volumes[i])
        }
        return result
    }

    // ═══════════════════════════════════════════════════════
    // TWO-PIVOT DIVERGENCE (price vs MACD histogram / OBV)
    // ═══════════════════════════════════════════════════════

    data class DivergenceResult(
        val histBullish: Boolean = false,
        val histBearish: Boolean = false,
        val volBullish: Boolean = false,
        val volBearish: Boolean = false,
        val detail: String = ""
    ) {
        val any: Boolean get() = histBullish || histBearish || volBullish || volBearish
    }

    /**
     * Classic two-pivot divergence. Anchors on the two most recent CONFIRMED
     * swing pivots of price (close), then compares the indicator value (MACD
     * histogram and OBV) at those same pivot bars:
     *   Bearish — price higher-high but indicator lower-high (at swing highs)
     *   Bullish — price lower-low  but indicator higher-low  (at swing lows)
     *
     * A pivot needs [k] strictly higher (highs) / lower (lows) closes on each
     * side, so the last [k] bars are never pivots (unconfirmed). Only pivots
     * within the last [lookback] bars are considered, for recency.
     *
     * Unlike the simpler OBV-vs-moving-average check in StockAnalyzer, this
     * compares two real price swings against the indicator at those swings —
     * the textbook definition. Purely informational; feeds no score.
     */
    fun divergence(
        closes: List<Double>,
        histogram: List<Double>,
        obv: List<Double>,
        lookback: Int = 60,
        k: Int = 3
    ): DivergenceResult {
        val n = closes.size
        if (n < 2 * k + 5 || histogram.size != n) return DivergenceResult()
        val hasObv = obv.size == n
        val from = maxOf(k, n - lookback)

        val highs = ArrayList<Int>()
        val lows = ArrayList<Int>()
        for (i in from..(n - 1 - k)) {
            var isHigh = true
            var isLow = true
            for (j in 1..k) {
                if (closes[i] <= closes[i - j] || closes[i] <= closes[i + j]) isHigh = false
                if (closes[i] >= closes[i - j] || closes[i] >= closes[i + j]) isLow = false
            }
            if (isHigh) highs.add(i)
            if (isLow) lows.add(i)
        }

        var histBull = false; var histBear = false; var volBull = false; var volBear = false
        val details = ArrayList<String>()

        if (highs.size >= 2) {
            val a = highs[highs.size - 2]; val b = highs[highs.size - 1]
            if (closes[b] > closes[a]) {  // price higher high
                if (histogram[b] < histogram[a]) {
                    histBear = true
                    details.add("Bearish hist: price HH ${divFmt(closes[a])}→${divFmt(closes[b])}, MACD-hist LH ${divFmt(histogram[a])}→${divFmt(histogram[b])}")
                }
                if (hasObv && obv[b] < obv[a]) {
                    volBear = true
                    details.add("Bearish vol: price HH but OBV lower (distribution)")
                }
            }
        }
        if (lows.size >= 2) {
            val a = lows[lows.size - 2]; val b = lows[lows.size - 1]
            if (closes[b] < closes[a]) {  // price lower low
                if (histogram[b] > histogram[a]) {
                    histBull = true
                    details.add("Bullish hist: price LL ${divFmt(closes[a])}→${divFmt(closes[b])}, MACD-hist HL ${divFmt(histogram[a])}→${divFmt(histogram[b])}")
                }
                if (hasObv && obv[b] > obv[a]) {
                    volBull = true
                    details.add("Bullish vol: price LL but OBV higher (accumulation)")
                }
            }
        }
        return DivergenceResult(histBull, histBear, volBull, volBear, details.joinToString(" · "))
    }

    private fun divFmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    // ═══════════════════════════════════════════════════════
    // RAW-VOLUME BEHAVIOUR PATTERNS (distinct from OBV divergence)
    // ═══════════════════════════════════════════════════════
    // Four named patterns, kept SEPARATE from the OBV two-pivot divergence above:
    //   Bullish dry-up   — new swing low on LOWER raw volume (sellers exhausting)
    //   Bullish climax    — new low + volume SPIKE + close near the bar's high
    //                       (capitulation / long lower wick, supply absorbed)
    //   Bearish dry-up   — new swing high on LOWER raw volume (buyers thinning)
    //   Bearish churning  — volume SPIKE near the high with no price progress
    //                       (distribution into demand)
    // Heuristic thresholds (spikeMult / wickFrac / nearHighPct / churnMaxMovePct)
    // are defaulted here and easy to tune later.

    data class VolumePatternResult(
        val bullDryUp: Boolean = false,
        val bearDryUp: Boolean = false,
        val bullCapitulation: Boolean = false,
        val bearChurning: Boolean = false,
        val detail: String = ""
    ) {
        val anyBull: Boolean get() = bullDryUp || bullCapitulation
        val anyBear: Boolean get() = bearDryUp || bearChurning
        val any: Boolean get() = anyBull || anyBear
    }

    fun volumePatterns(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        volumes: List<Long>,
        lookback: Int = 60,
        k: Int = 3,
        spikeMult: Double = 2.0,      // volume "spike" = >= 2x the recent average
        wickFrac: Double = 0.6,       // close must sit in the top 40% of the bar's range
        nearHighPct: Double = 0.97,   // bar high within 3% of the lookback high = "near resistance"
        churnMaxMovePct: Double = 0.5 // day-over-day move under 0.5% = "no progress"
    ): VolumePatternResult {
        val n = closes.size
        if (n < 2 * k + 5 || highs.size != n || lows.size != n || volumes.size != n) return VolumePatternResult()
        val from = maxOf(k, n - lookback)

        val volWindow = volumes.takeLast(20).map { it.toDouble() }
        val avgVol = if (volWindow.isNotEmpty()) volWindow.average() else 0.0

        // Price swing pivots — same definition as divergence().
        val highsIdx = ArrayList<Int>(); val lowsIdx = ArrayList<Int>()
        for (i in from..(n - 1 - k)) {
            var isHigh = true; var isLow = true
            for (j in 1..k) {
                if (closes[i] <= closes[i - j] || closes[i] <= closes[i + j]) isHigh = false
                if (closes[i] >= closes[i - j] || closes[i] >= closes[i + j]) isLow = false
            }
            if (isHigh) highsIdx.add(i)
            if (isLow) lowsIdx.add(i)
        }

        var bullDry = false; var bearDry = false; var bullCap = false; var bearChurn = false
        val details = ArrayList<String>()

        // Bearish dry-up: two recent swing highs, higher price on lower volume.
        if (highsIdx.size >= 2) {
            val a = highsIdx[highsIdx.size - 2]; val b = highsIdx[highsIdx.size - 1]
            if (closes[b] > closes[a] && volumes[b] < volumes[a]) {
                bearDry = true
                details.add("Bearish dry-up: new high on lighter volume (${volFmt(volumes[a])}→${volFmt(volumes[b])})")
            }
        }
        // Bullish dry-up: two recent swing lows, lower price on lower volume.
        if (lowsIdx.size >= 2) {
            val a = lowsIdx[lowsIdx.size - 2]; val b = lowsIdx[lowsIdx.size - 1]
            if (closes[b] < closes[a] && volumes[b] < volumes[a]) {
                bullDry = true
                details.add("Bullish dry-up: new low on lighter volume (${volFmt(volumes[a])}→${volFmt(volumes[b])})")
            }
        }

        // Single-bar patterns: scan the last few bars for climax / churning.
        if (avgVol > 0.0) {
            val lookLow = lows.subList(from, n).min()
            val lookHigh = highs.subList(from, n).max()
            val scanStart = maxOf(1, n - 3)
            for (i in scanStart until n) {
                val range = highs[i] - lows[i]
                val spike = volumes[i] >= spikeMult * avgVol
                // Bullish capitulation: spike volume at a new low, close near the high.
                if (!bullCap && spike && lows[i] <= lookLow * 1.001 && range > 0.0 &&
                    closes[i] >= lows[i] + wickFrac * range
                ) {
                    bullCap = true
                    details.add("Bullish capitulation: ${volSpikeLabel(volumes[i], avgVol)} volume at a new low, closed near the high")
                }
                // Bearish churning: spike volume near the high but no day-over-day progress.
                if (!bearChurn && spike && highs[i] >= lookHigh * nearHighPct) {
                    val movePct = if (closes[i - 1] > 0.0)
                        kotlin.math.abs(closes[i] - closes[i - 1]) / closes[i - 1] * 100.0 else 999.0
                    if (movePct < churnMaxMovePct) {
                        bearChurn = true
                        details.add("Bearish churning: ${volSpikeLabel(volumes[i], avgVol)} volume near resistance, price went nowhere")
                    }
                }
            }
        }

        return VolumePatternResult(bullDry, bearDry, bullCap, bearChurn, details.joinToString(" · "))
    }

    private fun volFmt(v: Long): String = when {
        v >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fB", v / 1e9)
        v >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", v / 1e6)
        v >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", v / 1e3)
        else -> v.toString()
    }
    private fun volSpikeLabel(v: Long, avg: Double): String =
        if (avg > 0) String.format(java.util.Locale.US, "%.1f×", v / avg) else "high"

    // ═══════════════════════════════════════════════════════
    // ADX with Directional Indicators
    // ═══════════════════════════════════════════════════════

    data class ADXResult(
        val adx: List<Double>,
        val plusDi: List<Double>,
        val minusDi: List<Double>
    )

    fun adx(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): ADXResult {
        val n = closes.size
        if (n < period + 1) return ADXResult(List(n) { 0.0 }, List(n) { 0.0 }, List(n) { 0.0 })

        val plusDm = mutableListOf(0.0)
        val minusDm = mutableListOf(0.0)
        val tr = mutableListOf(highs[0] - lows[0])

        for (i in 1 until n) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            plusDm.add(if (upMove > downMove && upMove > 0) upMove else 0.0)
            minusDm.add(if (downMove > upMove && downMove > 0) downMove else 0.0)

            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            tr.add(maxOf(tr1, tr2, tr3))
        }

        val atr = ema(tr, period)
        val smoothPlusDm = ema(plusDm, period)
        val smoothMinusDm = ema(minusDm, period)

        val plusDi = smoothPlusDm.zip(atr).map { (dm, a) ->
            if (a == 0.0) 0.0 else 100.0 * dm / a
        }
        val minusDi = smoothMinusDm.zip(atr).map { (dm, a) ->
            if (a == 0.0) 0.0 else 100.0 * dm / a
        }

        val dx = plusDi.zip(minusDi).map { (p, m) ->
            val sum = p + m
            if (sum == 0.0) 0.0 else 100.0 * abs(p - m) / sum
        }

        val adxLine = ema(dx, period)

        return ADXResult(adxLine, plusDi, minusDi)
    }

    // ═══════════════════════════════════════════════════════
    // ATR
    // ═══════════════════════════════════════════════════════

    fun atr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): List<Double> {
        val n = closes.size
        if (n < 2) return List(n) { 0.0 }

        val trList = mutableListOf(highs[0] - lows[0])
        for (i in 1 until n) {
            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            trList.add(maxOf(tr1, tr2, tr3))
        }
        return ema(trList, period)
    }

    // ═══════════════════════════════════════════════════════
    // SUPPORT & RESISTANCE — Tier 1 adaptive algorithm
    //
    // Upgrades over the naive 60-day/1.5% version:
    //   (a) ADAPTIVE LOOKBACK: measures the stock's natural cycle length
    //       by counting pivot density in the last 120 days. Fast-cycling
    //       stocks use a short window; slow-trending stocks use a long one.
    //       (Fixes cases like BHARTIARTL where the true resistance at ₹2050
    //        is 5 months old and was invisible to the old 60-day window.)
    //   (b) ATR-BASED CLUSTERING: clustering tolerance scales with the stock's
    //       volatility (0.8 × ATR) instead of a fixed 1.5% everywhere.
    //   (c) CLUSTER SCORING: each cluster is ranked by
    //         score = touches × avg_reversal_size × recency_weight
    //       so a level tested 3 times beats one tested once, even if the
    //       latter is slightly nearer. Returns the STRONGEST cluster that
    //       passes the distance filter.
    //   (d) MIN-DISTANCE FILTER (≥1 × ATR): rejects levels within one day's
    //       typical move — those are noise, not resistance.
    //   (e) ROLE REVERSAL: broken supports above current price are treated
    //       as resistance, broken resistances below as support.
    // ═══════════════════════════════════════════════════════

    data class SupportResistance(val support: Double, val resistance: Double)

    /**
     * Estimate a good lookback window based on swing-point density.
     * Runs a quick low-resolution pivot scan over the last 120 days;
     * many pivots → short natural cycle → short lookback.
     */
    private fun estimateAdaptiveLookback(highs: List<Double>, lows: List<Double>): Int {
        val scanLen = min(120, highs.size - 10)
        if (scanLen < 30) return min(60, highs.size - 10)

        val rH = highs.takeLast(scanLen)
        val rL = lows.takeLast(scanLen)
        val w = 3  // looser pivot window for fast cycle estimation
        var pivots = 0
        for (i in w until rH.size - w) {
            var isHigh = true
            var isLow = true
            for (j in 1..w) {
                if (rH[i] <= rH[i - j] || rH[i] <= rH[i + j]) isHigh = false
                if (rL[i] >= rL[i - j] || rL[i] >= rL[i + j]) isLow = false
            }
            if (isHigh || isLow) pivots++
        }

        // Pivots alternate high/low → a full cycle ≈ 2 pivots.
        // Cycle length (days) ≈ scanLen / (pivots / 2) = 2 × scanLen / pivots
        // Lookback should contain ~4–5 full cycles to see repeated tests.
        val cycleLen = if (pivots < 2) scanLen else (2.0 * scanLen / pivots).toInt()
        val lookback = (cycleLen * 5).coerceIn(50, 252)
        return min(lookback, highs.size - 10)
    }

    fun findSupportResistance(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        lookback: Int = -1  // -1 = auto (adaptive); positive value overrides
    ): SupportResistance {
        val n = closes.size
        val lb = if (lookback > 0) min(lookback, n - 10) else estimateAdaptiveLookback(highs, lows)
        val price = closes.last()

        val rHighs = highs.takeLast(lb)
        val rLows = lows.takeLast(lb)
        val rCloses = closes.takeLast(lb)

        // Compute ATR over the last 14 bars for volatility-scaled clustering/filtering
        val atrSeries = atr(highs, lows, closes, 14)
        val atrVal = atrSeries.lastOrNull()?.takeIf { it > 0 } ?: (price * 0.01) // fallback 1%

        // ── Detect swing highs/lows with 5-bar pivots ──
        data class Pivot(val price: Double, val idx: Int)  // idx in the windowed slice (0 = oldest)
        val window = 5
        val swingHighs = mutableListOf<Pivot>()
        val swingLows = mutableListOf<Pivot>()
        for (i in window until rHighs.size - window) {
            var isHigh = true
            var isLow = true
            for (j in 1..window) {
                if (rHighs[i] <= rHighs[i - j] || rHighs[i] <= rHighs[i + j]) isHigh = false
                if (rLows[i] >= rLows[i - j] || rLows[i] >= rLows[i + j]) isLow = false
            }
            if (isHigh) swingHighs.add(Pivot(rHighs[i], i))
            if (isLow) swingLows.add(Pivot(rLows[i], i))
        }

        // ── ATR-based clustering ──
        // Two pivots join the same cluster if within 0.8 × ATR of each other.
        // Output: list of (avgPrice, touchCount, avgReversal, latestIdx)
        data class Cluster(val price: Double, val touches: Int, val avgReversal: Double, val latestIdx: Int)

        fun cluster(pivots: List<Pivot>, isResistance: Boolean): List<Cluster> {
            if (pivots.isEmpty()) return emptyList()
            val tol = 0.8 * atrVal
            val sorted = pivots.sortedBy { it.price }
            val groups = mutableListOf<MutableList<Pivot>>()
            var curr = mutableListOf(sorted[0])
            for (p in sorted.drop(1)) {
                if (p.price - curr[0].price <= tol) curr.add(p)
                else {
                    groups.add(curr); curr = mutableListOf(p)
                }
            }
            groups.add(curr)

            return groups.map { g ->
                val avgP = g.map { it.price }.average()
                val latestIdx = g.maxOf { it.idx }
                // avg reversal: how far price moved AWAY from the level in the 5 bars after each pivot
                val reversals = g.mapNotNull { pv ->
                    val afterIdx = pv.idx + 5
                    if (afterIdx >= rCloses.size) null
                    else {
                        val priceAfter = rCloses[afterIdx]
                        if (isResistance) (pv.price - priceAfter).coerceAtLeast(0.0)
                        else (priceAfter - pv.price).coerceAtLeast(0.0)
                    }
                }
                val avgRev = if (reversals.isEmpty()) atrVal else reversals.average()
                Cluster(avgP, g.size, avgRev, latestIdx)
            }
        }

        val resClusters = cluster(swingHighs, isResistance = true)
        val supClusters = cluster(swingLows, isResistance = false)

        // ── Score clusters: touches × reversal × recency ──
        fun score(c: Cluster): Double {
            val touchBonus = c.touches.toDouble()
            val reversalBonus = (c.avgReversal / atrVal).coerceAtMost(5.0)
            // recency: latestIdx is [0, lb). Higher idx = more recent. Weight 0.5 → 1.5 linearly.
            val recency = 0.5 + (c.latestIdx.toDouble() / lb) * 1.0
            return touchBonus * reversalBonus * recency
        }

        // ── Select resistance ──
        // Candidates: all clusters above (price + 1 × ATR)  — min-distance filter.
        // ALSO include clusters below current price that got broken → role reversal candidates
        //   (only if significantly above current, to avoid picking the just-broken support).
        val minDistance = atrVal
        val resCandidates = mutableListOf<Cluster>()
        resClusters.filter { it.price >= price + minDistance }.forEach { resCandidates.add(it) }
        // Role reversal: a broken support cluster well above current price acts as resistance
        supClusters.filter { it.price >= price + minDistance * 2 }.forEach { resCandidates.add(it) }

        val resistance = resCandidates.maxByOrNull { score(it) }?.price
            ?: run {
                // Fallback: raw pivot R1, then the all-time max in window
                val pivot = (rHighs.last() + rLows.last() + rCloses.last()) / 3
                val r1 = 2 * pivot - rLows.last()
                if (r1 > price) r1 else rHighs.max()
            }

        // ── Select support (mirror logic) ──
        val supCandidates = mutableListOf<Cluster>()
        supClusters.filter { it.price <= price - minDistance }.forEach { supCandidates.add(it) }
        // Role reversal: a broken resistance well below current price acts as support
        resClusters.filter { it.price <= price - minDistance * 2 }.forEach { supCandidates.add(it) }

        val support = supCandidates.maxByOrNull { score(it) }?.price
            ?: run {
                val pivot = (rHighs.last() + rLows.last() + rCloses.last()) / 3
                val s1 = 2 * pivot - rHighs.last()
                if (s1 < price) s1 else rLows.min()
            }

        return SupportResistance(
            support = Math.round(support * 100.0) / 100.0,
            resistance = Math.round(resistance * 100.0) / 100.0
        )
    }

    // ═══════════════════════════════════════════════════════
    // TIER 2 — WAVE PROJECTION (on-demand, via detail-modal button)
    //
    // Projects a "where is this stock likely to oscillate next" range
    // based on:
    //   trend   = current EMA50 + its recent slope extrapolated forward
    //   envelope = ±2σ of (price − EMA50) over last 50 days
    // NOT a forecast of exact price — a probabilistic channel.
    // Cheap: runs in <5ms. Precomputed during scan but hidden behind a
    // button so users treat it as prediction, not fact.
    // ═══════════════════════════════════════════════════════

    data class WaveProjection(
        val projectedCeiling: Double,
        val projectedFloor: Double,
        val projectedMidpoint: Double,  // trend-only midpoint (where EMA50 will be)
        val daysAhead: Int
    )

    fun projectWaveRange(closes: List<Double>, daysAhead: Int = 20): WaveProjection? {
        val n = closes.size
        if (n < 60) return null

        val ema50Series = ema(closes, 50)
        val ema50Now = ema50Series.lastOrNull() ?: return null
        if (ema50Now <= 0) return null

        // Slope = (EMA50 now − EMA50 ten bars ago) / 10 → per-day rate
        val ema50Past = ema50Series.getOrNull(n - 11) ?: ema50Now
        val slopePerDay = (ema50Now - ema50Past) / 10.0

        // Envelope = 2σ of (close − EMA50) over last 50 bars
        val recentResid = (n - 50 until n).mapNotNull { i ->
            val e = ema50Series.getOrNull(i) ?: return@mapNotNull null
            closes[i] - e
        }
        if (recentResid.isEmpty()) return null
        val mean = recentResid.average()
        val variance = recentResid.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        val envelope = 2.0 * stdDev

        val projectedMid = ema50Now + slopePerDay * daysAhead
        return WaveProjection(
            projectedCeiling = Math.round((projectedMid + envelope) * 100.0) / 100.0,
            projectedFloor = Math.round((projectedMid - envelope) * 100.0) / 100.0,
            projectedMidpoint = Math.round(projectedMid * 100.0) / 100.0,
            daysAhead = daysAhead
        )
    }
}
