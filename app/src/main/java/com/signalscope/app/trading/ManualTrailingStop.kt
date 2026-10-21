package com.signalscope.app.trading

import com.signalscope.app.data.ManualWatchStore

// ════════════════════════════════════════════════════════════════════════════
// A PLAIN PERCENTAGE TRAIL, for positions you are watching yourself
// ════════════════════════════════════════════════════════════════════════════
// Set 8% and every hand-managed position carries a floor 8% below the highest
// price it has reached since you bought it. Nothing adaptive, nothing that
// widens on volatility — a number you chose, in a place you can see it.
//
// WHY NOT THE EXISTING TRAILING STOP. There already is one, and it is cleverer:
// peak-anchored, ATR-scaled, tightening as a position matures or plateaus. That
// is the right behaviour for a strategy running unattended. It is the wrong
// behaviour for a position a person is watching, because its level moves for
// reasons the person did not decide and cannot predict, and a floor you cannot
// predict is not a floor you can plan around.
//
// So the two coexist, and where both apply the TIGHTER one wins — see
// [tighterOf]. Never two stops on one holding.
//
// PURE ARITHMETIC, NO I/O, so the part that decides when money moves can be
// tested against known numbers rather than against a market.
// ════════════════════════════════════════════════════════════════════════════

object ManualTrailingStop {

    /**
     * How old a streamed price may be and still count as a TICK for the purpose
     * of FIRING the stop.
     *
     * A stream mark carries no bar size, so the general staleness rule floors it
     * at its 45-minute grace window. That is the right window for valuing a
     * holding and the wrong one for a setting whose name is "live ticks": with
     * the stream down, a forty-four-minute-old print would sell real stock while
     * the screen claimed immediacy.
     *
     * Ten minutes, against a sweep that runs every fifteen: a pass that priced
     * the symbol seconds earlier always qualifies, and a feed that has actually
     * stopped never does. Deliberately NOT applied to the high-water mark — an
     * old print is still a price that genuinely traded, so it may raise the peak;
     * it just may not pull the trigger.
     */
    const val TICK_MAX_AGE_MS = 10 * 60 * 1000L

    /**
     * The new high-water mark.
     *
     * ONLY EVER RISES, and starts at the entry price rather than zero: a position
     * that has never traded above what you paid still has a floor, measured from
     * the cost. Without that a stock that fell from the moment of purchase would
     * carry a stop of zero and be protected by nothing at all.
     */
    fun nextPeak(currentPeak: Double, entryPrice: Double, candidate: Double): Double {
        var p = maxOf(currentPeak, entryPrice.coerceAtLeast(0.0))
        if (candidate > p) p = candidate
        return p
    }

    /** The stop level a peak and a percentage imply. 0 when either is unusable. */
    fun levelFor(peak: Double, pct: Double): Double =
        if (peak <= 0.0 || pct <= 0.0) 0.0 else peak * (1.0 - pct / 100.0)

    /**
     * How far below the high the floor sits, in price, for a trail of [size] in
     * [unit]: a percentage of the high, [size] × the 14-day ATR, or [size] × half
     * the Bollinger width. 0 when the yardstick is unknown — no floor rather than
     * a guessed one.
     */
    fun widthFor(unit: ManualWatchStore.TrailUnit, size: Double, peak: Double,
                 atr: Double, bbHalf: Double): Double = when {
        size <= 0.0 || peak <= 0.0 -> 0.0
        unit == ManualWatchStore.TrailUnit.ATR -> if (atr > 0.0) size * atr else 0.0
        unit == ManualWatchStore.TrailUnit.BB -> if (bbHalf > 0.0) size * bbHalf else 0.0
        else -> peak * size / 100.0
    }

    /** The floor a width below the high gives. 0 when the width is unusable or
     *  would put the floor at or below nothing. */
    fun levelForWidth(peak: Double, width: Double): Double =
        if (peak <= 0.0 || width <= 0.0 || width >= peak) 0.0 else peak - width

