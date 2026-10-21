package com.signalscope.app.trading

// ════════════════════════════════════════════════════════════════════════════
// A PLAIN PERCENTAGE TARGET, for positions you are watching yourself
// ════════════════════════════════════════════════════════════════════════════
// The other half of [ManualTrailingStop] and deliberately its mirror image: one
// number you choose, a level you can predict, applied only to this book.
//
// MEASURED FROM WHAT YOU PAID, not from the strategy's entry and not from the
// high. A trail is about giving back as little as possible of a move that has
// already happened, so it hangs off the peak. A target is about the return YOU
// decided was enough, so it hangs off your cost — and a target measured from an
// entry price nobody paid is a return nobody gets.
//
// WHY IT IS NOT JUST THE CONFIG'S TAKE PROFIT. The config has one, and it acts
// on the engine's entry, on the engine's candles, and only while the engine is
// actually holding the position. Hand-bought stock frequently fails all three:
// the replay never opened the trade, so nothing in the config is measuring
// anything. This is the number that applies regardless.
//
// PURE ARITHMETIC, NO I/O, for the same reason as the trail — the part that
// decides when real money moves is checked against known figures rather than
// against a market.
// ════════════════════════════════════════════════════════════════════════════

object ManualTakeProfit {

    /** The price a percentage gain on your fill implies. 0 when either is unusable. */
    fun targetFor(entryPrice: Double, pct: Double): Double =
        if (entryPrice <= 0.0 || pct <= 0.0) 0.0 else entryPrice * (1.0 + pct / 100.0)

    /**
     * Has it got there?
     *
     * A zero or missing price is NOT an arrival, exactly as it is not a breach on
     * the stop side. Silence is silence in both directions, and a feed that has
     * stopped must not be able to sell a position by either rule.
     */
    fun reached(triggerPrice: Double, target: Double): Boolean =
        target > 0.0 && triggerPrice > 0.0 && triggerPrice >= target

    /**
     * The tighter of two SELL targets.
     *
     * The exact inversion of [ManualTrailingStop.tighterOf], and the reason both
     * exist as named functions rather than a bare max/min at the call site.
     * Tighter always means CLOSER TO PRICE; for a floor under a long that is the
     * higher number, for a ceiling above it that is the LOWER one. Writing
     * whichever felt right at the time is how a "tighter" target ends up further
     * away than the one it replaced.
     *
     * A zero means that side has no opinion, and no opinion never wins.
     */
    fun tighterOf(a: Double, b: Double): Double = when {
        a <= 0.0 -> b
        b <= 0.0 -> a
        else -> minOf(a, b)
    }

    /** What the hand-set levels say about a position on this pass. */
    enum class Verdict { HOLD, STOP, TARGET }

    /**
     * WHICH ONE FIRES WHEN BOTH DO, decided once and in one place.
     *
     * They can genuinely both be true at the same moment, which is not obvious:
     * the floor hangs off the PEAK and the target off your COST, so a position
     * that ran a long way and then fell back can sit below a trailed floor while
     * still above the target it passed on the way up. That happens whenever a
     * target is switched on after the move rather than before it.
     *
     * THE STOP WINS, because the two answers differ in what they claim about the
     * present. The target says "this reached the gain you asked for"; by the time
     * the trail has also fired, that is a statement about the past — price has
     * since fallen far enough from the high to trip protection. Reporting a
     * trade that gave back most of its move as a target being met would write the
     * wrong reason into a book whose whole purpose is telling you which rules are
     * working.
     *
     * The money is identical either way. Only the reason differs, and the reason
     * is the part that is read afterwards.
     */
    fun decide(triggerPrice: Double, floor: Double, ceiling: Double): Verdict = when {
        ManualTrailingStop.breached(triggerPrice, floor) -> Verdict.STOP
        reached(triggerPrice, ceiling) -> Verdict.TARGET
        else -> Verdict.HOLD
    }
}
