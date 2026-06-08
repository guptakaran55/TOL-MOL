package com.signalscope.app.util

import com.signalscope.app.data.CandleData
import com.signalscope.app.data.ScoringWeights
import com.signalscope.app.data.StockAnalysis
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analyzes a stock using 6 technical indicators and produces buy/sell scores.
 * This is a direct port of the analyze_stock() function from Python app.py.
 */
object StockAnalyzer {
    private const val SMOOTH_MACD_HOOK_LOW_ZONE_MAX = 35.0
    private const val SMOOTH_MACD_HOOK_EPS_MULT = 0.05

    fun analyze(
        candles: List<CandleData>,
        symbol: String,
        name: String,
        token: String,
        minAvgVolume: Int = 100000,
        w: ScoringWeights = ScoringWeights()
    ): StockAnalysis? {
        val n = candles.size
        if (n < 50) return null

        val closes = candles.map { it.close }
        val highs = candles.map { it.high }
        val lows = candles.map { it.low }
        val volumes = candles.map { it.volume }
        val price = closes.last()

        // Volume filter
        val avgVol20 = if (n >= 20) volumes.takeLast(20).average() else volumes.average()
        if (minAvgVolume > 0 && avgVol20 < minAvgVolume) return null
        val kalmanTrend = kalmanPriceTrend(closes, horizon = 252)

        // ── SMA (200 or 50 fallback) ──
        val hasSma200 = n >= 200
        val hasSma50 = n >= 50
        val smaValues: List<Double?>
        @Suppress("unused") val smaLabel: String
        val smaMaxPts: Int
        val sma200Val: Double?
        val sma200Prev: Double?
        val sma200Slope: Double

        when {
            hasSma200 -> {
                smaValues = Indicators.sma(closes, 200)
                sma200Val = smaValues.last()
                sma200Prev = smaValues[smaValues.size - 2]
                sma200Slope = if (sma200Val != null && sma200Prev != null) sma200Val - sma200Prev else 0.0
                smaLabel = "SMA(200)"
                smaMaxPts = w.buySma200Pts
            }
            hasSma50 -> {
                smaValues = Indicators.sma(closes, 50)
                sma200Val = smaValues.last()
                sma200Prev = if (smaValues.size >= 2) smaValues[smaValues.size - 2] else sma200Val
                sma200Slope = if (sma200Val != null && sma200Prev != null) sma200Val - sma200Prev else 0.0
                smaLabel = "SMA(50) fallback"
                smaMaxPts = w.buySma50Pts
            }
            else -> {
                sma200Val = null; sma200Prev = null; sma200Slope = 0.0
                smaLabel = "N/A"; smaMaxPts = 0; smaValues = emptyList()
            }
        }

        // ── EMA(21) ──
        val ema21Val: Double?
        val ema21Slope: Double
        val ema21PctDiff: Double
        if (n >= 21) {
            val ema21Series = Indicators.ema(closes, 21)
            ema21Val = ema21Series.last()
            val ema21Prev = if (ema21Series.size >= 2) ema21Series[ema21Series.size - 2] else ema21Val
            ema21Slope = ema21Val - ema21Prev
            ema21PctDiff = if (ema21Val > 0) ((price - ema21Val) / ema21Val * 100).round(2) else 0.0
        } else {
            ema21Val = null; ema21Slope = 0.0; ema21PctDiff = 0.0
        }

        // EMA(50) for the trend stack.
        val ema50Val: Double?
        val ema50Slope: Double
        if (n >= 50) {
            val ema50Series = Indicators.ema(closes, 50)
            ema50Val = ema50Series.last()
            val ema50Prev = if (ema50Series.size >= 2) ema50Series[ema50Series.size - 2] else ema50Val
            ema50Slope = ema50Val - ema50Prev
        } else {
            ema50Val = null
            ema50Slope = 0.0
        }

        // ── EMA(200) ──
        val ema200Val: Double?
        val ema200Slope: Double
        if (n >= 200) {
            val ema200Series = Indicators.ema(closes, 200)
            ema200Val = ema200Series.last()
            val ema200Prev = if (ema200Series.size >= 2) ema200Series[ema200Series.size - 2] else ema200Val
            ema200Slope = ema200Val - ema200Prev
        } else {
            ema200Val = null
            ema200Slope = 0.0
        }

        // ── RSI ──
        val rsiValues = Indicators.rsi(closes, 14)
        val rsiVal = rsiValues.lastOrNull { it != null }
        // Multi-day RSI for flip detection
        val rsiToday = rsiVal
        val rsiYesterday = if (rsiValues.size >= 2) rsiValues[rsiValues.size - 2] else null
        val rsi2DaysAgo = if (rsiValues.size >= 3) rsiValues[rsiValues.size - 3] else null

        // RSI momentum flip: detects the exact day RSI changes direction
        val rsiBuyFlip = rsiToday != null && rsiYesterday != null && rsi2DaysAgo != null &&
                rsiToday > rsiYesterday && rsiYesterday <= rsi2DaysAgo  // just turned UP

        val rsiSellFlip = rsiToday != null && rsiYesterday != null && rsi2DaysAgo != null &&
                rsiToday < rsiYesterday && rsiYesterday >= rsi2DaysAgo  // just turned DOWN

        // ── Bollinger Bands ──
        val bb = Indicators.bollingerBands(closes, 20, 2.0)
        val bbMid = bb.middle.last()
        val bbUpper = bb.upper.last()
        val bbLower = bb.lower.last()

        // ── MACD ──
        val macdResult = Indicators.macd(closes)
        val macdLine = macdResult.macdLine
        val cm = macdLine.last()
        val cmPrev = if (macdLine.size >= 2) macdLine[macdLine.size - 2] else cm
        val cmPrev2 = if (macdLine.size >= 3) macdLine[macdLine.size - 3] else cmPrev
        val cmPrev3 = if (macdLine.size >= 4) macdLine[macdLine.size - 4] else cmPrev2
        val macdSlope = cm - cmPrev
        val macdSlopePrev = cmPrev - cmPrev2
        val macdSlopePrev2 = cmPrev2 - cmPrev3
        val macdAccel = macdSlope - macdSlopePrev
        val macdSignalVal = macdResult.signalLine.last()
        val macdHistVal = macdResult.histogram.last()

        // ── Adaptive MACD-watchdog filter ──
        // Count peaks/troughs on the histogram within ±30% of 6-month amplitude,
        // derive the stock's natural oscillation period, filter = period/3.
        // Fast-cycling midcaps → small filter; slow large-caps → larger filter.
        val hist6mo = macdResult.histogram.takeLast(126)
        var macdHalfCycles = 0
        var macdCycleDays = 0.0
        val minBarsFilterComputed: Int = run {
            if (hist6mo.size < 20) return@run 6
            val amp = (hist6mo.max()) - (hist6mo.min())
            if (amp <= 0.0) return@run 6
            val thr = amp * 0.30
            var halfCycles = 0
            for (i in 1 until hist6mo.size - 1) {
                val p = hist6mo[i - 1]; val c = hist6mo[i]; val n = hist6mo[i + 1]
                if (c > thr && c >= p && c >= n) halfCycles++      // peak
                if (c < -thr && c <= p && c <= n) halfCycles++     // trough
            }
            macdHalfCycles = halfCycles
            if (halfCycles < 4) return@run 6
            val naturalPeriod = 126.0 / (halfCycles / 2.0)
            macdCycleDays = naturalPeriod
            (naturalPeriod / 3.0).toInt().coerceIn(2, 20)
        }

        // ── MACD Phase Detection ──
        // Phase-flip detection uses a small raw-slope noise floor. The Setups
        // sliders use signed MACD angle, so keep this independent.
        val minSlopeMag = 0.01
        val wasNeg2d = macdSlopePrev <= -minSlopeMag && macdSlopePrev2 <= -minSlopeMag
        val wasPos2d = macdSlopePrev >= minSlopeMag && macdSlopePrev2 >= minSlopeMag
        val wasNegWeak = macdSlopePrev <= 0 && macdSlopePrev2 <= 0 &&
                (abs(macdSlopePrev) + abs(macdSlopePrev2)) > minSlopeMag
        val wasPosWeak = macdSlopePrev >= 0 && macdSlopePrev2 >= 0 &&
                (abs(macdSlopePrev) + abs(macdSlopePrev2)) > minSlopeMag

        val slopeCrossUp = macdSlope > 0 && (wasNeg2d || wasNegWeak)
        val slopeCrossDn = macdSlope < 0 && (wasPos2d || wasPosWeak)
        val earlyBuy = macdSlope < 0 && macdAccel > 0
        val earlySell = macdSlope > 0 && macdAccel < 0

        val macdPhase = when {
            slopeCrossUp -> "BUY FLIP"
            earlyBuy -> "EARLY BUY"
            slopeCrossDn -> "SELL FLIP"
            earlySell -> "EARLY SELL"
            macdSlope > 0 && macdAccel >= 0 -> "BULLISH"
            macdSlope < 0 && macdAccel <= 0 -> "BEARISH"
            else -> "NEUTRAL"
        }

        // ── MACD 1Y range ──
        val macdDropna = macdLine.filter { !it.isNaN() }
        var macd1yLow = 0.0; var macdLowPct = 0.0; var macdPctl = 50.0
        val macdSlopeAngle = Math.toDegrees(atan(macdSlope)).round(1)
        if (macdDropna.size >= 60) {
            val stableVals = macdDropna.drop(60)
            val recentVals = if (stableVals.size > 650) stableVals.takeLast(650) else stableVals
            macd1yLow = recentVals.min()
            val macd1yHigh = recentVals.max()
            val macdRange = if (macd1yHigh != macd1yLow) macd1yHigh - macd1yLow else 1.0
            macdLowPct = if (macd1yLow < 0) {
                (cm / macd1yLow * 100).coerceIn(0.0, 100.0).round(1)
            } else 0.0
            macdPctl = ((cm - macd1yLow) / macdRange * 100).round(1)
        }

        // ── MACD curve for sparkline (last 30 values) ──
        val macdCurve = macdDropna.takeLast(30).map { it.round(4) }
        val smoothMacdTrend = kalmanMacdTrend(macdDropna, horizon = 126)

        val macdZeroCrossUp = cm > 0 && cm < 0.15 && cmPrev <= 0
        val macdZeroCrossDn = cm < 0 && cm > -0.15 && cmPrev >= 0

        // ── OBV ──
        val obvValues = Indicators.obv(closes, volumes)
        val obvCurrent = obvValues.last()
        val obv5 = if (obvValues.size >= 5) obvValues[obvValues.size - 5] else obvValues[0]
        val obv20 = if (obvValues.size >= 20) obvValues[obvValues.size - 20] else obvValues[0]

        // ── ADX ──
        val adxResult = Indicators.adx(highs, lows, closes, 14)
        val currAdx = adxResult.adx.lastOrNull() ?: 0.0
        val currPlusDi = adxResult.plusDi.lastOrNull() ?: 0.0
        val currMinusDi = adxResult.minusDi.lastOrNull() ?: 0.0
        val bullishTrend = currPlusDi > currMinusDi

        // ── Bollinger conditions ──
        val touchedLowerBand = (n - 5 until n).any { i ->
            i >= 0 && bb.lower[i] != null && closes[i] <= bb.lower[i]!!
        }
        val touchedUpperBand = (n - 5 until n).any { i ->
            i >= 0 && bb.upper[i] != null && closes[i] >= bb.upper[i]!!
        }
        val belowMidBand = bbMid != null && price <= bbMid

        // ── Support / Resistance (Tier 1: adaptive lookback + ATR clustering + scoring) ──
        // Pass lookback = -1 so the algorithm picks its own window based on the
        // stock's natural cycle length (pivot density). This replaces the fixed 60-day
        // window which was blind to levels like BHARTIARTL's ₹2050 from 5 months ago.
        val sr = Indicators.findSupportResistance(highs, lows, closes, lookback = -1)
        val risk = (price - sr.support).round(2)
        val reward = (sr.resistance - price).round(2)
        val rrRatio = if (risk > 0) (reward / risk).round(2) else 0.0

        // ── Tier 2: Wave projection (cheap, precomputed here; surfaced only on-tap in UI) ──
        val waveProjection = Indicators.projectWaveRange(closes, daysAhead = 20)

        // ── ATR ──
        val atrValues = Indicators.atr(highs, lows, closes, 14)
        val atrVal = atrValues.lastOrNull() ?: 0.0
        val riskInAtrs = if (atrVal > 0 && risk > 0) (risk / atrVal).round(2) else 0.0
        val riskPerTrade = 10000.0
        val positionSize = if (risk > 0) (riskPerTrade / risk).toInt() else 0
        val capitalNeeded = if (positionSize > 0) (positionSize * price).round(0) else 0.0
        val potentialProfit = if (positionSize > 0) (positionSize * reward).round(0) else 0.0
        val rocPct = if (capitalNeeded > 0) (potentialProfit / capitalNeeded * 100).round(2) else 0.0

        // ═══════════════════════════════════════════════════
        // PULLBACK POINTS
        // ═══════════════════════════════════════════════════
        var pullbackScore = 0

        // 1. SMA trend
        val smaPass = sma200Val != null && price > sma200Val
        val smaPts = if (smaPass) smaMaxPts else 0
        pullbackScore += smaPts

        // 2. MACD inflection
        // A qualified MACD setup is a setup-quality reversal: upward MACD curvature,
        // acceptable signed MACD angle, a non-deteriorating long trend, and a
        // MACD percentile that is not already historically stretched.
        val rawMinAngle = w.minSlopeMagnitude
        val rawMaxAngle = abs(w.goldenBuyMaxSlope)
        val setupMinAngle = if (abs(rawMinAngle) <= 5.0) -45.0 else rawMinAngle.coerceIn(-89.0, 89.0)
        val goldenBuyMaxAngle = if (rawMaxAngle <= 5.0) 85.0 else rawMaxAngle.coerceIn(-89.0, 89.0)
        val setupSmaMinSlope = w.setupSma200MinSlope
        val setupMacdPctlMax = w.setupMacdPctlMax.coerceIn(0.0, 100.0)
        val goldenBuy = macdPctl <= setupMacdPctlMax &&
                macdAccel > 0 &&
                macdSlopeAngle >= setupMinAngle &&
                macdSlopeAngle <= goldenBuyMaxAngle &&
                sma200Slope >= setupSmaMinSlope
        val goldenBuyReport = listOf(
            "MACD percentile not stretched|${macdPctl <= setupMacdPctlMax}|${"%.1f%%".format(macdPctl)}|<= ${"%.1f%%".format(setupMacdPctlMax)}",
            "MACD acceleration up|${macdAccel > 0}|${"%.4f".format(macdAccel)}|> 0",
            "MACD angle above setup minimum|${macdSlopeAngle >= setupMinAngle}|${"%.1f°".format(macdSlopeAngle)}|>= ${"%.1f°".format(setupMinAngle)}",
            "MACD angle not overextended|${macdSlopeAngle <= goldenBuyMaxAngle}|${"%.1f°".format(macdSlopeAngle)}|<= ${"%.1f°".format(goldenBuyMaxAngle)}",
            "SMA200 slope above setup minimum|${sma200Slope >= setupSmaMinSlope}|${"%.3f".format(sma200Slope)}|>= ${"%.3f".format(setupSmaMinSlope)}"
        ).joinToString("\n")
        val macdInfPts = when {
            goldenBuy -> w.buyGoldenBuyPts
            macdZeroCrossUp && macdAccel > 0 -> w.buyMacdZeroCrossUpPts
            slopeCrossUp -> w.buySlopeCrossUpPts
            earlyBuy -> w.buyEarlyBuyPts
            else -> 0
        }
        val goldenBonus = if (goldenBuy) w.buyGoldenBonus else 0
        val pctlBonus = if (macdPctl <= w.buyMacdPctlThreshold && macdInfPts > 0) w.buyMacdPctlBonus else 0
        pullbackScore += macdInfPts + goldenBonus + pctlBonus

        // 3. RSI graduated scoring + momentum flip bonus
        var rsiPts = 0
        if (rsiVal != null) {
            val r = rsiVal
            rsiPts = when {
                r in 25.0..55.0 -> {
                    if (r <= 35.0) (5 + 15 * (r - 25) / 10).toInt()
                    else (w.buyRsiMaxPts * (55 - r) / 20).toInt()
                }
                r < 25.0 -> 3
                else -> 0
            }.coerceIn(0, w.buyRsiMaxPts)
        }
        // RSI flip bonus: RSI just turned upward while in oversold zone
        val rsiFlipValue = rsiToday ?: Double.NaN
        val rsiBuyFlipPts = if (rsiBuyFlip &&
            rsiFlipValue in w.buyRsiFlipLow..w.buyRsiFlipHigh) w.buyRsiFlipBonus else 0
        pullbackScore += rsiPts + rsiBuyFlipPts

        // 4. Bollinger Bands
        val bbPass = belowMidBand || touchedLowerBand
        val bbPts = if (bbPass) w.buyBbBasePts + (if (touchedLowerBand) w.buyBbLowerBonus else 0) else 0
        pullbackScore += bbPts

        // 5. ADX with directional check
        val adxStrong = currAdx > w.buyAdxStrongThreshold
        val adxVeryStrong = currAdx > w.buyAdxVeryStrongThreshold
        val adxPts = if (adxStrong && bullishTrend) {
            w.buyAdxBasePts + (if (adxVeryStrong) w.buyAdxVeryStrongBonus else 0)
        } else 0
        pullbackScore += adxPts

        // 6. OBV
        val obvPass = obvCurrent > obv5 && obvCurrent > obv20
        val obvPts = if (obvPass) w.buyObvPts else 0
        pullbackScore += obvPts

        // 7. EMA(21) proximity
        var emaPts = 0
        if (ema21Val != null) {
            val d = ema21PctDiff
            emaPts = when {
                d < -3 -> 3
                d in -3.0..0.0 -> w.buyEmaMaxPts
                d in 0.0..2.0 -> (w.buyEmaMaxPts * 0.8).toInt()
                d in 2.0..4.0 -> (w.buyEmaMaxPts * 0.5).toInt()
                d in 4.0..7.0 -> (w.buyEmaMaxPts * 0.2).toInt()
                else -> 0
            }
        }
        pullbackScore += emaPts

        val pullbackSignal = when {
            pullbackScore >= w.pullbackStrongThreshold -> "STRONG PULLBACK"
            pullbackScore >= w.pullbackModerateThreshold -> "PULLBACK READY"
            else -> "NO SIGNAL"
        }

        // ── Pullback-points breakdown report ─────────────────────────────────
        // Per-bucket trace string consumed by dashboard.html detail modal
        // ("Why this score?" panel). Format = pipe-delimited rows separated by
        // newlines: bucket|status|points|max|reason. status is one of:
        //   "pass" (green) — bucket fully earned
        //   "partial" (amber) — bucket earned some, not max
        //   "fail" (red) — bucket awarded 0
        // The JS side parses this and renders a colored table. Keeping it
        // pre-formatted here means the breakdown can never drift from the
        // actual scoring math — they share the same code path.
        val maxMacd = maxOf(w.buyGoldenBuyPts, w.buyMacdZeroCrossUpPts, w.buySlopeCrossUpPts, w.buyEarlyBuyPts)
        val macdReason = when {
            goldenBuy -> "qualified setup: macdPctl=${macdPctl.toInt()}% <=${setupMacdPctlMax.toInt()}, accel=${"%.4f".format(macdAccel)}>0, angle=${"%.1f°".format(macdSlopeAngle)} in [${"%.1f°".format(setupMinAngle)}..${"%.1f°".format(goldenBuyMaxAngle)}], smaSlope>=${"%.3f".format(setupSmaMinSlope)}"
            macdZeroCrossUp && macdAccel > 0 -> "MACD just crossed 0 with positive accel"
            slopeCrossUp -> "MACD slope just turned positive"
            earlyBuy -> "early-reversal heuristic fired"
            else -> "no MACD inflection event"
        }
        val rsiBucketReason = if (rsiVal != null)
            "RSI=${"%.1f".format(rsiVal)} ${if (rsiVal < 25) "(deep oversold, +3 floor)" else if (rsiVal in 25.0..55.0) "(sweet zone — graduated)" else "(too high, no points)"}"
        else "RSI unavailable"
        val bbReason = when {
            touchedLowerBand -> "touched lower band (max bonus)"
            belowMidBand -> "below mid-band (base only)"
            else -> "above mid-band, no bonus"
        }
        val adxReason = when {
            adxVeryStrong && bullishTrend -> "ADX=${"%.1f".format(currAdx)} very-strong + bullish"
            adxStrong && bullishTrend -> "ADX=${"%.1f".format(currAdx)} strong + bullish"
            adxStrong && !bullishTrend -> "ADX=${"%.1f".format(currAdx)} strong but NOT bullish — no points"
            else -> "ADX=${"%.1f".format(currAdx)} below ${w.buyAdxStrongThreshold}"
        }
        val obvReason = if (obvPass) "OBV>OBV5 AND OBV>OBV20 (accumulating)"
                        else "OBV not above both moving averages"
        val emaReason = if (ema21Val != null) {
            val d = ema21PctDiff
            "d=${"%.2f".format(d)}% — ${when {
                d < -3 -> "deep pullback (floor 3pts)"
                d in -3.0..0.0 -> "ideal pullback zone (max)"
                d in 0.0..2.0 -> "near EMA (80% of max)"
                d in 2.0..4.0 -> "slightly stretched (50%)"
                d in 4.0..7.0 -> "stretched (20%)"
                else -> "overextended (0)"
            }}"
        } else "EMA21 unavailable"
        fun status(pts: Int, max: Int) = when {
            pts <= 0 -> "fail"
            pts >= max -> "pass"
            else -> "partial"
        }
        val rows = mutableListOf<String>()
        rows.add("SMA(200)|${status(smaPts, smaMaxPts)}|$smaPts|${smaMaxPts}|${if (smaPass) "price > SMA200 (uptrend)" else "price below SMA200 (no uptrend)"}")
        rows.add("MACD inflection|${status(macdInfPts, maxMacd)}|$macdInfPts|$maxMacd|$macdReason")
        if (goldenBonus > 0)
            rows.add("Setup bonus|pass|$goldenBonus|${w.buyGoldenBonus}|early reversal confirmed")
        if (pctlBonus > 0)
            rows.add("MACD %ile bonus|pass|$pctlBonus|${w.buyMacdPctlBonus}|MACD ≤${w.buyMacdPctlThreshold}%ile + bullish event")
        rows.add("RSI graduated|${status(rsiPts, w.buyRsiMaxPts)}|$rsiPts|${w.buyRsiMaxPts}|$rsiBucketReason")
        if (rsiBuyFlipPts > 0)
            rows.add("RSI flip bonus|pass|$rsiBuyFlipPts|${w.buyRsiFlipBonus}|RSI just turned up in oversold zone")
        rows.add("Bollinger|${status(bbPts, w.buyBbBasePts + w.buyBbLowerBonus)}|$bbPts|${w.buyBbBasePts + w.buyBbLowerBonus}|$bbReason")
        rows.add("ADX|${status(adxPts, w.buyAdxBasePts + w.buyAdxVeryStrongBonus)}|$adxPts|${w.buyAdxBasePts + w.buyAdxVeryStrongBonus}|$adxReason")
        rows.add("OBV|${status(obvPts, w.buyObvPts)}|$obvPts|${w.buyObvPts}|$obvReason")
        rows.add("EMA(21) proximity|${status(emaPts, w.buyEmaMaxPts)}|$emaPts|${w.buyEmaMaxPts}|$emaReason")
        val pullbackScoreReport = rows.joinToString("\n")

        // MOMENTUM POINTS
        // Continuation lens: rewards trend alignment and active upward pressure
        // without requiring price to be in the pullback zone.
        fun pctSlope(value: Double?, slope: Double): Double =
            if (value != null && value > 0.0) (slope / value * 100.0) else 0.0

        val smaTrendOk = smaPass || (sma200Val != null && price >= sma200Val * 0.99 && sma200Slope > 0)
        val smaSlopePct = pctSlope(sma200Val, sma200Slope)
        val ema21SlopePct = pctSlope(ema21Val, ema21Slope)
        val ema50SlopePct = pctSlope(ema50Val, ema50Slope)
        val ema200SlopePct = pctSlope(ema200Val, ema200Slope)
        val rawTrendStack = listOf(
            if (smaTrendOk) 30 else 0,
            if (smaSlopePct > 0.0 || ema200SlopePct > 0.0) 25 else 0,
            if (ema50SlopePct > 0.0) 25 else 0,
            if (ema21SlopePct > 0.0) 20 else 0
        ).sum()
        val momentumTrendPts = Math.round(rawTrendStack / 100.0 * w.momentumTrendStackPts).toInt()

        val momentumMacdPts = when {
            (macdZeroCrossUp && macdAccel > 0) || slopeCrossUp -> w.momentumMacdPts
            macdPhase == "BULLISH" && macdSlope > 0 -> Math.round(w.momentumMacdPts * 0.85).toInt()
            macdSlope > 0 && macdAccel >= 0 -> Math.round(w.momentumMacdPts * 0.70).toInt()
            earlyBuy && macdAccel > 0 -> Math.round(w.momentumMacdPts * 0.55).toInt()
            else -> 0
        }

        val momentumEmaSlopePts = when {
            ema21Val == null -> 0
            ema21SlopePct > 0.0 && price >= ema21Val -> w.momentumEma21SlopePts
            ema21SlopePct > 0.0 -> Math.round(w.momentumEma21SlopePts * 0.60).toInt()
            else -> 0
        }

        val momentumRoomPts = if (ema21Val != null) {
            val d = ema21PctDiff
            when {
                d in 0.0..4.0 -> w.momentumNotOverextendedPts
                d in -2.0..0.0 -> Math.round(w.momentumNotOverextendedPts * 0.80).toInt()
                d in 4.0..7.0 -> Math.round(w.momentumNotOverextendedPts * 0.45).toInt()
                d in -4.0..-2.0 -> Math.round(w.momentumNotOverextendedPts * 0.35).toInt()
                else -> 0
            }
        } else 0

        val momentumRsiPts = if (rsiVal != null) {
            when {
                rsiVal in 45.0..68.0 -> w.momentumRsiRoomPts
                rsiVal in 35.0..45.0 -> Math.round(w.momentumRsiRoomPts * 0.60).toInt()
                rsiVal in 68.0..75.0 -> Math.round(w.momentumRsiRoomPts * 0.45).toInt()
                rsiVal < 35.0 -> Math.round(w.momentumRsiRoomPts * 0.20).toInt()
                else -> 0
            }
        } else 0

        val momentumObvPts = if (obvPass) w.momentumObvPts else 0
        val momentumScore = momentumTrendPts + momentumMacdPts + momentumEmaSlopePts +
                momentumRoomPts + momentumRsiPts + momentumObvPts
        val momentumSignal = when {
            momentumScore >= w.momentumStrongThreshold -> "STRONG MOMENTUM"
            momentumScore >= w.momentumModerateThreshold -> "MOMENTUM READY"
            else -> "NO SIGNAL"
        }

        val momentumRows = mutableListOf<String>()
        val trendReason = "trendStack=${rawTrendStack}/100, smaSlope=${"%.3f".format(smaSlopePct)}%, ema200=${"%.3f".format(ema200SlopePct)}%, ema50=${"%.3f".format(ema50SlopePct)}%, ema21=${"%.3f".format(ema21SlopePct)}%"
        val momentumMacdReason = when {
            macdZeroCrossUp && macdAccel > 0 -> "MACD crossed above zero with positive acceleration"
            slopeCrossUp -> "MACD slope flipped positive"
            macdPhase == "BULLISH" && macdSlope > 0 -> "MACD bullish and slope still positive"
            macdSlope > 0 && macdAccel >= 0 -> "MACD slope rising with non-negative acceleration"
            earlyBuy && macdAccel > 0 -> "early hook, but not full continuation yet"
            else -> "MACD is not confirming continuation"
        }
        val emaSlopeReason = if (ema21Val != null)
            "EMA21 slope=${"%.3f".format(ema21SlopePct)}%, price ${if (price >= ema21Val) "above" else "below"} EMA21"
        else "EMA21 unavailable"
        val roomReason = if (ema21Val != null) "price is ${"%.2f".format(ema21PctDiff)}% from EMA21" else "EMA21 unavailable"
        val rsiMomentumReason = if (rsiVal != null) "RSI=${"%.1f".format(rsiVal)}; ideal continuation room is 45-68" else "RSI unavailable"
        momentumRows.add("Trend stack|${status(momentumTrendPts, w.momentumTrendStackPts)}|$momentumTrendPts|${w.momentumTrendStackPts}|$trendReason")
        momentumRows.add("MACD continuation|${status(momentumMacdPts, w.momentumMacdPts)}|$momentumMacdPts|${w.momentumMacdPts}|$momentumMacdReason")
        momentumRows.add("EMA(21) slope|${status(momentumEmaSlopePts, w.momentumEma21SlopePts)}|$momentumEmaSlopePts|${w.momentumEma21SlopePts}|$emaSlopeReason")
        momentumRows.add("Not overextended|${status(momentumRoomPts, w.momentumNotOverextendedPts)}|$momentumRoomPts|${w.momentumNotOverextendedPts}|$roomReason")
        momentumRows.add("RSI room|${status(momentumRsiPts, w.momentumRsiRoomPts)}|$momentumRsiPts|${w.momentumRsiRoomPts}|$rsiMomentumReason")
        momentumRows.add("OBV|${status(momentumObvPts, w.momentumObvPts)}|$momentumObvPts|${w.momentumObvPts}|$obvReason")
        val momentumScoreReport = momentumRows.joinToString("\n")

        val buyScore = maxOf(pullbackScore, momentumScore)
        val entryMode = when {
            pullbackScore >= w.pullbackModerateThreshold && momentumScore >= w.momentumModerateThreshold -> "PULLBACK + MOMENTUM"
            momentumScore >= w.momentumModerateThreshold -> "MOMENTUM"
            pullbackScore >= w.pullbackModerateThreshold -> "PULLBACK"
            else -> "NONE"
        }
        val buySignal = when {
            buyScore >= w.buyStrongThreshold && entryMode == "MOMENTUM" -> "STRONG MOMENTUM"
            buyScore >= w.buyStrongThreshold && entryMode == "PULLBACK" -> "STRONG PULLBACK"
            buyScore >= w.buyStrongThreshold -> "STRONG ENTRY"
            buyScore >= w.buyModerateThreshold && entryMode == "MOMENTUM" -> "MOMENTUM READY"
            buyScore >= w.buyModerateThreshold && entryMode == "PULLBACK" -> "PULLBACK READY"
            buyScore >= w.buyModerateThreshold -> "ENTRY READY"
            else -> "NO SIGNAL"
        }

        // ═══════════════════════════════════════════════════════
        // SUB-SCORE A: PROFIT BOOKING (max ~58 pts)
        // Intent: sell at MACD slope peak to capture max profit
        // Fires only when price > SMA200 (uptrend intact)
        // ═══════════════════════════════════════════════════════
        var profitScore = 0

        // 1. MACD momentum — MUTUALLY EXCLUSIVE, take highest
        val profitMacdPts = when {
            slopeCrossDn -> w.profitSlopeCrossDnPts
            earlySell -> w.profitEarlySellPts
            macdZeroCrossDn && macdAccel < 0 -> w.profitMacdZeroCrossDnPts
            else -> 0
        }
        val profitPctlBonus = if (macdPctl >= w.profitMacdPctlThreshold && profitMacdPts > 0) w.profitMacdPctlBonus else 0
        profitScore += profitMacdPts + profitPctlBonus

        // 2. RSI overbought confirmation + momentum flip bonus
        val rsiProfitPts = if (rsiVal != null) {
            when {
                rsiVal > w.profitRsiExtremeThreshold -> w.profitRsiExtremePts
                rsiVal >= w.profitRsiOverboughtThreshold -> w.profitRsiOverboughtPts
                rsiVal >= w.profitRsiMildThreshold -> w.profitRsiMildPts
                rsiVal >= w.profitRsiNoiseThreshold -> w.profitRsiNoisePts
                else -> 0
            }
        } else 0
        // RSI flip bonus: RSI just turned downward while overbought
        val rsiProfitFlipPts = if (rsiSellFlip && rsiProfitPts > 0) w.profitRsiFlipBonus else 0
        profitScore += rsiProfitPts + rsiProfitFlipPts

        // 3. Bollinger stretch
        val bbSellUpper = bbUpper != null && price >= bbUpper
        val bbProfitPts = when {
            bbSellUpper -> w.profitBbUpperPts
            touchedUpperBand -> w.profitBbTouchedPts
            else -> 0
        }
        profitScore += bbProfitPts

        // 4. OBV bearish divergence — distribution detection
        // ──────────────────────────────────────────────────────────────────
        // Two textbook patterns we care about during an uptrend:
        //   STRONG: price at/near a new 5-day high but OBV is BELOW its 5-day avg
        //           → smart money is selling into retail strength (classic top)
        //   SOFT:   OBV is below its 20-day avg even if no new high
        //           → longer-term distribution under the surface
        // Both fire only as warnings; they don't gate BOOK_PROFIT alone.
        val recent5High = if (closes.size >= 5)
            closes.subList(closes.size - 5, closes.size).max()
        else price
        val priceAtNewHigh = price >= recent5High * w.profitObvNewHighProximityPct
        val obvBearishDivergence = priceAtNewHigh && obvCurrent < obv5
        val obvDistributionWeakness = obvCurrent < obv20
        val obvProfitPts = when {
            obvBearishDivergence -> w.profitObvDivergencePts
            obvDistributionWeakness -> w.profitObvWeaknessPts
            else -> 0
        }
        profitScore += obvProfitPts

        // ═══════════════════════════════════════════════════════
        // SUB-SCORE B: CAPITAL PROTECTION (max ~58 pts)
        // Intent: get out before structural damage — trend broken
        // ═══════════════════════════════════════════════════════
        var protectScore = 0

        // 1. Price below SMA200 — primary structural break
        val smaSellPass = sma200Val != null && price < sma200Val
        val smaProtectPts = if (smaSellPass) w.protectSma200Pts else 0
        protectScore += smaProtectPts
        // SMA200 slope declining — bonus (confirms direction, doesn't gate)
        val sma200SlopeBonus = if (smaSellPass && sma200Slope < 0) w.protectSma200SlopeBonus else 0
        protectScore += sma200SlopeBonus

        // 2. ADX bearish — MUTUALLY EXCLUSIVE tiers
        val bearishTrend = currMinusDi > currPlusDi
        val adxSellPts = when {
            currAdx > w.protectAdxStrongThreshold && bearishTrend -> w.protectAdxStrongPts
            currAdx > w.protectAdxWeakThreshold && bearishTrend -> w.protectAdxWeakPts
            else -> 0
        }
        protectScore += adxSellPts

        // 3. OBV declining — distribution confirmed
        val obvSellPass = obvCurrent < obv5 && obvCurrent < obv20
        val obvProtectPts = if (obvSellPass) w.protectObvPts else 0
        protectScore += obvProtectPts

        // 4. MACD zero-cross down — structural momentum confirmation (lower weight here)
        val macdProtectPts = if (macdZeroCrossDn) w.protectMacdZeroCrossDnPts else 0
        protectScore += macdProtectPts

        // ═══════════════════════════════════════════════════════
        // INTENT LABELS — derived from dual scores
        // ═══════════════════════════════════════════════════════
        val profitBookingActive = profitScore >= w.profitActivationThreshold && (sma200Val == null || price > sma200Val)
        val capitalProtectActive = protectScore >= w.protectActivationThreshold && (smaSellPass || adxSellPts > 0)

        val sellIntent = when {
            profitBookingActive && capitalProtectActive -> "STRONG EXIT"
            profitBookingActive -> "BOOK PROFIT"
            capitalProtectActive -> "PROTECT CAPITAL"
            else -> "HOLD"
        }

        // Legacy sellScore for backward compat (max of the two sub-scores)
        val sellScore = maxOf(profitScore, protectScore)
        val sellSignal = when (sellIntent) {
            "STRONG EXIT" -> "STRONG EXIT"
            "BOOK PROFIT" -> "BOOK PROFIT"
            "PROTECT CAPITAL" -> "PROTECT CAPITAL"
            else -> "NO SIGNAL"
        }

        // ── Price dynamics ──
        val p0 = closes.last()
        val p1 = if (n >= 2) closes[n - 2] else p0
        val p2 = if (n >= 3) closes[n - 3] else p1
        val p3 = if (n >= 4) closes[n - 4] else p2
        val roc3 = if (p3 > 0) (p0 - p3) / p3 * 100 else 0.0
        val priceVel = if (p1 > 0) (p0 - p1) / p1 * 100 else 0.0
        val priceVelPrev = if (p2 > 0) (p1 - p2) / p2 * 100 else 0.0
        val priceAccelVal = priceVel - priceVelPrev
        var upDays = 0
        for (idx in 1 until min(6, n)) {
            if (closes[n - idx] > closes[n - idx - 1]) upDays++ else break
        }

        // Lightweight style suitability score. This is deliberately cheap enough
        // to run for every scanned stock on-device; the heavier Hurst/spectral
        // work can come later as an on-demand deep dive.
        val recentReturns = closes.takeLast(127).zipWithNext { a, b ->
            if (a > 0.0) (b - a) / a else 0.0
        }.filter { !it.isNaN() && !it.isInfinite() }
        val returnAutocorr1 = lagAutocorr(recentReturns, 1)
        val srRangeAtrs = if (atrVal > 0.0 && sr.resistance > sr.support) {
            (sr.resistance - sr.support) / atrVal
        } else 0.0
        val atrPct = if (price > 0.0) atrVal / price * 100.0 else 0.0
        val driftBase = when {
            n > 252 -> closes[n - 253]
            n > 126 -> closes[n - 127]
            else -> closes.first()
        }
        val driftPct = if (driftBase > 0.0) (price - driftBase) / driftBase * 100.0 else 0.0
        val cycleDaysForScore = if (macdCycleDays > 0.0) macdCycleDays else minBarsFilterComputed * 3.0

        val rangeScore = scoreRangeAtrs(srRangeAtrs)
        val cycleScore = scoreMacdCycle(cycleDaysForScore, macdHalfCycles)
        val adxRangeScore = scoreRangeAdx(currAdx)
        val reversionScore = scoreReversion(returnAutocorr1)
        val swingVolScore = scoreSwingVol(atrPct)
        val liquidityScore = scoreLiquidity(avgVol20)
        val swingScore = weightedScore(
            rangeScore to 25.0,
            cycleScore to 20.0,
            reversionScore to 20.0,
            adxRangeScore to 20.0,
            swingVolScore to 10.0,
            liquidityScore to 5.0
        )

        val trendStructureScore = (
            (if (sma200Val != null && price > sma200Val) 24.0 else 0.0) +
            (if (ema200Val != null && price > ema200Val) 20.0 else 0.0) +
            (if (sma200Slope > 0.0) 24.0 else 0.0) +
            (if (ema200Slope > 0.0) 20.0 else 0.0) +
            (if (bullishTrend) 12.0 else 0.0)
        ).coerceIn(0.0, 100.0)
        val driftScore = scoreDrift(driftPct)
        val adxTrendScore = scoreTrendAdx(currAdx, bullishTrend)
        val persistenceScore = scorePersistence(returnAutocorr1)
        val longTermVolScore = scoreHoldVol(atrPct)
        val longTermScore = weightedScore(
            trendStructureScore to 35.0,
            driftScore to 25.0,
            adxTrendScore to 20.0,
            persistenceScore to 10.0,
            longTermVolScore to 10.0
        )

        val styleLabel = when {
            swingScore >= 65 && swingScore >= longTermScore + 10 -> "RIDE"
            longTermScore >= 65 && longTermScore >= swingScore + 10 -> "HOLD"
            swingScore < 40 && longTermScore < 40 -> "WEAK"
            else -> "MIXED"
        }
        val cycleText = if (macdCycleDays > 0.0) {
            "%.1fd".format(macdCycleDays)
        } else {
            "~${minBarsFilterComputed * 3}d"
        }
        val styleReason = "range=${"%.1f".format(srRangeAtrs)} ATR, " +
                "cycle=$cycleText, ADX=${"%.1f".format(currAdx)}, " +
                "lag1=${"%.2f".format(returnAutocorr1)}, trend=${"%.1f".format(driftPct)}%"
        val styleScoreReport = listOf(
            "Swing suitability|$swingScore|S/R range ${"%.1f".format(srRangeAtrs)} ATR; MACD cycle $cycleText; ADX ${"%.1f".format(currAdx)}; lag1 ${"%.2f".format(returnAutocorr1)}",
            "Long-term suitability|$longTermScore|Trend structure ${trendStructureScore.toInt()}/100; drift ${"%.1f".format(driftPct)}%; ADX trend ${adxTrendScore.toInt()}/100"
        ).joinToString("\n")

        return StockAnalysis(
            symbol = symbol,
            name = name,
            token = token,
            price = price.round(2),
            sma200 = sma200Val?.round(2),
            sma200Slope = sma200Slope.round(3),
            rsi = rsiVal?.round(2),
            bbUpper = bbUpper?.round(2),
            bbMid = bbMid?.round(2),
            bbLower = bbLower?.round(2),
            macd = cm.round(4),
            macdSignal = macdSignalVal.round(4),
            macdHist = macdHistVal.round(4),
            macdSlope = macdSlope.round(4),
            macdSlopeAngle = macdSlopeAngle,
            macdAccel = macdAccel.round(4),
            macdPctl = macdPctl,
            macdLowPct = macdLowPct,
            macd1yLow = macd1yLow.round(2),
            macdPhase = macdPhase,
            macdCurve = macdCurve,
            smoothMacdCurve = smoothMacdTrend.smooth,
            smoothMacdSlope = smoothMacdTrend.slope.round(4),
            smoothMacdPrevSlope = smoothMacdTrend.prevSlope.round(4),
            smoothMacdAccel = smoothMacdTrend.accel.round(4),
            smoothMacdLowDistPct = smoothMacdTrend.lowDistPct.round(1),
            smoothMacdHook = smoothMacdTrend.hook,
            priceCurve = kalmanTrend.raw,
            kalmanCurve = kalmanTrend.smooth,
            kalmanSlope = kalmanTrend.slope.round(4),
            kalmanTrendPct = kalmanTrend.trendPct.round(2),
            kalmanRegime = kalmanTrend.regime,
            kalmanHorizonDays = kalmanTrend.horizonDays,
            minBarsFilter = minBarsFilterComputed,
            adx = currAdx.round(2),
            plusDi = currPlusDi.round(1),
            minusDi = currMinusDi.round(1),
            obv = obvCurrent.round(0),
            ema21 = ema21Val?.round(2),
            ema21Slope = ema21Slope.round(3),
            ema21PctDiff = ema21PctDiff,
            ema50 = ema50Val?.round(2),
            ema50Slope = ema50Slope.round(3),
            ema200 = ema200Val?.round(2),
            ema200Slope = ema200Slope.round(3),
            avgVol20 = avgVol20.round(0),
            volume = volumes.lastOrNull()?.toDouble()?.round(0) ?: 0.0,
            priceVel = priceVel.round(2),
            priceAccel = priceAccelVal.round(3),
            priceRoc3 = roc3.round(2),
            upDays = upDays,
            support = sr.support,
            resistance = sr.resistance,
            risk = risk,
            reward = reward,
            rrRatio = rrRatio,
            atr = atrVal.round(2),
            riskInAtrs = riskInAtrs,
            positionSize = positionSize,
            capitalNeeded = capitalNeeded,
            potentialProfit = potentialProfit,
            rocPct = rocPct,
            buyScore = buyScore,
            buySignal = buySignal,
            pullbackScore = pullbackScore,
            pullbackSignal = pullbackSignal,
            momentumScore = momentumScore,
            momentumSignal = momentumSignal,
            entryMode = entryMode,
            profitScore = profitScore,
            protectScore = protectScore,
            sellIntent = sellIntent,
            sellScore = sellScore,
            sellSignal = sellSignal,
            goldenBuy = goldenBuy,
            isBuy = pullbackScore >= w.pullbackStrongThreshold ||
                    momentumScore >= w.momentumStrongThreshold ||
                    buyScore >= w.buyStrongThreshold,
            isModerateBuy = pullbackScore >= w.pullbackModerateThreshold ||
                    momentumScore >= w.momentumModerateThreshold ||
                    buyScore >= w.buyModerateThreshold,
            isSell = sellIntent == "STRONG EXIT" || sellIntent == "PROTECT CAPITAL",
            isModerateSell = sellIntent == "BOOK PROFIT",
            obvDivergence = obvBearishDivergence,
            obvWeakness = obvDistributionWeakness,
            buyScoreReport = pullbackScoreReport,
            pullbackScoreReport = pullbackScoreReport,
            momentumScoreReport = momentumScoreReport,
            goldenBuyReport = goldenBuyReport,
            swingScore = swingScore,
            longTermScore = longTermScore,
            styleLabel = styleLabel,
            styleReason = styleReason,
            macdCycleDays = macdCycleDays.round(1),
            returnAutocorr1 = returnAutocorr1.round(3),
            styleScoreReport = styleScoreReport,
            projectedCeiling = waveProjection?.projectedCeiling,
            projectedFloor = waveProjection?.projectedFloor,
            projectedMidpoint = waveProjection?.projectedMidpoint
        )
    }

    private fun weightedScore(vararg parts: Pair<Double, Double>): Int {
        val totalWeight = parts.sumOf { it.second }
        if (totalWeight <= 0.0) return 0
        val total = parts.sumOf { (score, weight) -> score.coerceIn(0.0, 100.0) * weight }
        return Math.round(total / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun scoreRangeAtrs(rangeAtrs: Double): Double = when {
        rangeAtrs.isNaN() || rangeAtrs.isInfinite() || rangeAtrs <= 0.0 -> 20.0
        rangeAtrs < 2.0 -> 30.0
        rangeAtrs < 3.0 -> 55.0
        rangeAtrs <= 10.0 -> 90.0
        rangeAtrs <= 15.0 -> 72.0
        rangeAtrs <= 25.0 -> 50.0
        else -> 30.0
    }

    private fun scoreMacdCycle(cycleDays: Double, halfCycles: Int): Double = when {
        halfCycles < 4 || cycleDays <= 0.0 -> 35.0
        cycleDays < 5.0 -> 40.0
        cycleDays <= 45.0 -> (92.0 - abs(cycleDays - 22.0) * 0.9).coerceIn(65.0, 92.0)
        cycleDays <= 70.0 -> 55.0
        else -> 35.0
    }

    private fun scoreRangeAdx(adx: Double): Double = when {
        adx <= 0.0 -> 35.0
        adx < 10.0 -> 45.0
        adx <= 25.0 -> 90.0
        adx <= 35.0 -> 62.0
        else -> 30.0
    }

    private fun scoreReversion(corr: Double): Double = when {
        corr <= -0.20 -> 95.0
        corr <= -0.10 -> 82.0
        corr <= 0.0 -> 65.0
        corr <= 0.10 -> 45.0
        else -> 25.0
    }

    private fun scorePersistence(corr: Double): Double = when {
        corr >= 0.15 -> 88.0
        corr >= 0.05 -> 72.0
        corr >= -0.05 -> 52.0
        else -> 30.0
    }

    private fun scoreSwingVol(atrPct: Double): Double = when {
        atrPct <= 0.0 -> 20.0
        atrPct < 0.7 -> 35.0
        atrPct <= 6.0 -> 88.0
        atrPct <= 10.0 -> 60.0
        else -> 35.0
    }

    private fun scoreHoldVol(atrPct: Double): Double = when {
        atrPct <= 0.0 -> 40.0
        atrPct < 0.8 -> 70.0
        atrPct <= 4.5 -> 90.0
        atrPct <= 7.0 -> 65.0
        else -> 35.0
    }

    private fun scoreLiquidity(avgVol20: Double): Double = when {
        avgVol20 >= 500_000.0 -> 100.0
        avgVol20 >= 100_000.0 -> 80.0
        avgVol20 >= 50_000.0 -> 60.0
        avgVol20 >= 10_000.0 -> 40.0
        else -> 20.0
    }

    private fun scoreDrift(driftPct: Double): Double = when {
        driftPct >= 30.0 -> 100.0
        driftPct >= 15.0 -> 85.0
        driftPct >= 5.0 -> 65.0
        driftPct >= 0.0 -> 50.0
        driftPct >= -10.0 -> 35.0
        else -> 15.0
    }

    private fun scoreTrendAdx(adx: Double, bullishTrend: Boolean): Double {
        if (!bullishTrend) return if (adx >= 25.0) 20.0 else 35.0
        return when {
            adx >= 35.0 -> 100.0
            adx >= 25.0 -> 85.0
            adx >= 18.0 -> 65.0
            else -> 42.0
        }
    }

    private fun lagAutocorr(values: List<Double>, lag: Int): Double {
        val clean = values.filter { !it.isNaN() && !it.isInfinite() }
        if (lag <= 0 || clean.size <= lag + 2) return 0.0
        val xs = clean.dropLast(lag)
        val ys = clean.drop(lag)
        val meanX = xs.average()
        val meanY = ys.average()
        var cov = 0.0
        var varX = 0.0
        var varY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            cov += dx * dy
            varX += dx * dx
            varY += dy * dy
        }
        val denom = sqrt(varX * varY)
        return if (denom > 0.0) (cov / denom).coerceIn(-1.0, 1.0) else 0.0
    }

    private data class SmoothMacdTrend(
        val smooth: List<Double>,
        val slope: Double,
        val prevSlope: Double,
        val accel: Double,
        val lowDistPct: Double,
        val hook: Boolean
    )

    private data class KalmanTrend(
        val raw: List<Double>,
        val smooth: List<Double>,
        val slope: Double,
        val trendPct: Double,
        val regime: String,
        val horizonDays: Int
    )

    private fun kalmanMacdTrend(values: List<Double>, horizon: Int = 126): SmoothMacdTrend {
        val window = values.takeLast(horizon).filter { !it.isNaN() && !it.isInfinite() }
        if (window.size < 10) {
            val slope = if (window.size >= 2) window.last() - window[window.size - 2] else 0.0
            val prevSlope = if (window.size >= 3) window[window.size - 2] - window[window.size - 3] else slope
            return SmoothMacdTrend(
                smooth = window.takeLast(30).map { it.round(4) },
                slope = slope,
                prevSlope = prevSlope,
                accel = slope - prevSlope,
                lowDistPct = 100.0,
                hook = false
            )
        }

        val diffs = window.zipWithNext { a, b -> b - a }
        val rv = sampleVariance(diffs).coerceAtLeast(1e-8)

        // Same local-linear Kalman structure as the price trend filter, but in
        // raw MACD units because MACD can be negative.
        val r = (rv * 3.0).coerceAtLeast(1e-8)
        val qLevel = (rv * 0.08).coerceAtLeast(1e-10)
        val qSlope = (rv * 0.01).coerceAtLeast(1e-12)

        var xLevel = window.first()
        var xSlope = diffs.take(20).let { if (it.isNotEmpty()) it.average() else 0.0 }
        var p00 = 1.0
        var p01 = 0.0
        var p10 = 0.0
        var p11 = 1.0

        val smooth = ArrayList<Double>(window.size)
        val slopes = ArrayList<Double>(window.size)
        for (z in window) {
            val predLevel = xLevel + xSlope
            val predSlope = xSlope
            val pp00 = p00 + p01 + p10 + p11 + qLevel
            val pp01 = p01 + p11
            val pp10 = p10 + p11
            val pp11 = p11 + qSlope

            val innovation = z - predLevel
            val s = pp00 + r
            val k0 = if (s > 0.0) pp00 / s else 0.0
            val k1 = if (s > 0.0) pp10 / s else 0.0

            xLevel = predLevel + k0 * innovation
            xSlope = predSlope + k1 * innovation
            p00 = (1.0 - k0) * pp00
            p01 = (1.0 - k0) * pp01
            p10 = pp10 - k1 * pp00
            p11 = pp11 - k1 * pp01

            smooth += xLevel
            slopes += xSlope
        }

        val slope = slopes.lastOrNull() ?: 0.0
        val prevSlope = if (slopes.size >= 2) slopes[slopes.size - 2] else slope
        val accel = slope - prevSlope
        val mn = smooth.min()
        val mx = smooth.max()
        val range = (mx - mn).takeIf { it > 0.0 } ?: 1.0
        val lowDistPct = ((smooth.last() - mn) / range * 100.0).coerceIn(0.0, 100.0)
        val eps = sqrt(rv).coerceAtLeast(1e-6) * SMOOTH_MACD_HOOK_EPS_MULT
        val hook = prevSlope < -eps &&
                slope >= 0.0 &&
                accel > eps &&
                lowDistPct <= SMOOTH_MACD_HOOK_LOW_ZONE_MAX

        return SmoothMacdTrend(
            smooth = smooth.takeLast(30).map { it.round(4) },
            slope = slope,
            prevSlope = prevSlope,
            accel = accel,
            lowDistPct = lowDistPct,
            hook = hook
        )
    }

    private fun kalmanPriceTrend(closes: List<Double>, horizon: Int = 252): KalmanTrend {
        val window = closes.takeLast(horizon).filter { it > 0.0 && !it.isNaN() && !it.isInfinite() }
        if (window.size < 10) {
            return KalmanTrend(
                raw = window.map { it.round(2) },
                smooth = window.map { it.round(2) },
                slope = 0.0,
                trendPct = 0.0,
                regime = "UNKNOWN",
                horizonDays = window.size
            )
        }

        val logs = window.map { ln(it) }
        val returns = logs.zipWithNext { a, b -> b - a }
        val rv = sampleVariance(returns).coerceAtLeast(1e-6)

        // Local-linear state model over log price:
        // x = [level, slope]. Adaptive noise keeps steady trends smooth while
        // still letting news-driven moves bend the line within a few sessions.
        val r = (rv * 3.0).coerceIn(1e-6, 0.02)
        val qLevel = (rv * 0.08).coerceIn(1e-7, 0.005)
        val qSlope = (rv * 0.01).coerceIn(1e-8, 0.001)

        var xLevel = logs.first()
        var xSlope = returns.take(20).let { if (it.isNotEmpty()) it.average() else 0.0 }
        var p00 = 1.0
        var p01 = 0.0
        var p10 = 0.0
        var p11 = 1.0

        val smooth = ArrayList<Double>(logs.size)
        for (z in logs) {
            val predLevel = xLevel + xSlope
            val predSlope = xSlope
            val pp00 = p00 + p01 + p10 + p11 + qLevel
            val pp01 = p01 + p11
            val pp10 = p10 + p11
            val pp11 = p11 + qSlope

            val innovation = z - predLevel
            val s = pp00 + r
            val k0 = if (s > 0.0) pp00 / s else 0.0
            val k1 = if (s > 0.0) pp10 / s else 0.0

            xLevel = predLevel + k0 * innovation
            xSlope = predSlope + k1 * innovation
            p00 = (1.0 - k0) * pp00
            p01 = (1.0 - k0) * pp01
            p10 = pp10 - k1 * pp00
            p11 = pp11 - k1 * pp01

            smooth += exp(xLevel)
        }

        val slopePct = (exp(xSlope) - 1.0) * 100.0
        val trendPct = if (smooth.first() > 0.0) (smooth.last() - smooth.first()) / smooth.first() * 100.0 else 0.0
        val regime = when {
            slopePct > 0.15 && trendPct > 5.0 -> "UPTREND"
            slopePct > 0.05 -> "RECOVERING"
            slopePct < -0.15 && trendPct < -5.0 -> "DOWNTREND"
            slopePct < -0.05 -> "ROLLING"
            else -> "FLAT"
        }

        return KalmanTrend(
            raw = window.map { it.round(2) },
            smooth = smooth.map { it.round(2) },
            slope = slopePct,
            trendPct = trendPct,
            regime = regime,
            horizonDays = window.size
        )
    }

    private fun sampleVariance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        var sum = 0.0
        for (v in values) {
            val d = v - mean
            sum += d * d
        }
        return sum / (values.size - 1)
    }

    // Extension for rounding
    private fun Double.round(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
