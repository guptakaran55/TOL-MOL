package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.data.T212LiveStore
import com.signalscope.app.network.Trading212Client
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mirrors US paper Strategy-Lab decisions into REAL Trading 212 market orders.
 *
 * Deliberately conservative: OFF by default, only fires on the NEWEST closed bar
 * (never late catch-up bars), sizes each entry to a tiny hard-capped account-
 * currency amount, and NEVER retries a market order after it's been sent (a
 * duplicate could double a real position). Paper keeps running regardless.
 *
 * T212 caveats handled here:
 *  • quantity = amount / paper price (fractional shares), floored to 5 dp.
 *  • ticker is a best-effort Yahoo→T212 mapping; a wrong/unknown ticker is
 *    recorded as an ERROR, never sent as a different-symbol order.
 *  • a SELL can be refused if the position's shares are reserved by another
 *    pending sell order (manual SL/Target) — surfaced as an ERROR, not retried.
 * NOTE: T212 order responses give only an order id (fills settle async), so this
 * records orders placed, not realized fills — confirm fills in the T212 app.
 */
object T212LiveMirror {
    private const val TAG = "T212LiveMirror"

    /**
     * SINGLE-THREADED ladder queue, shared by entries and exits.
     *
     * Two reasons it must be one thread, not a pool. First, every ladder starts by
     * cancelling open orders on its ticker, and concurrent ladders on the same
     * account cancel each other's live rungs. Second, T212 rate-limits orders; a
     * pool would burn the budget in parallel and get legs rejected mid-chase.
     *
     * Serial also means five seats entering on the same bar queue up rather than
     * racing — slower, and the only version that is correct.
     */
    private val ladder: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "t212-ladder").apply { isDaemon = true }
        }

    /**
     * MASTER FREEZE over live entries — now a real switch rather than a recompile.
     *
     * It began as `const val LIVE_FROZEN = true` because the execution layer was
     * market orders and the marketable-limit ladder did not exist. The ladder now
     * exists ([PatientExecutor]) and the mirror uses it, so the reason for
     * hardcoding is gone — but the default stays FROZEN and the user throws the
     * switch, because the code being ready is not the same as the account being
     * ready. Prove it with [LiveTestBench] first.
     *
     * Sits ABOVE the Settings mirror toggle and the per-algo flags: every gate
     * must agree. Reads the flag on each call, so flipping it in Settings takes
     * effect on the next tick with no restart.
     */
    fun isFrozen(ctx: Context): Boolean = ConfigManager(ctx).t212LiveFrozen

    /**
     * Backfill REAL fill prices from T212's executed-order history.
     *
     * Deliberately NOT gated by [LIVE_FROZEN]: this only READS. It places nothing,
     * cancels nothing, and is the one piece that can be safely wired and tested
     * before live entries are ever unfrozen — including against orders placed by
     * hand in the T212 app. Reading early is how the drag figure has samples on
     * day one instead of starting from zero the moment real money goes in.
     *
     * Matching is by ORDER ID, not by symbol or time: ids are exact, whereas a
     * symbol+timestamp match would silently pair an algo order with a manual one
     * placed the same minute and quietly corrupt the slippage measurement.
     *
     * @return how many trades gained a fill price.
     */
    fun reconcileFills(ctx: Context): Int {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return 0
        val state = T212LiveStore.load(ctx)
        // A leg needs work while any rung it placed is still unaccounted for. The
        // old test was "liveEntryPrice <= 0", which stopped the moment the FIRST
        // rung priced — freezing a six-rung ladder at its cheapest fill.
        val needsBuy = state.trades.filter { it.allBuyIds().isNotEmpty() && !it.buyComplete() }
        val needsSell = state.trades.filter { it.allSellIds().isNotEmpty() && !it.sellComplete() }
        if (needsBuy.isEmpty() && needsSell.isEmpty()) return 0

        val client = Trading212Client(config)
        val fills = when (val r = client.fetchOrderHistory(limit = 50)) {
            is Trading212Client.T212Result.Success -> r.data
            is Trading212Client.T212Result.Failure -> {
                Log.w(TAG, "fill history unavailable: ${r.message}"); return 0
            }
            else -> return 0
        }
        if (fills.isEmpty()) return 0
        val byId = fills.associateBy { it.orderId }

        var updated = 0
        // Dedupe on the (ns, paperTradeId) pair — two algos can legitimately hold
        // the same ticker from the same bar, and collapsing them here would drop
        // one algo's fill.
        /**
         * Collapse one leg's rungs into a single execution.
         *
         * A ladder that buys 3 shares at 41.00 and 1 at 41.60 did NOT execute at
         * 41.30 — it executed at 41.15, because the cheap rung carried three times
         * the weight. Averaging the prices instead of weighting them by quantity is
         * the classic way to publish a slippage number that flatters the ladder,
         * and it gets worse exactly when it matters: the expensive rungs are the
         * small ones the chase had to reach for.
         *
         * A rung the history hasn't shown yet is skipped, not zero-filled, and the
         * seen-count travels with the aggregate so an incomplete leg stays visibly
         * incomplete rather than silently cheap.
         */
        fun aggregate(ids: List<Long>): Triple<Double, Double, Int>? {
            var qty = 0.0; var value = 0.0; var seen = 0
            for (id in ids) {
                val f = byId[id] ?: continue
                if (f.filledQuantity <= 0.0 || f.fillPrice <= 0.0) { seen++; continue }
                seen++
                qty += f.filledQuantity
                // filledValue is authoritative when present (it carries the broker's
                // own rounding); price × qty is the fallback, never a third guess.
                value += if (f.filledValue != 0.0) kotlin.math.abs(f.filledValue)
                         else f.fillPrice * f.filledQuantity
            }
            if (seen == 0) return null
            return Triple(qty, value, seen)
        }

        for (t in (needsBuy + needsSell).distinctBy { it.ns to it.paperTradeId }) {
            var next = t
            aggregate(t.allBuyIds())?.let { (qty, value, seen) ->
                next = next.copy(
                    liveEntryPrice = if (qty > 0.0) value / qty else 0.0,
                    liveQtyFilled = qty, liveEntryValue = value, buyRungsSeen = seen)
            }
            aggregate(t.allSellIds())?.let { (qty, value, seen) ->
                next = next.copy(
                    liveExitPrice = if (qty > 0.0) value / qty else 0.0,
                    liveExitQty = qty, liveExitValue = value, sellRungsSeen = seen)
            }
            if (next != t) {
                T212LiveStore.upsert(ctx, next.copy(fillsSyncedAt = System.currentTimeMillis()))
                updated++
                Log.i(TAG, "fills for ${t.symbol}: paper ${t.paperEntryPrice} → real ${next.liveEntryPrice} " +
                    "(${next.buyRungsSeen}/${t.allBuyIds().size} buy rungs, " +
                    "${next.sellRungsSeen}/${t.allSellIds().size} sell rungs)")
            }
        }
        return updated
    }

    /**
     * Paper-vs-real samples for [SlippageStats]. Only fully-known round trips
     * qualify — a trade missing either fill price is EXCLUDED rather than
     * defaulted, because a zero would read as a catastrophic fill and poison the
     * average that is meant to calibrate the slippage constant.
     */
    fun slippageSamples(ctx: Context): List<SlippageStats.Sample> =
        T212LiveStore.load(ctx).trades.mapNotNull { t ->
            // BOTH legs must be fully reconciled. A ladder with two of six rungs
            // reported would otherwise contribute a real-looking sample built from
            // a third of the position, and it would bias low every time — the
            // rungs that report late are the ones the chase paid up for.
            // A hand-closed exit measures the user, not the ladder.
            if (t.isManual()) return@mapNotNull null
            if (!t.buyComplete() || !t.sellComplete()) return@mapNotNull null
            val pe = t.paperEntryPrice; val px = t.paperExitPrice ?: 0.0
            val le = t.liveEntryPrice;  val lx = t.liveExitPrice
            if (pe <= 0.0 || px <= 0.0 || le <= 0.0 || lx <= 0.0) return@mapNotNull null
            val spent = if (t.liveEntryValue > 0.0) t.liveEntryValue else le * t.liveQtyFilled
            if (spent <= 0.0) return@mapNotNull null
            // Exit quantity is its OWN number: a partial sell, or a ladder that
            // could not place the last rung, leaves fewer shares sold than bought.
            // Reusing the buy quantity here booked cash that was never received.
            // ownExitQty/ownExitValue also strip any SWEPT dust — shares this row
            // never bought, whose proceeds would otherwise show up as free return.
            val exitQty = if (t.ownExitQty() > 0.0) t.ownExitQty() else t.liveQtyFilled
            val received = if (t.ownExitValue() > 0.0) t.ownExitValue() else lx * exitQty
            SlippageStats.Sample(
                paperEntry = pe, liveEntry = le, paperExit = px, liveExit = lx,
                quoteSpent = spent, livePnlQuote = received - spent)
        }

    /**
     * PER-ALGO live config, mirroring BinanceLiveMirror.liveCfgFor.
     *
     * Previously T212 had exactly one global Settings switch and one global
     * per-trade amount. With six German algos sharing a 106-name universe that is
     * all-or-nothing: unfreezing would arm every algo at once, at one size, with no
     * way to say "real money only in the liquid names". Bucket algos now carry
     * their own live flag, budget and allowlist exactly as crypto ones do, so a
     * paper algo simply no-ops instead of quietly becoming live.
     */
    private data class LiveCfg(
        val enabled: Boolean,
        val allows: (String) -> Boolean,
        val perTrade: Double
    )

    private fun liveCfgFor(ctx: Context, config: ConfigManager, ns: String): LiveCfg {
        if (!Spaces.isBucket(ns)) return LiveCfg(
            enabled = config.t212LiveMirrorEnabled,
            allows = { true },                       // legacy bare path: no allowlist
            perTrade = config.t212LivePerTradeAmount)
        val b = com.signalscope.app.data.BucketStore.get(ctx, ns)
        val allow = (b?.liveAllowlist ?: emptyList()).map { it.uppercase() }.toSet()
        // Budget is spread over the SEATS, not the whole 106-name universe — a
        // 3b algo only ever holds holdN of them at once, so dividing by the
        // universe would size every order ~20× too small to be tradeable.
        val seats = (b?.holdN ?: 0).coerceAtLeast(1)
        val per = if (b != null && b.liveBudget > 0.0) b.liveBudget / seats else 0.0
        return LiveCfg(
            enabled = (b?.live == true) && config.hasT212Credentials,
            allows = { allow.isNotEmpty() && it.uppercase() in allow },
            perTrade = per)
    }

    fun mirrorEntry(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade,
                    space: String = "US", ns: String = "") {
        // Each decline is RECORDED, not just logged. A paper position with no
        // broker order behind it is indistinguishable between these cases from
        // the card, and they need opposite responses — lift a switch, arm an
        // algo, widen an allowlist, or simply wait for the next bar.
        if (isFrozen(ctx)) {
            Log.i(TAG, "T212 live mirror FROZEN — skipping entry ${trade.symbol}")
            com.signalscope.app.data.LiveSkipStore.note(ctx, ns, paperTradeId, trade.symbol,
                "master freeze is ON — no algo can place real orders"); return
        }
        val config = ConfigManager(ctx)
        val lc = liveCfgFor(ctx, config, ns)
        if (!lc.enabled) {
            com.signalscope.app.data.LiveSkipStore.note(ctx, ns, paperTradeId, trade.symbol,
                if (!config.t212LiveMirrorEnabled)
                    "real orders are off for stocks in Settings"
                else "this algo is not armed for real money (or its budget is 0)")
            return
        }
        val sym = trade.symbol.uppercase()
        // Real money is opt-IN per ticker. A paper entry on a researched-but-not-
        // funded name must never reach the broker. Silent by design: this is normal
        // operation, not a failure, and it is what keeps the gate free to rank all
        // 106 names while only the liquid ones are actually funded.
        if (!lc.allows(sym)) {
            Log.i(TAG, "[$ns] T212 live SKIPPED $sym — not in live allowlist (paper unaffected)")
            com.signalscope.app.data.LiveSkipStore.note(ctx, ns, paperTradeId, sym,
                "$sym is not in this algo's real-money list")
            return
        }
        if (!config.hasT212Credentials) {
            T212LiveStore.markError(ctx, paperTradeId, sym, "", trade.entryTs, "T212 credentials missing", ns); return
        }
        if (T212LiveStore.get(ctx, paperTradeId, ns) != null) return          // idempotent

        val client = Trading212Client(config)
        // Bulletproof ticker resolution — never send a guessed symbol.
        val ticker = client.resolveT212Ticker(sym, space)
            ?: run { T212LiveStore.markError(ctx, paperTradeId, sym, "", trade.entryTs,
                "Couldn't resolve \"$sym\" to a T212 instrument (not found in the live instrument list).", ns); return }

        val price = trade.entryPrice
        if (price <= 0.0) { T212LiveStore.markError(ctx, paperTradeId, sym, ticker, trade.entryTs, "no price to size the order", ns); return }
        val amount = lc.perTrade.coerceIn(1.0, 3000.0)
        // Don't attempt an order we clearly can't fund — avoids a guaranteed
        // reject (and a possible forced FX conversion). Free cash is consolidated
        // in the account base currency; we can't isolate a USD-only sub-balance.
        val free = (client.fetchAccountSummary() as? Trading212Client.T212Result.Success)?.data?.freeFunds
        if (free != null && amount > free + 0.001) {
            T212LiveStore.markError(ctx, paperTradeId, sym, ticker, trade.entryTs,
                "Skipped: live BUY size ${"%.2f".format(amount)} exceeds T212 free cash ${"%.2f".format(free)} (${client.fetchAccountCurrency()}). Lower the size in Settings or add funds.", ns); return
        }
        val qty = BigDecimal(amount / price).setScale(5, RoundingMode.DOWN).toDouble()
        if (qty <= 0.0) { T212LiveStore.markError(ctx, paperTradeId, sym, ticker, trade.entryTs, "size rounds to zero shares (price too high for the amount)", ns); return }
        // NO SHARE-COUNT ENTRY GUARD. T212's floor is €1.00 of VALUE, and a live
        // per-trade budget is euros to begin with, so every entry clears it and so
        // does the residue it leaves a sibling. An earlier version refused entries
        // under 1.00 SHARES — a rule that does not exist, and one that would have
        // blocked most of a 106-name German universe at €20 a trade.
        //
        // The genuine constraint is dust at EXIT: a sale must close the position or
        // leave at least €1 of it. PatientExecutor enforces that, where the live
        // price and the actual pool are both in hand.
        if (amount < Trading212Client.MIN_POSITION_VALUE * 1.1) {
            T212LiveStore.markError(ctx, paperTradeId, sym, ticker, trade.entryTs,
                "Skipped: live size ${"%.2f".format(amount)} is at or below T212's " +
                "${"%.2f".format(Trading212Client.MIN_POSITION_VALUE)} minimum order value.", ns)
            return
        }

        // CLAIM THE SLOT BEFORE PLACING ANYTHING.
        // The ladder below runs on its own thread and dwells for minutes; the tick
        // that started it will have returned long before it finishes, and the next
        // tick re-derives the same entry from the same bar. Without a row on disk
        // right now, the idempotency check above still reads "nothing here" and a
        // second ladder launches on the same signal — two real positions for one
        // decision. The PENDING row is what makes that impossible.
        T212LiveStore.upsert(ctx, T212LiveStore.LiveTrade(
            paperTradeId = paperTradeId, ns = ns, symbol = sym, t212Ticker = ticker,
            status = "PENDING", entryTs = trade.entryTs, paperEntryPrice = price,
            amount = amount, qty = qty, buyAt = System.currentTimeMillis()))

        // QUEUE THE INTENT — do not place yet. See [flushEntryIntents] for why.
        val ys = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(
            sym, if (space.equals("DE", true)) "DE" else "US")
        com.signalscope.app.data.LiveSkipStore.clear(ctx, ns, paperTradeId)
        synchronized(pending) {
            pending.add(EntryIntent(ns, paperTradeId, sym, ticker, ys, space, amount, price))
        }
        scheduleFlush(ctx)
    }

    // ══════════════════════════════════════════════════════════════════════
    // COALESCED ENTRIES
    // ══════════════════════════════════════════════════════════════════════
    // Two algos ranking the same universe with the same screener pick the same
    // company — routinely, not occasionally. Placed separately, that became two
    // ladders on one name: they run one at a time (concurrent chases cancel each
    // other's rungs), so the second started minutes later at a worse anchor and
    // walked its rungs without filling. One algo got the position, the other got
    // an unfilled order and a paper trade claiming shares it never owned.
    //
    // The fix is to place ONE order for the combined size and split the fill.
    // Both books get the shares their own strategy asked for, the spread is
    // crossed once instead of twice, and neither algo's holdings differ from
    // what its own ranking chose — which matters more than it sounds: the moment
    // execution changes WHICH name an algo holds, its paper book and its real
    // book stop being comparable and every deviation measurement built on that
    // comparison becomes meaningless.
    //
    // WHY A DELAY RATHER THAN A PRE-PASS. Each algo runs in its own call, and
    // several callers drive them (the service loop, manual runs, save-and-run).
    // A short window catches siblings from the same tick without any caller
    // needing to know coalescing exists — and an intent left behind by an
    // unusual path still gets placed, just alone.

    private data class EntryIntent(
        val ns: String, val paperTradeId: String, val sym: String,
        val ticker: String, val yahooSym: String, val space: String,
        val amount: Double, val price: Double
    )

    private val pending = ArrayList<EntryIntent>()
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val flushTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor {
        Thread(it, "t212-coalesce").apply { isDaemon = true }
    }
    /** Long enough to catch siblings in one tick, short enough that a lone entry
     *  is not meaningfully delayed against its signal bar. */
    private const val COALESCE_WINDOW_MS = 6_000L

    private fun scheduleFlush(ctx: Context) {
        if (!flushScheduled.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        flushTimer.schedule({
            flushScheduled.set(false)
            try { flushEntryIntents(app) } catch (e: Exception) { Log.e(TAG, "flush failed", e) }
        }, COALESCE_WINDOW_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Place one ladder per ticker for everything queued, then divide each fill
     * between the algos that asked for it, in proportion to what they asked for.
     *
     * Proportional rather than equal: two algos with different budgets want
     * different amounts of the same name, and splitting a partial fill equally
     * would hand the smaller one more than it ordered.
     *
     * Safe to call at any time; does nothing when the queue is empty. The
     * service calls it after its sweep so a tick's entries go out promptly
     * rather than waiting on the timer.
     */
    fun flushEntryIntents(ctx: Context) {
        val batch: List<EntryIntent>
        synchronized(pending) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending); pending.clear()
        }
        val config = ConfigManager(ctx)
        for ((ticker, group) in batch.groupBy { it.ticker }) {
            val total = group.sumOf { it.amount }
            val head = group.first()
            ladder.execute {
                try {
                    val r = PatientExecutor.buy(config, ticker, head.yahooSym,
                        head.space, total, head.price)
                    if (!r.ok || r.filled <= 0.0) {
                        val why = r.error ?: (r.note.ifBlank { "ladder filled nothing" })
                        group.forEach { g ->
                            T212LiveStore.get(ctx, g.paperTradeId, g.ns)?.let { row ->
                                // ZERO, not the ordered amount. A row left claiming
                                // shares it never received is what produced the
                                // phantom ledger shortfall on MTX.
                                T212LiveStore.upsert(ctx, row.copy(status = "ERROR", qty = 0.0,
                                    error = why, buyOrderIds = r.orderIds, refEntryPrice = r.refPrice))
                            }
                        }
                        // Only an ACTIONABLE failure earns an interruption. A closed
                        // market cannot be worked around by hand, so telling the
                        // user to go and place an order would be advice they cannot
                        // take — and unusable notifications are what teach people to
                        // dismiss the usable ones unread.
                        val marketWasShut = why.contains("is closed", true) ||
                                            why.contains("after hours", true)
                        if (!marketWasShut) {
                            val algoNames = group.mapNotNull {
                                com.signalscope.app.data.BucketStore.get(ctx, it.ns)?.name
                            }.distinct().joinToString(" + ").ifBlank { "An algo" }
                            LabNotifier.ladderUnfilled(ctx, algoNames, head.sym, total,
                                if (head.space.equals("DE", true)) "EUR" else "USD", why)
                        }
                        Log.w(TAG, "coalesced BUY ${head.sym} x${group.size} unfilled: $why")
                        return@execute
                    }
                    // Split proportionally. The last participant absorbs the
                    // rounding remainder so the parts always sum to the whole —
                    // otherwise a few thousandths of a share belong to nobody and
                    // the audit reports a permanent shortfall.
                    var handed = 0.0
                    group.forEachIndexed { i, g ->
                        val share = if (i == group.lastIndex) (r.filled - handed)
                                    else Math.floor(r.filled * (g.amount / total) * 1e5) / 1e5
                        handed += share
                        T212LiveStore.get(ctx, g.paperTradeId, g.ns)?.let { row ->
                            T212LiveStore.upsert(ctx, row.copy(
                                status = if (share > 0.0) "OPEN" else "ERROR",
                                qty = share,
                                error = if (share > 0.0) "" else "coalesced fill left nothing for this algo",
                                buyOrderId = r.orderIds.firstOrNull() ?: 0L,
                                buyOrderIds = r.orderIds, refEntryPrice = r.refPrice))
                        }
                    }
                    Log.i(TAG, "coalesced BUY ${head.sym}: ${r.filled} over ${r.orderIds.size} rung(s) " +
                        "split across ${group.size} algo(s) [${group.joinToString(",") { it.ns }}]")
                } catch (e: Exception) {
                    Log.e(TAG, "coalesced BUY ${head.sym} threw", e)
                    group.forEach { g ->
                        T212LiveStore.get(ctx, g.paperTradeId, g.ns)?.let {
                            T212LiveStore.upsert(ctx, it.copy(status = "ERROR", qty = 0.0,
                                error = e.message ?: "ladder failed"))
                        }
                    }
                }
            }
        }
    }

    // NOTE: exits are deliberately NOT gated by LIVE_FROZEN — an exit only acts
    // on an already-open live position (risk-reducing), and with entries frozen
    // no new positions can appear. Blocking exits would strand a legacy position.
    fun mirrorExit(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade,
                   space: String = "US", ns: String = "") {
        val config = ConfigManager(ctx)
        if (!config.t212LiveMirrorEnabled) return
        // Checked BEFORE openFor: a MANUAL_CLOSED row is not "OPEN", so openFor would
        // return null and the generic "no open live mirror" message would hide the
        // real reason. The shares are gone — attempting the exit would be rejected at
        // best, and at worst would sell something the user has since bought back in
        // the same name.
        T212LiveStore.get(ctx, paperTradeId, ns)?.let {
            if (it.isManual()) {
                Log.i(TAG, "[$ns] ${it.symbol} was closed by hand — skipping live exit"); return
            }
        }
        val live = T212LiveStore.openFor(ctx, paperTradeId, ns) ?: run {
            Log.i(TAG, "no open live mirror for $paperTradeId; skipping live sell"); return
        }
        if (!config.hasT212Credentials) {
            T212LiveStore.upsert(ctx, live.copy(status = "ERROR", error = "T212 credentials missing before sell")); return
        }
        // SELL WHAT WE OWN. Preferring the reconciled fill quantity over the ordered
        // one matters here more than anywhere: a partially-filled entry that is then
        // sold at the ORDERED size is a short leg the account never authorised, and
        // T212 rejects it — leaving a live position the app believes is closed and
        // will therefore never try to exit again.
        val sellQty = if (live.liveQtyFilled > 0.0) live.liveQtyFilled else live.qty
        // Marked EXITING, not CLOSED, until the ladder reports. A row flipped to
        // CLOSED up front would drop out of openFor() and strand the position if the
        // sell failed.
        T212LiveStore.upsert(ctx, live.copy(status = "EXITING",
            exitTs = trade.exitTs, paperExitPrice = trade.exitPrice,
            sellAt = System.currentTimeMillis(), error = ""))
        val ysx = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(
            live.symbol, if (space.equals("DE", true)) "DE" else "US")
        val exitRef = trade.exitPrice ?: 0.0
        // WHO ELSE OWNS THIS NAME. Six German algos share one 106-symbol universe,
        // so two of them holding the same ticker is ordinary, not exotic. The pooled
        // T212 position must be divided by claim; an unbounded exit would liquidate
        // the siblings and any manual holding along with its own shares.
        val others = T212LiveStore.otherClaimsQty(ctx, live.t212Ticker, ns, paperTradeId)
        if (others > 0.0)
            Log.i(TAG, "[$ns] live SELL ${live.symbol}: our claim $sellQty, other rows claim $others")
        ladder.execute {
            try {
                val r = PatientExecutor.sell(config, live.t212Ticker, ysx, space, sellQty, exitRef,
                    targetQty = sellQty, claimedByOthers = others)
                val row = T212LiveStore.get(ctx, paperTradeId, ns) ?: return@execute
                if (!r.ok || r.filled <= 0.0) {
                    // Still OPEN at the broker, so it must go back to OPEN here or the
                    // next exit attempt will not find it.
                    T212LiveStore.upsert(ctx, row.copy(status = "OPEN",
                        error = "exit unfilled: " + (r.error ?: r.note.ifBlank { "nothing sold" }),
                        sellOrderIds = r.orderIds, refExitPrice = r.refPrice))
                    // MORE URGENT THAN AN UNFILLED ENTRY. A missed entry costs an
                    // opportunity; a missed exit leaves real money in a position the
                    // strategy has already decided to leave, still exposed and no
                    // longer being managed by anything except the next attempt.
                    val why = r.error ?: r.note.ifBlank { "nothing sold" }
                    if (!why.contains("is closed", true) && !why.contains("after hours", true)) {
                        val algoName = com.signalscope.app.data.BucketStore.get(ctx, ns)?.name ?: "An algo"
                        LabNotifier.ladderExitUnfilled(ctx, algoName, live.symbol, sellQty, why)
                    }
                    Log.w(TAG, "[$ns] live SELL ${live.symbol} unfilled — position still open")
                    return@execute
                }
                T212LiveStore.upsert(ctx, row.copy(status = "CLOSED",
                    sellOrderId = r.orderIds.firstOrNull() ?: 0L,
                    sellOrderIds = r.orderIds, refExitPrice = r.refPrice,
                    sweptQty = r.sweptQty, error = ""))
                if (r.sweptQty > 0.0)
                    Log.i(TAG, "[$ns] swept ${r.sweptQty} unclaimed ${live.symbol} dust with the exit " +
                        "— recorded outside strategy P&L")
                Log.i(TAG, "[$ns] live SELL ${live.symbol} filled ${r.filled} over ${r.orderIds.size} rung(s)")
            } catch (e: Exception) {
                Log.e(TAG, "[$ns] live SELL ${live.symbol} threw", e)
                T212LiveStore.get(ctx, paperTradeId, ns)?.let {
                    T212LiveStore.upsert(ctx, it.copy(status = "OPEN", error = e.message ?: "exit ladder failed"))
                }
            }
        }
    }

}
