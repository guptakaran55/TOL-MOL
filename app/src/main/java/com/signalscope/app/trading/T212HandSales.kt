package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.ManualWatchStore
import com.signalscope.app.data.StrategyLabStore
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client

/**
 * SALES YOU MADE, BROUGHT INTO THE APP'S BOOKS — without a tap to confirm them.
 *
 * Two routes a position can leave an algo or the watchlist behind its back:
 *
 *  1. YOUR SALE THROUGH THE APP — "Patient sell" on a stock's page (from the
 *     portfolio or anywhere else). The app placed that order itself, so there is
 *     nothing to guess: [afterAppSale] works out how much of what the algos and
 *     the watchlist hold went with it, closes those positions at the price the
 *     sale achieved — booked like a take-profit, not discarded — and hands each
 *     algo seat on to the next contender. Shares you hold beyond what the app
 *     manages are taken first: if you sold only those, nothing changes.
 *
 *  2. YOUR OWN ORDER AT THE BROKER — a target or stop you set from the portfolio,
 *     a bulk target, or a sale in Trading 212's own app. When an algo's shares are
 *     gone and the broker's history shows a sale under an order the app's own
 *     machinery did not place, [sweep] books it at once, at that real price, like
 *     a take-profit, and hands the seat on. A stock you chose to sell is a stock
 *     you chose to sell, whichever button you used.
 *
 *  3. GONE WITH NO SALE ON RECORD (it fell off the history window, or never
 *     showed in it). Then it waits for evidence: ENTIRELY gone from the account
 *     on two reads at least half an hour apart. [sweep] closes it as "sold by
 *     you" with the result set aside — there is no price to book — and hands the
 *     seat on. A position only partly missing is still left to you: there is no
 *     telling which shares went.
 *
 * Watchlist positions sold in the broker's app are already closed by the
 * watchlist's own two-read check, so [sweep] leaves those to it.
 */
object T212HandSales {
    private const val TAG = "T212HandSales"
    private const val PREFS = "t212_hand_sales"
    /** Gone on two reads at least this far apart before anything is closed. */
    private const val ABSENT_CONFIRM_MS = 30 * 60_000L
    /** A buy younger than this may simply not be in the holdings list yet. */
    private const val SETTLE_MS = 30 * 60_000L
    const val REASON = "sold by you"

    private fun ownQty(r: T212LiveStore.LiveTrade): Double =
        if (r.liveQtyFilled > 0.0) minOf(r.qty, r.liveQtyFilled) else r.qty

    private fun isWatcher(ns: String) = Spaces.bucketIdOf(ns) == ManualWatchStore.ID

    /** The average price the sale's own orders filled at, from the broker's
     *  history — null when the history has not caught up yet. */
    private fun avgFill(client: Trading212Client, orderIds: List<Long>): Double? {
        if (orderIds.isEmpty()) return null
        val fills = (client.fetchOrderHistory(limit = 50) as? Trading212Client.T212Result.Success)
            ?.data?.filter { it.orderId in orderIds && it.filledQuantity > 0.0 && it.fillPrice > 0.0 }
            ?: return null
        val q = fills.sumOf { it.filledQuantity }
        return if (q > 0.0) fills.sumOf { it.fillPrice * it.filledQuantity } / q else null
    }

