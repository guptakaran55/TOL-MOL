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
     * percent is the smallest gain worth parking an order for where the round
     * trip costs money: it clears the US currency charge and India's taxes and
     * exchange charges with something left over.
     */
    const val MIN_GAP_PCT = 1.0

    /** German shares on Trading 212 trade in euros with no commission and no
     *  currency charge, so a smaller gain is still a real one. */
    const val MIN_GAP_PCT_FEE_FREE = 0.5

    /**
     * US shares on Trading 212 pay a currency conversion on the buy and on the
     * sale. Tested on Nasdaq-100 2016–21 and 2022–26 (daily bars, 4% stop,
     * 20-day limit, 0.36% round trip): a target under ~5% above the fill did
     * worse than no target in both eras — the charges took most of what it
     * booked. Closer than this, the stop and the algo's own exit do the selling.
     */
    const val MIN_GAP_PCT_US = 5.0

    /** The smallest worthwhile target for a market: "de", "us", "in"… */
    fun minGapFor(base: String): Double = when (base.lowercase()) {
        "de" -> MIN_GAP_PCT_FEE_FREE
        "us" -> MIN_GAP_PCT_US
        else -> MIN_GAP_PCT
    }

    /** Trading days in the month window — the same month the watchlist's "1M" shows. */
    const val MONTH_DAYS = IntradayLevels.MONTH_DAYS

    /** Fewer days than this and it is not a month's range. */
    const val MONTH_MIN_DAYS = 15

    /**
     * "Near its low" — the bottom tenth of that window's own range, or below it.
     * The same zone the watchlist's floor alerts use, so a buy the app called
     * "at its 1-month low" is the buy this rule treats as one.
     */
    const val NEAR_LOW = IntradayLevels.SUPPORT_ZONE

    /**
     * How far short of the month's high the month target sits, as a fraction of
     * the month's range — the mirror of [NEAR_LOW] at the top. A sell parked AT
     * the high needs the stock to make it all the way back; a tenth of the range
     * below it gets sold on the way up, which is the point of booking it.
     */
    const val SHY_OF_HIGH = 0.10

    /** One window's low and high, from bars that had closed before the buy. */
    data class Range(val low: Double, val high: Double) {
        /** 0 at the low, 1 at the high; below 0 under the low. */
        fun pos(price: Double): Double = (price - low) / (high - low)
    }

    /** The week's and the month's ranges before the buy. Either is null when the
     *  bars do not reach back far enough to be that window. */
    data class Ranges(val week: Range?, val month: Range?)

    fun rangesBefore(candles: List<CandleData>, beforeSec: Long, barSec: Long): Ranges {
        val done = candles.filter { it.timestamp + barSec <= beforeSec }
        fun window(days: Int, minDays: Int): Range? {
            val recent = IntradayLevels.recentDays(done, days)
            if (recent.map { it.timestamp / 86_400L }.distinct().size < minDays) return null
            val lo = recent.minOfOrNull { it.low } ?: return null
            val hi = recent.maxOfOrNull { it.high } ?: return null
            return if (lo > 0.0 && hi > lo && hi.isFinite()) Range(lo, hi) else null
        }
        return Ranges(window(DAYS, MIN_DAYS), window(MONTH_DAYS, MONTH_MIN_DAYS))
    }

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

    /** What to rest, or why nothing. [kind] is "week" or "month" — which rule
     *  picked the price — blank when there is none. */
    data class Plan(val price: Double, val why: String = "", val kind: String = "",
                    /** Which level it came from, in words, for the log — "the 190.50 close of 2026-08-25". */
                    val detail: String = "") {
        val ok: Boolean get() = price > 0.0
        /** The rule in words, for the card and the sale's reason. */
        val label: String get() = when (kind) {
            "week" -> "its 1-week high"
            "month" -> "near its 1-month high"
            "monthpeak" -> "just under its last monthly peak"
            "monthzone" -> "its projected monthly resistance zone"
            "manual" -> "your own price"
            else -> "its target"
        }
    }

    /** Which rule version decided a stored target. A record without it was
     *  decided by the old week-high-only rule. */
    const val RULE = "range-v1"

    /**
     * WHERE IN ITS RANGES WAS IT BOUGHT — and so where to sell it.
     *
     *   Near the month's low   → a tenth of the month's range short of its high.
     *   Near the week's low    → the week's high (or the month target, when the
     *                            week's high is too close to be worth it).
     *   Near neither           → the month target as well: bought on strength,
     *                            the week's high is usually right where it was
     *                            bought, and the month's range is the room left.
     *
     * Whatever the rule, a target closer than [minGapPct] to the fill is not set.
     *
     * @param paid         the real fill price
     * @param priceNow     the current price, or null when unknown
     * @param preExisting  held before this rule decided it — see below
     */
    fun plan(r: Ranges, paid: Double, priceNow: Double?, preExisting: Boolean,
             minGapPct: Double = MIN_GAP_PCT): Plan {
        if (!(paid > 0.0)) return Plan(0.0, "the fill price is not known yet")
        val week = r.week; val month = r.month
        if (week == null && month == null)
            return Plan(0.0, "there is not a full week of prices from before it was bought")
        val monthPx = month?.let { floorCent(it.high - SHY_OF_HIGH * (it.high - it.low)) } ?: 0.0
        val weekPx = week?.let { floorCent(it.high) } ?: 0.0
        // In the order they are tried. Without a month of history the week's
        // high is all there is, whatever the position.
        val tries: List<Pair<Double, String>> = when {
            month == null -> listOf(weekPx to "week")
            month.pos(paid) <= NEAR_LOW -> listOf(monthPx to "month")
            week != null && week.pos(paid) <= NEAR_LOW -> listOf(weekPx to "week", monthPx to "month")
            else -> listOf(monthPx to "month")
        }
        val pick = tries.firstOrNull { (px, _) -> px > 0.0 && (px / paid - 1.0) * 100.0 >= minGapPct }
        if (pick == null) {
            val (px, kind) = tries.maxByOrNull { it.first }!!
            val gap = (px / paid - 1.0) * 100.0
            val where = "its target (" + "%.2f".format(px) + ", " + Plan(px, kind = kind).label + ")"
            return Plan(0.0, if (gap < 0) "$where would be below what you paid — no room for one"
                else "$where is only " + "%.1f".format(gap) + "% above what you paid — too close to be worth it")
        }
        val (px, kind) = pick
        // ALREADY PAST IT WHEN THIS RULE DECIDED IT. A position bought after the
        // rule existed cannot be here unless it rose through the target in the
        // minutes before the first check, and then a sale is exactly right. One
        // that was already held is different: it ran past a level nobody had set,
        // and selling it the moment the rule is installed would be a decision
        // made for you rather than by a rule you chose.
        if (preExisting && priceNow != null && priceNow >= px)
            return Plan(0.0, "it was already above its target (" + "%.2f".format(px) +
                ", " + Plan(px, kind = kind).label + ") when this rule was applied — left for you to decide")
        return Plan(px, kind = kind)
    }

    // ── US SHARES: NO SHORT TARGETS ─────────────────────────────────────────
    // Every US round trip pays the currency conversion twice, so a target near
    // the fill books little more than the charges. On the Nasdaq-100 test the
    // week's high kept 0.24% a trade after costs in 2022–26 (the charges took
    // 60% of what it made). With the 5% minimum, the last standing monthly peak
    // kept 0.57% on week-low buys (2016–21: 1.24%) — level with the projected
    // monthly zone (0.56% / 1.26%): the level is the one the chart shows, and it
    // does no worse than the projection for it.

    /** The rule version for US rows — decided by [usPlan], not [plan]. */
    const val RULE_US = "us-monthpeak-v1"

    /** The campaign algo's buffer: a tenth of the 14-day ATR, widened by √21 for a month. */
    const val ZONE_ATR_FRAC = 0.1

    /** How far back a peak still counts — the year the 1Y chart shows. */
    const val PEAK_LOOKBACK_DAYS = 252

    /** A month of sessions: a peak is the highest close of the month before it. */
    const val PEAK_WINDOW_DAYS = 21

    /** A close this recent is not a peak yet — it may be part of a rise still going. */
    const val PEAK_MIN_AGE_DAYS = 5

    /** A monthly peak: the day and its close. */
    data class Peak(val date: java.time.LocalDate, val close: Double)

    /**
     * The monthly peaks the price has not closed above since, newest first —
     * the resistance levels a 1Y chart shows above today's price.
     *
     * A peak is a close at least as high as every close of the month before it.
     * "Not closed above since" keeps only levels still standing: a peak the price
     * later rose through has turned into support, not resistance. Because each
     * standing peak is above everything after it, the newest is the lowest — the
     * first one a rise meets. Closes, not intraday highs, as the line chart draws.
     */
    fun standingPeaks(bars: List<com.signalscope.app.screener.DailyBar>): List<Peak> {
        val c = bars.map { it.close }
        val n = c.size
        val out = ArrayList<Peak>()
        var maxAfter = Double.NEGATIVE_INFINITY      // highest close after index i
        for (i in n - 1 downTo maxOf(PEAK_WINDOW_DAYS, n - PEAK_LOOKBACK_DAYS)) {
            if (i <= n - 1 - PEAK_MIN_AGE_DAYS && c[i] >= maxAfter &&
                (i - PEAK_WINDOW_DAYS until i).all { c[it] <= c[i] })
                out.add(Peak(bars[i].date, c[i]))
            maxAfter = maxOf(maxAfter, c[i])
        }
        return out
    }

    /** A month's buffer under a level: the campaign algo's, a tenth of the ATR × √21. */
    fun monthBuffer(bars: List<com.signalscope.app.screener.DailyBar>): Double? =
        CampaignRules.atr14(bars)?.let { it * ZONE_ATR_FRAC * Math.sqrt(PEAK_WINDOW_DAYS.toDouble()) }

    /**
     * The bottom of the projected monthly resistance zone, from DAILY bars that
     * closed before the buy — the campaign algo's own level ([CampaignRules.levels]):
     * the 50-day average carried a month ahead on its slope, plus two standard
     * deviations of the swings around it, less a month's ATR buffer. The bottom
     * of the zone, because a sell parked at the top needs the stock to make it
     * all the way. Falls back to the plain month's high when it cannot project.
     */
    fun monthZoneBottom(bars: List<com.signalscope.app.screener.DailyBar>): Double? {
        val l = CampaignRules.levels(bars, ZONE_ATR_FRAC, "projected") ?: return null
        val px = floorCent(l.monthRes - l.bufMonth)
        return px.takeIf { it > 0.0 && it.isFinite() }
    }

    /**
     * WHAT A US POSITION RESTS AS ITS TARGET.
     *
     *   An algo's buy   → none. The algos were tested selling on their own rules,
     *                     with no take-profit; a target would cap the winners
     *                     their results depend on.
     *   Your watcher's  → just under the last monthly peak above the buy that the
     *                     price has not closed above since — a month's buffer
     *                     below that close, so it is booked on the way up rather
     *                     than only at the very top. The nearest such peak that is
     *                     at least [minGapPct] above the fill; one closer is a
     *                     level a US sale would not be worth its charges at.
     *                     Above every peak of the past year (a stock at new
     *                     highs), the projected monthly zone stands in for one.
     *
     * [bars] are the daily bars that closed before the buy.
     */
    fun usPlan(watcher: Boolean, bars: List<com.signalscope.app.screener.DailyBar>?, paid: Double,
               priceNow: Double?, preExisting: Boolean, minGapPct: Double = MIN_GAP_PCT_US): Plan {
        if (!watcher) return Plan(0.0, "an algo's US buy rests no target — it sells on its own rules, as it was tested")
        if (!(paid > 0.0)) return Plan(0.0, "the fill price is not known yet")
        val buf = bars?.let { monthBuffer(it) }
            ?: return Plan(0.0, "there is not enough daily history from before the buy to find its monthly levels")
        fun clears(px: Double) = (px / paid - 1.0) * 100.0 >= minGapPct
        val peaks = standingPeaks(bars).filter { it.close > paid }
        val peak = peaks.sortedBy { it.close }.firstOrNull { clears(floorCent(it.close - buf)) }
        val pick: Plan = when {
            peak != null -> Plan(floorCent(peak.close - buf), kind = "monthpeak",
                detail = "the " + "%.2f".format(peak.close) + " close of ${peak.date}, less a month's buffer of " + "%.2f".format(buf))
            peaks.isEmpty() -> monthZoneBottom(bars)?.takeIf { clears(it) }?.let {
                Plan(it, kind = "monthzone", detail = "above every monthly peak of the past year")
            } ?: return Plan(0.0, "it is above every monthly peak of the past year, and its projected monthly zone " +
                "is not " + "%.0f".format(minGapPct) + "% above what you paid — under what a US sale needs to be worth its charges")
            else -> {
                val p = peaks.maxByOrNull { it.close }!!
                val px = floorCent(p.close - buf)
                return Plan(0.0, "its highest monthly peak above the buy (" + "%.2f".format(p.close) + " on ${p.date}) puts the target at " +
                    "%.2f".format(px) + ", only " + "%.1f".format((px / paid - 1.0) * 100.0) + "% above what you paid — under the " +
                    "%.0f".format(minGapPct) + "% a US sale needs to be worth its charges")
            }
        }
        if (preExisting && priceNow != null && priceNow >= pick.price)
            return Plan(0.0, "it was already above its target (" + "%.2f".format(pick.price) + ", " + pick.label +
                ") when this rule was applied — left for you to decide")
        return pick
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
