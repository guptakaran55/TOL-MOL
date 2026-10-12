package com.signalscope.app.trading

import com.signalscope.app.data.CandleData

// ════════════════════════════════════════════════════════════════════════════
// THE 1-WEEK HIGH AS A TARGET, for stock bought through Trading 212
// ════════════════════════════════════════════════════════════════════════════
// Buy near the bottom of the recent range, sell at the top of it: the target is
// the highest price of the five trading days BEFORE the buy, fixed at that
// moment. It rests at Trading 212 as an ordinary limit sell, so it fills while
// the phone is off and catches a spike between two checks — at that price or
// better, never at whatever the market happens to be fifteen minutes later.
//
// ONLY ONE SELL CAN REST. Trading 212 reserves shares for every waiting sell,
// so a target for the whole position leaves no room for a resting stop. The
// stop stays where it already is — watched by the app — and every sale the app
// makes clears the waiting target first. Nothing about the stop changes.
//
// FIXED AT THE BUY, NOT ROLLED. The week's high moves every day as old sessions
// drop out and new ones arrive. A target that followed it would chase price up
// on a winner (a new high IS the week's high, so it could never be reached) and
// sag toward the fill on a loser. The range the trade was bought inside is the
// one it is meant to be sold at the top of.
//
// PURE, NO I/O: the decisions that place and cancel real orders are checked
// against known numbers rather than against a market.
// ════════════════════════════════════════════════════════════════════════════

object WeekTarget {

    /** Trading days in the window — the same week the watchlist's "1W" shows. */
    const val DAYS = IntradayLevels.WEEK_DAYS

    /** Fewer days than this and it is not a week's range. */
    const val MIN_DAYS = 4

    /**
     * A target closer than this to what you paid is not set at all.
     *
     * Bought on a breakout, the stock is already AT its week's high, so the
     * "target" would be the fill itself — a sale for nothing, minus costs. One
     * percent is the smallest gain worth parking an order for: it clears the US
     * currency charge on the round trip and is no tighter than the algos' stop.
     */
    const val MIN_GAP_PCT = 1.0

    /** The highest high of the last [DAYS] trading days of bars that had fully
     *  CLOSED by [beforeSec]. Null when the bars do not reach back [MIN_DAYS]. */
    fun highBefore(candles: List<CandleData>, beforeSec: Long, barSec: Long): Double? {
        val done = candles.filter { it.timestamp + barSec <= beforeSec }
        val recent = IntradayLevels.recentDays(done, DAYS)
        val days = recent.map { it.timestamp / 86_400L }.distinct().size
        if (days < MIN_DAYS) return null
        return recent.maxOfOrNull { it.high }?.takeIf { it > 0.0 && it.isFinite() }
    }

    /** Down to the cent, never up — a target a cent above the real high is one
     *  the market may never print. */
    fun floorCent(x: Double): Double = Math.floor(x * 100.0 + 1e-9) / 100.0

    /** What to rest, or why nothing. */
    data class Plan(val price: Double, val why: String = "") {
        val ok: Boolean get() = price > 0.0
    }

    /**
     * @param weekHigh     the high before the buy; 0 = the bars did not reach back
     * @param paid         the real fill price
     * @param priceNow     the current price, or null when unknown
     * @param preExisting  bought before targets existed — see below
     */
    fun plan(weekHigh: Double, paid: Double, priceNow: Double?, preExisting: Boolean): Plan {
        if (!(paid > 0.0)) return Plan(0.0, "the fill price is not known yet")
        if (!(weekHigh > 0.0))
            return Plan(0.0, "there is not a full week of prices from before it was bought")
        val px = floorCent(weekHigh)
        val gap = (px / paid - 1.0) * 100.0
        if (gap < MIN_GAP_PCT)
            return Plan(0.0, "its 1-week high (" + "%.2f".format(px) + ") was only " +
                "%.1f".format(gap) + "% above what you paid — too close to be worth a target")
        // ALREADY PAST IT WHEN TARGETS WERE SWITCHED ON. A position bought after
        // this existed cannot be here unless it rose through the target in the
        // minutes before the first check, and then a sale is exactly right. One
        // that was already held is different: it ran past a level nobody had set,
        // and selling it the moment the feature is installed would be a decision
        // made for you rather than by a rule you chose.
        if (preExisting && priceNow != null && priceNow >= px)
            return Plan(0.0, "it was already above its 1-week high (" + "%.2f".format(px) +
                ") when targets were switched on — left for you to decide")
        return Plan(px)
    }