    /**
     * After a sale you made through the app. Returns a sentence for the result
     * dialog, blank when no algo or watchlist position was involved.
     */
    fun afterAppSale(ctx: Context, ticker: String, orderIds: List<Long>, refPrice: Double): String {
        val rows = T212LiveStore.load(ctx).trades.filter {
            T212WeekTargets.applies(it.ns) && it.t212Ticker.equals(ticker, true) &&
                it.status == "OPEN" && it.qty > 0.0
        }
        if (rows.isEmpty()) return ""
        val client = Trading212Client(ConfigManager(ctx))
        // What is left in the account now. Unreadable = do nothing here; a full
        // exit is still caught by [sweep] later, from the broker's own word.
        var remaining: Double? = null
        for (attempt in 0..1) {
            val r = client.fetchPortfolio()
            if (r is Trading212Client.T212Result.Success) {
                remaining = r.data.firstOrNull { it.ticker.equals(ticker, true) }?.quantity ?: 0.0
                break
            }
            Thread.sleep(3000L)
        }
        val left = remaining ?: return ""
        val managed = rows.sumOf { ownQty(it) }
        val tol = maxOf(1e-4, managed * 0.005)
        var excess = managed - left
        if (excess <= tol) return ""                     // you sold shares the app does not manage
        val exitPx = avgFill(client, orderIds) ?: refPrice
        if (!(exitPx > 0.0)) return ""
        val now = System.currentTimeMillis()
        val notes = ArrayList<String>()
        // Newest first: the most recent buy is the one most plausibly being undone.
        for (r in rows.sortedByDescending { it.buyAt }) {
            if (excess <= tol) break
            val q = ownQty(r)
            if (excess >= q - tol) {
                notes += closeOne(ctx, r, orderIds, exitPx, now)
                excess -= q
            } else {
                // Part of this position went: it keeps the rest, still managed.
                val keep = q - excess
                T212LiveStore.upsert(ctx, r.copy(qty = keep,
                    liveQtyFilled = if (r.liveQtyFilled > 0.0) keep else r.liveQtyFilled))
                notes += "${label(ctx, r.ns)} keeps ${"%.4f".format(keep)} ${r.symbol}."
                Log.w(TAG, "[${r.ns}] ${r.symbol} part sold by you — position now $keep")
                excess = 0.0
            }
        }
        try { T212LiveMirror.reconcileFills(ctx) }
        catch (e: Exception) { Log.w(TAG, "fill read after your sale failed", e) }
        return notes.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun label(ctx: Context, ns: String): String =
        if (isWatcher(ns)) "Your watchlist"
        else try { com.signalscope.app.data.BucketStore.list(ctx, Spaces.baseOf(ns))
                .firstOrNull { it.ns == ns }?.name } catch (e: Exception) { null } ?: "The algo"

    /** Close one position everywhere at the sale's price, and move its seat on. */
    private fun closeOne(ctx: Context, r: T212LiveStore.LiveTrade, orderIds: List<Long>,
                         exitPx: Double, now: Long): String {
        T212LiveStore.upsert(ctx, r.copy(status = "CLOSED",
            sellOrderId = orderIds.firstOrNull() ?: 0L, sellOrderIds = orderIds,
            sellAt = now, refExitPrice = exitPx, error = "",
            exitOrigin = "your sale from the stock's page", exitReasonNote = REASON))
        val who = label(ctx, r.ns)
        if (isWatcher(r.ns)) {
            ManualWatchStore.mutate(ctx, Spaces.baseOf(r.ns)) { s ->
                s.copy(entries = s.entries.map { e ->
                    if (e.status == "OPEN" && "${e.symbol.uppercase()}-${e.signalBarTs}" == r.paperTradeId)
                        e.copy(status = "CLOSED", exitTs = now / 1000L, closedAt = now,
                            exitQty = e.filledQty, exitPrice = exitPx, exitReason = REASON)
                    else e
                })
            }
            Log.w(TAG, "[${r.ns}] ${r.symbol} sold by you — watchlist position closed at $exitPx")
            return "$who's ${r.symbol} position is closed."
        }
        var seat: String? = null
        val booked = StrategyLabRunner.withAlgoHeld(r.ns, 90_000L) {
            val open = StrategyLabStore.load(ctx, r.ns).trades
                .firstOrNull { it.id == r.paperTradeId && it.status == "OPEN" }
            if (open != null) AlgoTakeProfit.bookPaperExit(ctx, r.ns, open, exitPx, now, REASON)
            seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, REASON) }
                   catch (e: Exception) { Log.w(TAG, "seat hand-on failed", e); null }
            true
        } ?: false
        Log.w(TAG, "[${r.ns}] ${r.symbol} sold by you — booked=$booked at $exitPx, seat → ${seat ?: "-"}")
        return if (!booked) "$who's ${r.symbol} record was busy; it is closed at the next check."
               else "$who's ${r.symbol} position is closed at ${"%.2f".format(exitPx)}" +
                   (if (seat != null) "; its seat goes to $seat." else ".")
    }

    /** Was this execution after [sinceMs] (with a minute's slack)? Unknown = yes. */
    private fun executedAfter(iso: String, sinceMs: Long): Boolean = try {
        java.time.Instant.parse(iso).toEpochMilli() >= sinceMs - 60_000L
    } catch (e: Exception) {
        try { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() >= sinceMs - 60_000L }
        catch (e2: Exception) { true }
    }

    /**
     * A sale of an algo's shares by an order YOU placed, priced from the broker:
     * the position closed at that price, booked to the algo like a take-profit,
     * and the seat handed on. @return the stock that took the seat, or null.
     */
    private fun bookYourSale(ctx: Context, r: T212LiveStore.LiveTrade, ids: List<Long>,
                             px: Double, now: Long): String? {
        T212LiveStore.upsert(ctx, r.copy(status = "CLOSED",
            sellOrderId = ids.firstOrNull() ?: 0L, sellOrderIds = ids,
            sellAt = now, refExitPrice = px, error = "",
            exitOrigin = "your own order at Trading 212 (a target, stop or sale you set)",
            exitReasonNote = REASON_YOURS))
        var seat: String? = null
        StrategyLabRunner.withAlgoHeld(r.ns, 90_000L) {
            val open = StrategyLabStore.load(ctx, r.ns).trades
                .firstOrNull { it.id == r.paperTradeId && it.status == "OPEN" }
            if (open != null) AlgoTakeProfit.bookPaperExit(ctx, r.ns, open, px, now, REASON_YOURS)
            seat = try { MacdGateEngine.handOn(ctx, r.ns, r.symbol, REASON_YOURS) } catch (e: Exception) { null }
            true
        }
        try { T212LiveMirror.reconcileFills(ctx) } catch (_: Exception) {}
        Log.w(TAG, "[${r.ns}] ${r.symbol} sold by your own order at $px — booked, seat → ${seat ?: "-"}")
        return seat
    }

    const val REASON_YOURS = "your own order sold it"

    /**
     * Every pass: algo positions whose shares have gone from the account
     * entirely, confirmed on two reads half an hour apart. Returns a few words
     * for the pass summary, blank when idle.
     */
    fun sweep(ctx: Context): String {
        if (!ConfigManager(ctx).hasT212Credentials) return ""
        val now = System.currentTimeMillis()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ledger = T212LiveStore.load(ctx).trades.filter { T212WeekTargets.applies(it.ns) }
        val rows = ledger.filter {
            !isWatcher(it.ns) && it.status == "OPEN" && it.qty > 0.0 &&
                it.buyAt > 0L && now - it.buyAt > SETTLE_MS
        }
        // Forget absences of rows that are no longer open.
        val keys = rows.map { "${it.ns}|${it.paperTradeId}" }.toSet()
        p.all.keys.filter { it !in keys }.forEach { p.edit().remove(it).apply() }
        if (rows.isEmpty()) return ""
        val holdings = (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)?.holdings ?: return ""
        val heldTickers = holdings.filter { it.qty > 0.0 }.map { it.ticker.uppercase() }.toSet()
        val heldQty = holdings.groupBy { it.ticker.uppercase() }.mapValues { (_, v) -> v.sumOf { it.qty } }
        // A sale the strategy has already decided is the exit retry's business.
        val selling = T212ExitGuards.waitingKeys(ctx, ledger)
        // Every order the app's own machinery placed — the algos' ladders, the
        // 1-week-high targets, the safety stops. A sale under any other order id
        // was yours: a target or stop you set, or a sale in the broker's app.
        val ownIds: Set<Long> by lazy {
            val s = HashSet<Long>()
            try { T212LiveStore.load(ctx).trades.forEach { s.addAll(it.allBuyIds()); s.addAll(it.allSellIds()) } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212TargetStore.load(ctx).targets.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            try { com.signalscope.app.data.T212ExitGuardStore.load(ctx).guards.forEach {
                if (it.orderId > 0L) s.add(it.orderId); it.prevIds?.let { ids -> s.addAll(ids) } } } catch (_: Exception) {}
            s
        }
        val history: List<Trading212Client.HistoricalFill>? by lazy {
            (Trading212Client(ConfigManager(ctx)).fetchOrderHistory(limit = 50)
                as? Trading212Client.T212Result.Success)?.data
        }
        var closed = 0
        for (r in rows) {
            val key = "${r.ns}|${r.paperTradeId}"
            if (key in selling) continue
            // A scrap left after a sale is not the position — see the watchlist's
            // check, where a €1 buy kept a sold HAG holding "held".
            val bq = heldQty[r.t212Ticker.uppercase()] ?: 0.0
            if (r.t212Ticker.uppercase() in heldTickers && bq >= ownQty(r) * 0.1) {
                p.edit().remove(key).apply(); continue
            }
            // ── YOUR ORDER SOLD IT, AND THE BROKER SAYS AT WHAT PRICE ────────────
            // Proof needs no second reading: book it now, as a take-profit.
            val yours = history?.filter {
                it.ticker.equals(r.t212Ticker, true) && it.side.equals("SELL", true) &&
                    it.filledQuantity > 0.0 && it.fillPrice > 0.0 && it.orderId !in ownIds &&
                    executedAfter(it.dateExecuted, r.buyAt)
            }.orEmpty()
            if (yours.isNotEmpty()) {
                val q = yours.sumOf { it.filledQuantity }
                val px = yours.sumOf { it.fillPrice * it.filledQuantity } / q
                val seat = bookYourSale(ctx, r, yours.map { it.orderId }, px, now)
                p.edit().remove(key).apply()
                closed++
                try { LabNotifier.yourSaleBooked(ctx, r.ns, r.symbol, px, r.liveEntryPrice, seat, r.paperTradeId,
                    if (Spaces.baseOf(r.ns) == "de") "EUR" else "USD") } catch (_: Exception) {}
                continue
            }
            val first = p.getLong(key, 0L)
            if (first == 0L) {
                p.edit().putLong(key, now).apply()
                Log.w(TAG, "[${r.ns}] ${r.symbol} not in the account — asking again in half an hour")
                continue
            }
            if (now - first < ABSENT_CONFIRM_MS) continue
            val res = StrategyLabRunner.withAlgoHeld(r.ns, 60_000L) {
                ManualIntervention.markSoldByHand(ctx, r.ns, r.symbol)
            } ?: continue
            if (!res.ok) continue
            p.edit().remove(key).apply()
            closed++
            Log.w(TAG, "[${r.ns}] ${r.symbol} gone from the account since ${java.util.Date(first)} — " +
                "closed as sold by you, seat → ${res.seatTo ?: "cash"}")
            try { LabNotifier.soldOutsideApp(ctx, r.ns, r.symbol, res.seatTo, r.paperTradeId) }
            catch (_: Exception) {}
        }
        return if (closed > 0) "✋ $closed sold outside the app, closed" else ""
    }
}
