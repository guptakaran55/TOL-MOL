package com.signalscope.app.trading

// ════════════════════════════════════════════════════════════════════════════
// WHEN A BAR IS REALLY FINISHED — NOT BY THE CLOCK, BY THE DATA
// ════════════════════════════════════════════════════════════════════════════
// A bar used to count as closed one minute after its end on the wall clock. That
// is right for a feed that is live, and wrong for one that is not: Yahoo's Xetra
// prices run fifteen minutes behind (measured on the device), so at 10:02 the
// "closed" 09:00–10:00 bar had prices only up to about 09:47. The engine judged
// that partial bar, stamped it done, and on the next pass judged the complete one
// — which can say something different.
//
// BOTH DIRECTIONS COST MONEY, and both were seen or are possible:
//
//   • A SIGNAL THAT APPEARS LATE. Aixtron's entry on 28 September existed only
//     on the complete bar. By the time it appeared the bar was already marked
//     done, so the rule against buying a stale signal kept it on paper — the
//     strategy held a position real money never took, on the same bar Drägerwerk
//     was bought on.
//   • A SIGNAL THAT VANISHES. The mirror image: an entry visible on the early
//     prices and gone on the final ones places a real order for a signal that,
//     on the numbers the strategy is backtested on, never happened.
//
// So a bar is complete only once the FEED has had time to deliver it: its end,
// plus the feed's delay, plus the usual one-minute grace. For Germany that is
// sixteen minutes, which the quarter-hourly pass (at :01:30, :16:30, ...) meets
// with thirty seconds to spare — the decision moves one pass later, not two.
//
// Only Germany, because that is the delay that was measured. No other market's
// feed has been shown to lag this way; one that is found to belongs here too.
// ════════════════════════════════════════════════════════════════════════════

object FeedDelay {

    /** How far Yahoo's Xetra prices trail the exchange. */
    const val XETRA_SEC = 15 * 60L

    /** The feed delay for a namespace's market, in seconds. */
    fun secFor(ns: String): Long = if (Spaces.baseOf(ns.lowercase()) == "de") XETRA_SEC else 0L

    /**
     * Has the feed delivered this whole bar?
     *
     * [barStartSec] + [periodSec] is when the bar ends on the exchange's clock;
     * the data for its final moments arrives [delaySec] later, and [graceSec] is
     * the existing allowance for a provider that publishes a little late.
     */
    fun isComplete(barStartSec: Long, periodSec: Long, nowSec: Long,
                   graceSec: Long, delaySec: Long): Boolean =
        barStartSec + periodSec + graceSec + delaySec <= nowSec
}