    /** The setting a floor was worked out under — a change of either half starts
     *  the ratchet afresh. */
    fun basis(unit: ManualWatchStore.TrailUnit, size: Double): String = "${unit.name}:$size"

    /**
     * A FLOOR MEASURED IN DAILY RANGE MAY NOT FALL. The high only rises, but the
     * ATR and the band breathe: a volatile day widens them and would pull the
     * floor down under a position that has done nothing. So under an unchanged
     * setting the floor keeps the higher of where it was and where it now works
     * out — and an unknown range this pass (0) keeps the last floor. Changing the
     * setting starts afresh, which is the one time the floor should be free to
     * move down: you asked for more room.
     */
    fun ratchet(prevLevel: Double, prevBasis: String, basis: String, level: Double): Double =
        if (prevBasis != basis) level else maxOf(prevLevel, level)

    /** (upper − lower) / 2 of a [period]-day Bollinger band at [k] standard
     *  deviations — k × the population standard deviation of the last closes. */
    fun bbHalfWidth(closes: List<Double>, period: Int = 20, k: Double = 2.0): Double {
        val xs = closes.filter { it > 0.0 }.takeLast(period)
        if (xs.size < period) return 0.0
        val m = xs.average()
        return k * kotlin.math.sqrt(xs.sumOf { (it - m) * (it - m) } / period)
    }

    /**
     * Which price decides whether the stop has been breached.
     *
     * The peak and the trigger can run on different clocks by design — see
     * [ManualWatchStore.TrailSource]. TICK_PEAK deliberately lets the mark rise on
     * live trades while the sell still waits for a completed bar, so a wick lifts
     * the floor but cannot fire it.
     */
    fun triggerPrice(source: ManualWatchStore.TrailSource,
                     livePrice: Double, lastClose: Double): Double = when (source) {
        ManualWatchStore.TrailSource.TICK -> livePrice
        else -> lastClose
    }

    /** Which price is allowed to raise the high-water mark. */
    fun peakCandidate(source: ManualWatchStore.TrailSource,
                      livePrice: Double, lastClose: Double): Double = when (source) {
        ManualWatchStore.TrailSource.BAR_CLOSE -> lastClose
        else -> livePrice
    }

    /**
     * Has it broken?
     *
     * A zero or missing trigger price is NOT a breach. An absent quote is silence,
     * and selling a real position on silence is the worst reading of it — the same
     * mistake that once turned a rate-limited holdings call into "you own nothing".
     */
    fun breached(triggerPrice: Double, level: Double): Boolean =
        level > 0.0 && triggerPrice > 0.0 && triggerPrice <= level

    /**
     * The tighter of two stop levels for a LONG position.
     *
     * Tighter means CLOSER TO PRICE, which for a long is the HIGHER number — the
     * one that gives back least. Reading it as "lower" would quietly pick the
     * looser stop every time while appearing to be careful.
     *
     * A zero means that side has no opinion, and no opinion never wins.
     */
    fun tighterOf(a: Double, b: Double): Double = when {
        a <= 0.0 -> b
        b <= 0.0 -> a
        else -> maxOf(a, b)
    }

    /**
     * WHY THERE IS NO SEPARATE RATCHET.
     *
     * There was one, and nothing called it. The level is recomputed from the peak
     * on every pass, and the peak only ever rises — so at a fixed percentage the
     * level is monotonic for free, and a stored "previous level" would be a second
     * copy of a fact already held.
     *
     * The one case where it genuinely moves down is when YOU WIDEN the percentage
     * — an 8% trail changed to 10% sits further below the same high, so the floor
     * drops from 110.40 to 108.00 on a peak of 120. That should move: it is the
     * user changing their mind, not the stop retreating on its own. A ratchet
     * would have frozen the old, tighter level and quietly ignored the new
     * setting.
     *
     * SAID IN NUMBERS BECAUSE THE WORDS INVERT. A BIGGER percentage is a LOOSER
     * trail and a LOWER floor; this comment said "lower the percentage" and meant
     * the opposite, in a file whose whole value is naming that exact trap.
     */
}
