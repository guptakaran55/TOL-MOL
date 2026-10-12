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
    /** The pre-split file, holding both markets in one report. Removed by the next history read. */
    private const val LEGACY_FILE = "t212_closed_trades.json"
    private fun fileFor(space: String) = "t212_closed_trades_${space.lowercase()}.json"
    private val gson = Gson()

    /** The two spaces one Trading 212 account is shown as. */
    val SPACES = listOf("US", "DE")

    /**
     * Which space a closed trade belongs to: "DE" for Xetra, "US" for the rest.
     *
     * ONE ACCOUNT, TWO TABS. Trading 212 keeps German and US shares in a single
     * account and a single order history, but the app shows them as separate
     * markets — so the history is split before it is matched, and each tab totals
     * only its own trades. Matching is per ticker anyway, so splitting first
     * changes no round trip, only which total it lands in.
     *
     * Uses the same venue rule the feed lookup does, so a stock can never be
     * German on one screen and American on another.
     */
    fun spaceOf(ticker: String): String =
        if (Trading212Client.wantedFeedSuffix(ticker) == ".DE") "DE" else "US"

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

    /**
     * Fetch once, split by market, match each, cache each. Network — call it off
     * the main thread. Returns [space]'s report.
     */
    fun run(ctx: Context, space: String): ClosedTrades.Report {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials)
            return storeAll(ctx, ClosedTrades.Report(error = "No Trading 212 API key saved."))
        val fills = when (val r = Trading212Client(config).fetchOrderHistoryPaged()) {
            is Trading212Client.T212Result.Success -> r.data
            is Trading212Client.T212Result.Failure ->
                return storeAll(ctx, ClosedTrades.Report(error = r.message))
            else -> return storeAll(ctx, ClosedTrades.Report(error = "Trading 212 not configured"))
        }
        if (fills.isEmpty())
            return storeAll(ctx, ClosedTrades.Report(ok = true, checkedAt = System.currentTimeMillis(),
                error = "The history endpoint answered but reported no executed orders."))
        // COSTED BY MARKET. Trading 212's only per-trade charge is its 0.15%
        // conversion fee, paid on each leg when the stock's currency is not the
        // account's — US shares on a euro account pay it both ways, German shares
        // on a euro account pay nothing. The pairing of market and account
        // currency is exactly what [TradingCosts] already decides, so the closed
        // trades are costed through it rather than by a second copy of the rule.
        val bySpace = SPACES.associateWith { sp ->
            val (_, rep) = match(fills.filter { spaceOf(it.ticker) == sp })
            ClosedTrades.costed(rep, sp.lowercase(), config.accountCurrency)
                .copy(currency = config.accountCurrency).also { store(ctx, sp, it) }
        }
        File(ctx.filesDir, LEGACY_FILE).delete()
        Log.i(TAG, "closed-trade scan: ${fills.size} fills → " + bySpace.entries.joinToString { (sp, r) ->
            "$sp ${r.trips.size} round trips, net ${"%.2f".format(r.totalPnl)} ${r.currency}" })
        return bySpace.getValue(space.uppercase().takeIf { it in SPACES } ?: "US")
    }

    /** The last report for [space], for a panel that must render without hitting the network. */
    fun last(ctx: Context, space: String): ClosedTrades.Report = try {
        val f = File(ctx.filesDir, fileFor(space))
        if (!f.exists()) ClosedTrades.Report(error = "not scanned yet")
        else {
            val r = gson.fromJson(f.readText(), ClosedTrades.Report::class.java)
                ?: ClosedTrades.Report(error = "unreadable")
            // Costed on reading too, so a report saved before costing existed
            // shows its fees without waiting for the next history read.
            if (r.ok && r.trips.isNotEmpty())
                ClosedTrades.costed(r, space.lowercase(), ConfigManager(ctx).accountCurrency)
            else r
        }
    } catch (e: Exception) { ClosedTrades.Report(error = e.message ?: "unreadable") }

    /** An account-wide outcome (no key, fetch failed) is the same answer on both tabs. */
    private fun storeAll(ctx: Context, r: ClosedTrades.Report): ClosedTrades.Report {
        SPACES.forEach { store(ctx, it, r) }
        return r
    }

    private fun store(ctx: Context, space: String, r: ClosedTrades.Report) {
        try { File(ctx.filesDir, fileFor(space)).writeText(gson.toJson(r)) }
        catch (e: Exception) { Log.w(TAG, "closed-trade store failed", e) }
    }
}
