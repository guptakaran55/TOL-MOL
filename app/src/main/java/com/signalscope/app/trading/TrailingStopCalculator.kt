package com.signalscope.app.trading

import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Profit-adaptive trailing stop-loss for swing positions (7–45 day horizon).
 *
 * Final formula (per design discussion, synthesized from user + external
 * critique about ATR/give-back independence and chandelier anchoring):
 *
 *     gainFromPeak = (peakPrice - entryPrice) / entryPrice
 *
 *     atrMult =  2.0 if gain ≤ 10%
 *                1.5 if gain ≤ 20%
 *                1.2 if gain ≤ 30%
 *                1.0 otherwise
 *
 *     // Time decay: plateaued positions become riskier
 *     if daysSincePeak ≥ 10: atrMult *= 0.8
 *     if daysSincePeak ≥ 20: atrMult *= 0.6
 *
 *     chandelierStop  = peakPrice - atrMult × atr        // anchored to peak
 *     supportFloor    = supportLevel                     // algorithmic swing low (or 0 if N/A)
 *
 *     newSL = max(
 *         yesterdaySL,                                    // monotonic trail — never lower
 *         max(supportFloor, chandelierStop)              // whichever is closer to LTP
 *     )
 *
 *     // Final clamp: must stay strictly below LTP (Kite OCO rule) and within ±25% (Kite limit)
 *     newSL = newSL.coerceIn(ltp × 0.75, ltp × 0.999)
 *
 * Notes:
 *   - Chandelier anchors to peakPrice, NOT entry. That's the critical
 *     correction over the earlier design: plateau-and-bleed scenarios
 *     (stock hits +25%, drifts sideways for 3 weeks, then slow-fades)
 *     get cleanly caught by peak-anchored chandelier. Entry-anchored
 *     give-back would have kept the stop far below.
 *   - We still take max(support, chandelier). Support level beats
 *     chandelier on the way UP (tighter protection). Chandelier beats
 *     support on the way DOWN (chandelier falls with ATR compression).
 *   - No separate "give-back" layer. ATR multiplier compression covers
 *     that role — single knob, no dead code.
 */
object TrailingStopCalculator {

    data class Input(
        val symbol: String,
        val ltp: Double,
        val entryPrice: Double,
        val peakPrice: Double,
        val peakEpoch: Long,
        val atr: Double,
        val supportLevel: Double,    // from StockAnalysis; 0 if unavailable
        val yesterdaySL: Double,     // from GTT or PositionTracker.lastSL; 0 if no existing SL
        val fixedAtrMultiple: Double = 0.0,
        val timeDecay10dFactor: Double = 0.80,
        val timeDecay20dFactor: Double = 0.60,
        val nowMs: Long = System.currentTimeMillis()
    )

    data class Output(
        val newSL: Double,
        val atrMult: Double,
        val gainFromPeakPct: Double,   // gain at peak, used for debugging
        val daysSincePeak: Long,
        val chandelierStop: Double,
        val supportFloor: Double,
        val wasClampedToLtp: Boolean,  // true if we had to push SL closer to LTP to satisfy Kite
        val reason: String             // human-readable explanation for the log
    )

    fun compute(input: Input): Output {
        val ltp = input.ltp
        val atr = if (input.atr > 0) input.atr else ltp * 0.02   // 2% fallback ATR
        val entry = input.entryPrice
        val peak = max(input.peakPrice, ltp)   // peak must never be below today's LTP

        // 1. Base ATR multiplier from gain-at-peak.
        // Tiers widened at the bottom end per backtest finding (v1→v2):
        // fresh entries with < 5% gain need 3× ATR room to survive first
        // normal pullbacks without triggering a premature stop-out.
        val gain = if (entry > 0) (peak - entry) / entry else 0.0
        var atrMult = if (input.fixedAtrMultiple > 0.0) {
            input.fixedAtrMultiple
        } else {
            when {
                gain <= 0.05 -> 3.0
                gain <= 0.10 -> 2.5
                gain <= 0.20 -> 2.0
                gain <= 0.30 -> 1.5
                else         -> 1.0
            }
        }

        // 2. Time decay — plateaued winners tighten
        val daysSincePeak = (input.nowMs - input.peakEpoch)
            .let { TimeUnit.MILLISECONDS.toDays(it).coerceAtLeast(0) }
        if (daysSincePeak >= 20) atrMult *= input.timeDecay20dFactor.coerceIn(0.10, 1.00)
        else if (daysSincePeak >= 10) atrMult *= input.timeDecay10dFactor.coerceIn(0.10, 1.00)
        atrMult = atrMult.coerceIn(0.20, 8.00)

        // 3. Chandelier anchored to peak, NOT entry
        val chandelierStop = peak - atrMult * atr

        // 4. Support floor — use it if it makes sense (below LTP, above entry-loss bound)
        val supportFloor = if (input.supportLevel > 0 && input.supportLevel < ltp)
            input.supportLevel else 0.0

        // 5. Take the tighter (higher) of support vs chandelier
        var candidate = max(supportFloor, chandelierStop)

        // 6. Monotonic trail — never lower an existing SL
        if (input.yesterdaySL > 0) candidate = max(candidate, input.yesterdaySL)

        // 7. Kite API rules: within -25% of LTP, strictly below LTP (0.1% pad)
        val minAllowed = ltp * 0.75
        val maxAllowed = ltp * 0.999
        val clamped = candidate.coerceIn(minAllowed, maxAllowed)
        val wasClamped = (clamped != candidate)

        // 8. Round to 0.05 tick
        val rounded = Math.round(clamped * 20.0) / 20.0

        val reason = buildString {
            append("gain@peak=${(gain * 100).format(1)}%")
            append(" · atrMult=${atrMult.format(2)}")
            append(" · daysSincePeak=$daysSincePeak")
            append(" · chandelier=${chandelierStop.format(2)}")
            if (supportFloor > 0) append(" · support=${supportFloor.format(2)}")
            if (input.yesterdaySL > 0) append(" · prevSL=${input.yesterdaySL.format(2)}")
            if (wasClamped) append(" · [clamped to Kite bounds]")
            append(" → SL=${rounded.format(2)}")
        }

        return Output(
            newSL = rounded,
            atrMult = atrMult,
            gainFromPeakPct = gain * 100,
            daysSincePeak = daysSincePeak,
            chandelierStop = chandelierStop,
            supportFloor = supportFloor,
            wasClampedToLtp = wasClamped,
            reason = reason
        )
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
