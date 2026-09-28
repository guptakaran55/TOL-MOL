package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ZerodhaTradeStore

// ════════════════════════════════════════════════════════════════════════════
// WHAT THE INDIAN ACCOUNT ACTUALLY MADE
// ════════════════════════════════════════════════════════════════════════════
// The same question the Trading 212 analysis answers, and deliberately the same
// engine underneath — see [ClosedTrades]. Only the two ends differ:
//
//   WHERE THE FILLS COME FROM. Trading 212 serves a paged history on demand, so
//   its panel can be built from nothing at any moment. Kite serves TODAY and
//   forgets, so India's fills are accumulated day by day in a local store and
//   the panel can only ever know what the app was running to see. That is a real
//   limitation and the report says so out loud rather than presenting a partial
//   total as a complete one.
//
//   WHAT A "COST" IS. A T212 fill carries a cash amount in the account currency
//   which may differ from price × quantity after conversion. A Zerodha account
//   settles rupees for rupee instruments, so the two cannot disagree and the
//   cash column is simply the product.
// ════════════════════════════════════════════════════════════════════════════

object ZerodhaClosedTrades {

    private const val TAG = "ZerodhaClosedTrades"

    /**
     * Capture anything new, then match everything held.
     *
     * Network — call it off the main thread.
     *
     * @param force poll the broker even if one ran moments ago, and say plainly
     *   when the poll itself failed. A user who just tapped "re-read" is owed the
     *   outcome of THEIR tap, not the result of a cached one.
     */
    fun run(ctx: Context, force: Boolean = false): ClosedTrades.Report {
        val cfg = ConfigManager(ctx)
        val added = try { ZerodhaTradeStore.capture(ctx, force) } catch (e: Exception) {
            Log.w(TAG, "capture failed", e); 0
        }
        val st = ZerodhaTradeStore.load(ctx)
        val fills = ZerodhaTradeStore.fillsForMatching(ctx)
        if (fills.isEmpty()) {
            return ClosedTrades.Report(
                ok = true, checkedAt = System.currentTimeMillis(), currency = "INR",
                error = if (!cfg.isZerodhaConnected)
                    "Zerodha is not connected, so no fills could be read."
                else
                    "No fills recorded yet. Kite only reports the current day, so this " +
                    "list fills up as the app sees each trading day. " + st.lastNote)
        }
        val (_, rep) = ClosedTrades.match(fills)
        // COSTED PER TRIP, from each one's own two legs. A seat-sized estimate
        // would be close but never right: stamp duty rides on the buy alone, the
        // depository fee on the sell alone, and the transaction tax on each leg's
        // own value — so a trade that sold higher than it bought pays more than
        // one that did not.
        val out = ClosedTrades.costed(rep, "in", cfg.accountCurrency).copy(currency = "INR")
        Log.i(TAG, "closed-trade scan: ${fills.size} fills (+$added new) → " +
            "${out.trips.size} round trips, gross ${"%.2f".format(out.totalPnl)} INR, " +
            "charges ${"%.2f".format(out.totalCharges)}, " +
            "net ${"%.2f".format(out.totalPnl - out.totalCharges)}")
        return out
    }

    /** Match what is already held, touching no network. For a panel that must
     *  render instantly and for every repaint after the first. */
    fun last(ctx: Context): ClosedTrades.Report {
        val st = ZerodhaTradeStore.load(ctx)
        val fills = ZerodhaTradeStore.fillsForMatching(ctx)
        if (fills.isEmpty()) return ClosedTrades.Report(
            ok = true, checkedAt = st.lastCaptureAt, currency = "INR",
            error = "No fills recorded yet. " + st.lastNote)
        val (_, rep) = ClosedTrades.match(fills)
        return ClosedTrades.costed(rep, "in", ConfigManager(ctx).accountCurrency)
            .copy(currency = "INR", checkedAt = st.lastCaptureAt)
    }

    /** The day the record starts, so a total is never read as a lifetime figure. */
    fun knownSince(ctx: Context): String = ZerodhaTradeStore.load(ctx).firstSeenDay
}
