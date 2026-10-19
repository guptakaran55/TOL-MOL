package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.MacdGateStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client

/**
 * "I SOLD THAT ONE MYSELF."
 *
 * The account is shared between the algos and a human, and sometimes the human
 * wins the argument — an extraordinary move, a piece of news, a decision to book
 * profit rather than wait for the strategy's exit. That is a legitimate thing to
 * do, and until now the app had no way to be told about it: the paper trade stayed
 * open forever against shares that no longer existed, the seat stayed occupied, and
 * the position audit screamed MISSING at every check.
 *
 * WHY THIS IS USER-CONFIRMED AND NOT AUTOMATIC. A missing share has several
 * possible causes — a manual sale, a partially rejected exit, a broker correction,
 * a stale read. They are indistinguishable from outside, and the response to each
 * is different. Guessing "you must have sold it" and then deleting a paper trade
 * and freeing a seat on that guess would be the app quietly rewriting its own
 * history. So this runs from an explicit tap — or, since 14 Oct, from
 * [T212HandSales.sweep], and only when the broker has shown NONE of the shares on
 * two reads at least half an hour apart: not a stale read, not a partial
 * rejection, but a position that is simply gone. Partly missing positions are
 * still left to you.
 *
 * WHAT IT DOES, and the reasoning for each:
 *
 *  1. PAPER TRADE REMOVED, P&L DISCARDED. The strategy did not choose that exit,
 *     so it gets neither the credit nor the blame. Booking your exit into the
 *     algo's record would corrupt the one number the whole app exists to measure.
 *  2. SEAT FREED. Capital stays with the seat; only the name is released, so the
 *     gate can rotate someone in on its next evaluation. Identical to an eject.
 *  3. LIVE ROW MARKED, NOT DELETED. The cash was real and belongs in the account's
 *     books — just never in the algo's performance or its execution measurement.
 *     Excluded from slippage samples because the exit was your thumb, not the
 *     ladder: including it would measure the wrong thing.
 *  4. NO EXIT ATTEMPTED LATER. The mirror skips a manually-closed row, so the
 *     strategy's eventual sell signal cannot fire an order at shares that are gone.
 *
 * The algo is otherwise untouched — same universe, same rules, and it may re-enter
 * that name whenever its own logic says so. A one-off human decision should not
 * quietly reshape how the strategy trades afterwards.
 */
object ManualIntervention {
    private const val TAG = "ManualIntervention"

    data class Result(
        val ok: Boolean,
        val error: String = "",
        val symbol: String = "",
        val paperRemoved: Boolean = false,
        val discardedPnlPct: Double = 0.0,   // what the paper trade was showing when dropped
        val seatFreed: Boolean = false,
        val liveMarked: Boolean = false,
        val matchedFill: Boolean = false,    // did we find the actual manual sell in history?
        val exitPrice: Double = 0.0,
        val exitQty: Double = 0.0,
        val realPnl: Double = 0.0,           // real cash, only when the fill was found
        val seatTo: String? = null           // the stock that took the freed seat
    )

