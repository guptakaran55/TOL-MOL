package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.Trading212Client
import java.io.File

// ════════════════════════════════════════════════════════════════════════════
// WHAT THE ACCOUNT ACTUALLY MADE — closed round trips, built from real fills
// ════════════════════════════════════════════════════════════════════════════
// Trading 212 tells you when something was bought and when something was sold.
// It does not tell you what any of it EARNED: there is no list of completed
// round trips and no per-trade P&L, so the one question a trader asks at the end
// of a month — which of these actually worked — has no answer in the app.
//
// This builds that answer from the executed-order history, which is the only
// record of what really happened. Nothing here is derived from the strategy's
// opinion of a trade; a signal price is what the app WANTED, and this file is
// exclusively about what the broker DID.
//
// ── ONLY CLOSED QUANTITY COUNTS ─────────────────────────────────────────────
// Buy 100, sell 99, and the profit is on 99 shares. The hundredth is still an
// open position with an unrealised mark, and folding it in would report money
// that has not been made. So sells are matched against buys share by share, and
// whatever is left unsold stays out of every total on this page.
//
// ── FIRST IN, FIRST OUT ─────────────────────────────────────────────────────
// A sell consumes the oldest shares still held. That is the convention nearly
// every broker and tax authority uses, so the numbers here agree with the ones
// the user will see elsewhere. The alternative — averaging every buy together —
// hides exactly the thing worth seeing: that the early entry made money and the
// one chased later did not.
//
// ── CASH, NOT PRICES ────────────────────────────────────────────────────────
// P&L is computed from the cash each leg actually moved, prorated to the shares
// consumed, NOT from price × quantity. On an account whose currency differs from
// the instrument's, those are different numbers — a $100 fill is about €86 — and
// this app has already once recorded a per-share price in the wrong currency and
// put a stop 17% away from where it belonged. Cash is what left the account and
// cash is what came back.
// ════════════════════════════════════════════════════════════════════════════

object T212ClosedTrades {

    private const val TAG = "T212ClosedTrades"
    private const val FILE = "t212_closed_trades.json"
    private val gson = Gson()

    // THE SHAPES AND THE MATCHING NOW LIVE IN [ClosedTrades], shared with India.
    // Kept reachable under the old names so nothing that reads a report has to
    // care where the engine moved to.
    /** "SAPd_EQ" → "SAP". The suffix is Trading 212's, not the market's. */
    private fun bare(ticker: String): String =
        ticker.substringBefore("_").removeSuffix("d").removeSuffix("l").ifBlank { ticker }

    /**
     * Translate Trading 212's fills into neutral ones and hand them to the shared
     * matcher. The only thing that is genuinely T212's here is how a ticker is
     * spelled — everything else about pairing a sell to its buys is the same
     * question in every market.
     */
    fun match(fills: List<Trading212Client.HistoricalFill>): Pair<List<ClosedTrades.RoundTrip>, ClosedTrades.Report> =
        ClosedTrades.match(fills.map {
            ClosedTrades.Fill(
                ticker = it.ticker, symbol = bare(it.ticker),
                qty = it.filledQuantity, price = it.fillPrice, cash = it.filledValue,
                side = it.side, at = it.dateExecuted)
        })

    /** Kept as a door onto the shared rule so the tests that pin its thresholds —
     *  the 203.66 case among them — go on testing the code that actually runs. */
    fun cashPerShare(cash: Double, qty: Double, price: Double): Double =
        ClosedTrades.cashPerShare(cash, qty, price)

    /** Fetch, match, cache. Network — call it off the main thread. */
    fun run(ctx: Context): ClosedTrades.Report {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials)
            return ClosedTrades.Report(error = "No Trading 212 API key saved.").also { store(ctx, it) }
        val fills = when (val r = Trading212Client(config).fetchOrderHistoryPaged()) {
            is Trading212Client.T212Result.Success -> r.data
            is Trading212Client.T212Result.Failure ->
                return ClosedTrades.Report(error = r.message).also { store(ctx, it) }
            else -> return ClosedTrades.Report(error = "Trading 212 not configured").also { store(ctx, it) }
        }
        if (fills.isEmpty())
            return ClosedTrades.Report(ok = true, checkedAt = System.currentTimeMillis(),
                error = "The history endpoint answered but reported no executed orders.")
                .also { store(ctx, it) }
        val (_, rep) = match(fills)
        // DELIBERATELY NOT COSTED, unlike the Indian report.
        //
        // This walk is ACCOUNT-WIDE: one list holding German and US trades
        // together. Their charges are not the same — a euro account buying euro
        // stock converts nothing, while the same account buying a Nasdaq share
        // pays 0.15% on each leg — and the fill rows carry no currency to tell
        // them apart. Applying either rate to the whole list would be wrong for
        // half of it, and a P&L "after charges" that is wrong for half the rows
        // is worse than one that admits it has not costed them. The panel says
        // so rather than showing a net figure it cannot stand behind.
        val out = rep.copy(currency = config.accountCurrency)
        store(ctx, out)
        Log.i(TAG, "closed-trade scan: ${fills.size} fills → ${out.trips.size} round trips, " +
            "net ${"%.2f".format(out.totalPnl)} ${out.currency}")
        return out
    }

    /** The last report, for a panel that must render without hitting the network. */
    fun last(ctx: Context): ClosedTrades.Report = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) ClosedTrades.Report(error = "not scanned yet")
        else gson.fromJson(f.readText(), ClosedTrades.Report::class.java) ?: ClosedTrades.Report(error = "unreadable")
    } catch (e: Exception) { ClosedTrades.Report(error = e.message ?: "unreadable") }

    private fun store(ctx: Context, r: ClosedTrades.Report) {
        try { File(ctx.filesDir, FILE).writeText(gson.toJson(r)) }
        catch (e: Exception) { Log.w(TAG, "closed-trade store failed", e) }
    }
}