    /** What to do with the resting order on this pass. */
    enum class Step {
        /** Nothing is resting: put the order in. */
        PLACE,
        /** Resting as it should be. */
        KEEP,
        /** Resting for the wrong number of shares: cancel and put it back. */
        REPLACE,
        /** The broker's history shows it executed: the target sold the position. */
        FILLED,
        /** Gone, and the shares are still there: cancelled. Put it back. */
        FORGET,
        /** Gone, the shares are gone too, and the history has not caught up yet. */
        WAIT,
        /** Gone for hours with the shares gone and no fill ever reported. */
        ABANDON
    }

    /** How long a vanished order whose shares have also gone is waited for in
     *  the history before the app stops guessing and says so. */
    const val GIVE_UP_MS = 6 * 60 * 60_000L

    /**
     * How long a vanished order is given to appear in the history before it is
     * taken as cancelled, EVEN with the shares apparently still there.
     *
     * "Still there" is measured on the pooled holding, and shares you bought
     * yourself in the broker's app sit in that pool too: a target that just sold
     * its own shares can leave the count looking untouched. Trading 212 publishes
     * an execution to its history a few minutes after it happens, so a short
     * wait is what tells "cancelled" from "sold, not reported yet". Putting the
     * order back too early would reserve shares that belong to nobody's target.
     */
    const val FORGET_AFTER_MS = 20 * 60_000L

    /**
     * @param orderId       the order this position's target rests as, 0 = none
     * @param wantQty       the shares this position owns
     * @param restingQty    the open orders right now, id → shares
     * @param filledIds     order ids the history reports as executed
     * @param sharesThere   true = the account still holds this position's shares
     *                      (after everyone else's claims); false = it does not;
     *                      null = could not tell
     * @param goneSinceMs   when the order was first seen missing, 0 = not yet
     */
    fun step(orderId: Long, wantQty: Double, restingQty: Map<Long, Double>, filledIds: Set<Long>,
             sharesThere: Boolean?, goneSinceMs: Long, nowMs: Long): Step {
        if (orderId <= 0L) return Step.PLACE
        restingQty[orderId]?.let { q ->
            // Shares change: the watcher trims a hand-bought row to "your share
            // only" when an algo turns out to hold the same name. A target for more
            // than this position owns would be selling someone else's shares.
            val tol = maxOf(1e-4, wantQty * 0.001)
            return if (kotlin.math.abs(q - wantQty) > tol) Step.REPLACE else Step.KEEP
        }
        if (orderId in filledIds) return Step.FILLED
        return when (sharesThere) {
            // Gone without executing, shares still held: cancelled — by you in the
            // broker's app, or swept by another sale of the same name. Put back,
            // once the history has had time to show a fill if there was one.
            true -> if (goneSinceMs > 0L && nowMs - goneSinceMs >= FORGET_AFTER_MS) Step.FORGET else Step.WAIT
            // Shares gone too: almost certainly a fill the history has not
            // published yet. Wait for it rather than invent a price.
            false -> if (goneSinceMs > 0L && nowMs - goneSinceMs > GIVE_UP_MS) Step.ABANDON else Step.WAIT
            null -> Step.WAIT
        }
    }

    /** The share counts a quantity is tried at: exact first, then coarser, never
     *  rounded up — the same walk the sell ladder uses, for instruments that
     *  refuse long decimals. */
    fun qtyAttempts(qty: Double): List<Double> {
        val out = LinkedHashSet<Double>()
        if (qty > 0.0) out.add(qty)
        for (dp in intArrayOf(5, 3, 2, 0)) {
            val f = Math.pow(10.0, dp.toDouble())
            val q = Math.floor(qty * f) / f
            if (q > 0.0) out.add(q)
        }
        return out.toList()
    }
}