    fun markSoldByHand(ctx: Context, ns: String, symbol: String): Result {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return Result(false, "No symbol given.")

        // ── 1. What the paper side is about to lose, captured BEFORE removal so the
        // user is told what was discarded rather than it vanishing silently.
        val lab = StrategyLabStore.load(ctx, ns)
        val paperOpen = lab.trades.lastOrNull { it.symbol.equals(sym, true) && it.status == "OPEN" }
        val discarded = paperOpen?.pctReturn ?: 0.0

        // ── 2. Try to price it from the broker's own record. A declared sale with a
        // real fill behind it is worth far more than a declared sale alone: it makes
        // the manual P&L a fact instead of an assertion. Best-effort — an older sale
        // will have fallen off the 50-order history window, and that is fine.
        var matched = false; var exitPx = 0.0; var exitQty = 0.0; var realPnl = 0.0
        val live = T212LiveStore.load(ctx).trades.firstOrNull {
            it.ns == ns && it.symbol.equals(sym, true) &&
            (it.status == "OPEN" || it.status == "PENDING" || it.status == "EXITING")
        }
        if (live != null) {
            try {
                val config = ConfigManager(ctx)
                if (config.hasT212Credentials) {
                    val known = T212LiveStore.load(ctx).trades
                        .flatMap { it.allBuyIds() + it.allSellIds() }.toHashSet()
                    val fills = when (val r = Trading212Client(config).fetchOrderHistory(50)) {
                        is Trading212Client.T212Result.Success -> r.data
                        else -> emptyList()
                    }
                    // The most recent SELL on this ticker that WE did not place.
                    val hit = fills.filter {
                        it.ticker.equals(live.t212Ticker, true) &&
                        it.side.equals("SELL", true) &&
                        it.orderId !in known && it.fillPrice > 0.0
                    }.maxByOrNull { it.dateExecuted }
                    if (hit != null) {
                        matched = true
                        exitPx = hit.fillPrice
                        exitQty = hit.filledQuantity
                        val proceeds = if (hit.filledValue != 0.0) kotlin.math.abs(hit.filledValue)
                                       else hit.fillPrice * hit.filledQuantity
                        val cost = if (live.liveEntryPrice > 0.0) live.liveEntryPrice * exitQty else 0.0
                        if (cost > 0.0) realPnl = proceeds - cost
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "manual fill lookup failed", e) }

            T212LiveStore.upsert(ctx, live.copy(
                status = "MANUAL_CLOSED",
                manualClosedAt = System.currentTimeMillis(),
                manualExitPrice = exitPx, manualExitQty = exitQty,
                error = if (matched) "" else "closed by hand — fill not found in recent history"))
        }

        // ── 3. Remove the paper trade and release the name. unenroll already does
        // exactly what is wanted here: drops the OPEN trade, the watermark, the
        // baseline and the enrollment. Dropping the baseline matters most — without
        // it the next pass replays the same candles and re-creates the trade it just
        // deleted. With it, the symbol flat-starts if the gate ever re-seats it.
        val hadPaper = paperOpen != null
        // A position whose real buy never filled was never in the account: "sold
        // by you" would be untrue. (Such buys are normally moved out of the book on
        // their own — see [UnfulfilledEntries].)
        val neverBought = live == null && paperOpen != null && T212LiveStore.load(ctx).trades.any {
            it.ns == ns && it.paperTradeId == paperOpen.id && it.liveQtyFilled <= 0.0 && it.qty <= 0.0
        }
        StrategyLabStore.unenroll(ctx, sym, ns)

        // ── 4. Hand the seat on, keeping its capital. Capital is deliberately NOT
        // compounded by the discarded trade. The seat goes straight to the next
        // rising contender ([MacdGateEngine.handOn]) rather than waiting empty for
        // the next daily evaluation — you have decided against this name.
        var seatFreed = false
        var seatTo: String? = null
        try {
            val st = MacdGateStore.load(ctx, ns)
            if (st.enabled && st.slots.any { it.symbol?.equals(sym, true) == true }) {
                seatTo = MacdGateEngine.handOn(ctx, ns, sym,
                    (if (neverBought) "real buy never filled" else "sold by you") +
                        " — paper trade dropped (${"%+.2f".format(discarded)}% discarded)")
                seatFreed = true
            }
        } catch (e: Exception) { Log.w(TAG, "seat release failed", e) }

        Log.w(TAG, "[$ns] $sym marked SOLD BY HAND — paper=$hadPaper seat=$seatFreed→${seatTo ?: "cash"} fill=$matched")
        return Result(ok = true, symbol = sym,
            paperRemoved = hadPaper, discardedPnlPct = discarded,
            seatFreed = seatFreed, liveMarked = live != null,
            matchedFill = matched, exitPrice = exitPx, exitQty = exitQty, realPnl = realPnl,
            seatTo = seatTo)
    }
}
