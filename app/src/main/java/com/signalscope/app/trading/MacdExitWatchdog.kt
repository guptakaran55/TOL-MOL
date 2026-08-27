package com.signalscope.app.trading

/**
 * Momentum-death exit signal for swing positions — v2, histogram-based with
 * an adaptive per-stock time filter.
 *
 * Why this replaces the v1 slope-based watchdog:
 *   v1 used (macdLine − macdLine[3 days ago]) = a noisy 1-day numerical slope.
 *       Fired falsely on consolidation dips; hardcoded 2-day filter was too
 *       tight for slow large-caps and too loose for fast midcaps.
 *   v2 uses the HISTOGRAM (= MACD − Signal), already EMA-9 smoothed. Going
 *       below zero is the classic bearish MACD/Signal crossover. The time
 *       filter adapts to each stock's natural oscillation period (2–20 days).
 *
 *     exitSignal = (
 *         currentHist < peakHistValue × 0.5   // momentum halved from its peak
 *         AND currentHist < 0                  // histogram is actually negative
 *         AND daysHistNegative >= minBarsFilter
 *                                              // sustained — length adapts per stock
 *         AND unrealizedGainPct > 3%           // don't exit into tiny/zero profit
 *     )
 *
 * How `minBarsFilter` is derived (done in StockAnalyzer, passed through
 * StockAnalysis.minBarsFilter):
 *   amplitude  = max(hist_6mo) − min(hist_6mo)
 *   threshold  = amplitude × 0.30
 *   halfCycles = count of peaks above +threshold AND troughs below −threshold
 *   naturalPeriod = 126 / (halfCycles / 2)
 *   minBarsFilter = (naturalPeriod / 3).coerceIn(2, 20)
 *
 *   → fast-cycling midcap  (period ~7d)  → minBarsFilter ≈ 3
 *   → medium               (period ~18d) → minBarsFilter ≈ 6
 *   → slow large-cap       (period ~45d) → minBarsFilter ≈ 15
 *
 * IMPORTANT: `histNegativeSinceEpoch` must be RESET whenever histogram goes
 * non-negative. PositionTracker owns that state. Don't treat "15 days ago it
 * was briefly negative" as a sustained streak.
 */
object MacdExitWatchdog {

    data class Input(
        val symbol: String,
        val currentHist: Double,
        val peakHistValue: Double,
        val histNegativeSinceEpoch: Long,  // 0 if histogram currently ≥ 0
        val unrealizedGainPct: Double,
        val minBarsFilter: Int,            // adaptive from StockAnalysis
        val nowMs: Long = System.currentTimeMillis()
    )

    data class Output(
        val shouldExit: Boolean,
        val reason: String
    )

    private const val HIST_DECAY_RATIO = 0.5
    private const val GAIN_GATE_PCT = 3.0   // only fire on winners with >3% gain
    private const val MS_PER_DAY = 24L * 60L * 60L * 1000L

    fun evaluate(input: Input): Output {
        val reasons = mutableListOf<String>()

        if (input.unrealizedGainPct <= GAIN_GATE_PCT) {
            return Output(
                false,
                "gain ${input.unrealizedGainPct.format(2)}% ≤ ${GAIN_GATE_PCT}% — MACD watchdog only fires on meaningful winners"
            )
        }
        if (input.peakHistValue <= 0.0) {
            return Output(false, "no positive peak histogram observed yet (peak=${input.peakHistValue.format(4)})")
        }

        val decayThreshold = input.peakHistValue * HIST_DECAY_RATIO
        val histDecayed = input.currentHist < decayThreshold
        if (!histDecayed)
            reasons.add("hist ${input.currentHist.format(4)} ≥ ${decayThreshold.format(4)} (50% of peak)")

        val histNegative = input.currentHist < 0.0
        if (!histNegative) reasons.add("hist ${input.currentHist.format(4)} ≥ 0 (no MACD/Signal cross yet)")

        val daysNegative = if (input.histNegativeSinceEpoch > 0L)
            ((input.nowMs - input.histNegativeSinceEpoch) / MS_PER_DAY).coerceAtLeast(0)
        else 0
        val sustained = daysNegative >= input.minBarsFilter
        if (!sustained)
            reasons.add("hist negative only $daysNegative day(s) — need ≥${input.minBarsFilter} (adaptive filter)")

        val shouldExit = histDecayed && histNegative && sustained
        val verdict = if (shouldExit)
            "FIRE: hist ${input.currentHist.format(4)} < ${decayThreshold.format(4)} " +
                    "(50% of peak ${input.peakHistValue.format(4)}) · $daysNegative d negative " +
                    "≥ ${input.minBarsFilter} d filter · gain ${input.unrealizedGainPct.format(2)}%"
        else
            "HOLD: " + reasons.joinToString(" · ")

        return Output(shouldExit, verdict)
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
