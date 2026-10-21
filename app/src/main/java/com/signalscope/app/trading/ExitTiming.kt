package com.signalscope.app.trading

/**
 * WHEN A WAITING SALE ACTUALLY GOES — the rule, with no I/O.
 *
 * A sale the strategy decided after the close (or that found no buyer) waits for
 * the market. At the open it used to go on the first pass, whatever the price
 * was doing. Now it mirrors the queued-buy rule: a stock that opens RISING is
 * left to rise, and sold once the rise stops — while the safety stop at the
 * broker ([T212ExitGuards]) stays underneath it the whole time.
 *
 * Sell now when ANY of these holds:
 *  • the price is already at or under the position's stop — a stop sale is a
 *    stop, and stop sales never wait;
 *  • there is no live price, or no 15-minute bars — nothing to wait ON, and a
 *    sale is never held back blind;
 *  • the last finished 15-minute bar of today closed LOWER than it opened;
 *  • the price has come back a third of a typical day's move from today's high —
 *    so a rise that reverses inside one quarter hour gives back only that much;
 *  • it has been held back 90 minutes already — a sideways morning must not
 *    drag the sale into the afternoon.
 * Otherwise hold, and ask again at the next pass. The first look is 16 minutes
 * after the bell, when the day's first 15-minute bar can have finished.
 */
object ExitTiming {
    const val SETTLE_MIN = 16L
    const val MAX_WAIT_MS = 90 * 60_000L
    const val PULLBACK_ATR = 1.0 / 3.0

    enum class Verdict { SELL, HOLD }
    data class Decision(val verdict: Verdict, val why: String) {
        val sell: Boolean get() = verdict == Verdict.SELL
    }

    /**
     * @param belowStop    the price is at or under the position's own stop
     * @param price        live price; null when it could not be read
     * @param minutesSinceOpen minutes since today's regular session opened
     * @param barsRead     the 15-minute bars could be fetched at all
     * @param lastBarUp    today's last finished 15-minute bar closed above its
     *                     open; null when none of today's has finished yet
     * @param highSinceOpen the highest price seen since the open (0 = unknown)
     * @param atr          a typical day's move (0 = unknown)
     * @param waitedMs     how long this sale has already been held back
     */
    fun decide(belowStop: Boolean, price: Double?, minutesSinceOpen: Long, barsRead: Boolean,
               lastBarUp: Boolean?, highSinceOpen: Double, atr: Double, waitedMs: Long): Decision {
        if (belowStop) return Decision(Verdict.SELL, "under its stop — stop sales never wait")
        if (price == null || !(price > 0.0)) return Decision(Verdict.SELL, "no live price — not held back blind")
        if (waitedMs >= MAX_WAIT_MS) return Decision(Verdict.SELL, "held back 90 minutes — selling now")
        if (minutesSinceOpen < SETTLE_MIN)
            return Decision(Verdict.HOLD, "waiting for the day's first 15-minute bar")
        if (!barsRead) return Decision(Verdict.SELL, "no 15-minute prices — not held back blind")
        if (lastBarUp == null) return Decision(Verdict.HOLD, "waiting for today's first finished 15-minute bar")
        if (!lastBarUp) return Decision(Verdict.SELL, "the last 15-minute bar closed lower")
        if (atr > 0.0 && highSinceOpen > 0.0 && price <= highSinceOpen - PULLBACK_ATR * atr)
            return Decision(Verdict.SELL, "came back a third of a day's move from today's high")
        return Decision(Verdict.HOLD, "still rising")
    }
}
