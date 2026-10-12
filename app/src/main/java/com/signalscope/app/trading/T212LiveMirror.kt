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
     * IT ALSO SENDS THE ONLY ALERT THAT EVER CARRIES REAL EXIT MONEY. Placing a
     * sell returns an order id; the price it achieved arrives here, minutes later.
     * So the notice sent at sell time can only ever say "sold, price to follow",
     * and this is the one place that knows enough to finish the sentence. It is
     * wired here rather than in the selling paths for the same reason the fill
     * prices are: there are four ways to sell and exactly one way to learn what a
     * sale was worth, and duplicating that knowledge is how the two drift apart.
     *
     * @param notifySettled false when the caller is itself about to report the
     *   sale — [stopOutNow] calls this inline hoping the fill has already landed,
     *   and two alerts for one sale is the failure this whole change exists to fix.
     * @return how many trades gained a fill price.
     */
    fun reconcileFills(ctx: Context, notifySettled: Boolean = true): Int {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return 0
        val state = T212LiveStore.load(ctx)
        // A leg needs work while any rung it placed is still unaccounted for. The
        // old test was "liveEntryPrice <= 0", which stopped the moment the FIRST
        // rung priced — freezing a six-rung ladder at its cheapest fill.
        // Order ids are matched against TRADING 212's history, so a row from
        // another broker can only ever match by coincidence — and a coincidence
        // here writes a fill price onto the wrong position.
        fun mine(t: com.signalscope.app.data.T212LiveStore.LiveTrade) =
            T212LiveStore.isT212Ns(t.ns)
        val needsBuy = state.trades.filter { mine(it) && it.allBuyIds().isNotEmpty() && !it.buyComplete() }
        val needsSell = state.trades.filter { mine(it) && it.allSellIds().isNotEmpty() && !it.sellComplete() }
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
        /**
         * @return qty, PRICE-value (instrument currency), CASH-value (account
         *   currency), and how many rungs were seen.
         *
         * TWO VALUES, NOT ONE, BECAUSE THEY ARE IN DIFFERENT CURRENCIES.
         *
         * `fillPrice` is per share in the INSTRUMENT's currency; `filledValue` is
         * the cash that moved in the ACCOUNT's. On a euro account buying German
         * stocks those agree, so one number served both jobs and the difference
         * never showed. Buying US stocks from that same account they do not: a $100
         * fill reports roughly €86, and dividing €86 by the share count produced a
         * per-share price about 14% below what was actually paid.
         *
         * That number is not cosmetic — it anchors the real stop. A CRWD position
         * filled at $227.66 was recorded at $196.26, putting its "4% stop" at
         * $188.41 instead of $218.55: a 17% hole where four percent of protection
         * was supposed to be, displayed as if it were correct.
         *
         * So the per-share average is now built ONLY from fillPrice, and filledValue
         * is kept for what it actually is — the cash the account parted with.
         */
        fun aggregate(ids: List<Long>): List<Double>? {
            var qty = 0.0; var priceValue = 0.0; var cashValue = 0.0; var seen = 0
            for (id in ids) {
                val f = byId[id] ?: continue
                if (f.filledQuantity <= 0.0 || f.fillPrice <= 0.0) { seen++; continue }
                seen++
                qty += f.filledQuantity
                priceValue += f.fillPrice * f.filledQuantity
                cashValue += if (f.filledValue != 0.0) kotlin.math.abs(f.filledValue)
                             else f.fillPrice * f.filledQuantity
            }
            if (seen == 0) return null
            return listOf(qty, priceValue, cashValue, seen.toDouble())
        }

        for (t in (needsBuy + needsSell).distinctBy { it.ns to it.paperTradeId }) {
            var next = t
            aggregate(t.allBuyIds())?.let { a ->
                val qty = a[0]; val priceValue = a[1]; val cashValue = a[2]; val seen = a[3].toInt()
                next = next.copy(
                    // Per-share price from the INSTRUMENT-currency figures only.
                    liveEntryPrice = if (qty > 0.0) priceValue / qty else 0.0,
                    liveQtyFilled = qty,
                    // Cash deployed stays in the ACCOUNT's currency — that is what
                    // actually left the account.
                    liveEntryValue = cashValue, buyRungsSeen = seen)
            }
            aggregate(t.allSellIds())?.let { a ->
                val qty = a[0]; val priceValue = a[1]; val cashValue = a[2]; val seen = a[3].toInt()
                next = next.copy(
                    liveExitPrice = if (qty > 0.0) priceValue / qty else 0.0,
                    liveExitQty = qty, liveExitValue = cashValue, sellRungsSeen = seen)
            }
            if (next != t) {
                T212LiveStore.upsert(ctx, next.copy(fillsSyncedAt = System.currentTimeMillis()))
                updated++
                Log.i(TAG, "fills for ${t.symbol}: paper ${t.paperEntryPrice} → real ${next.liveEntryPrice} " +
                    "(${next.buyRungsSeen}/${t.allBuyIds().size} buy rungs, " +
                    "${next.sellRungsSeen}/${t.allSellIds().size} sell rungs)")
                // THE MOMENT A SALE STOPS BEING PROVISIONAL. Fires on the
                // transition only — the row leaves `needsSell` once the price is
                // known, so this branch is reachable exactly once per trade and
                // needs no "already told them" flag to stay quiet afterwards.
                if (notifySettled && t.liveExitPrice <= 0.0 && next.liveExitPrice > 0.0)
                    announceSettledExit(ctx, next)
            }
        }
        return updated
    }

    /** How long after a sale a settled figure is still worth pushing. Rows written
     *  before any of this existed carry `sellAt = 0` and are therefore silent —
     *  without that, the first sync after an upgrade would fire a notification for
     *  every historical exit at once. */
    private const val SETTLED_ALERT_WINDOW_MS = 24 * 60 * 60_000L

    /**
     * Report what a sale was ACTUALLY worth, now that the broker has said.
     *
     * Replaces the provisional notice rather than joining it — same trade key,
     * same notification id — so the reader ends up with one alert per sale that
     * happens to sharpen, instead of two that disagree.
     *
     * It also repairs the WATCHLIST BOOK, which is the other half of the same
     * problem: a hand-bought position is closed the instant the sell returns, and
     * the only exit price available at that moment is the strategy's candle. The
     * book's own contract says that field holds a real per-share exit or zero, so
     * it is written as zero and corrected here. Nothing else would ever do it —
     * the routine that fills in real exits only visits entries still marked open,
     * and this one is already closed by the time the price is known.
     */
    private fun announceSettledExit(ctx: Context, t: T212LiveStore.LiveTrade) {
        // Sold by the user's own hand in the T212 app: real cash, but not an exit
        // this app decided or should announce as its own.
        if (t.isManual()) return
        if (t.sellAt <= 0L ||
            System.currentTimeMillis() - t.sellAt > SETTLED_ALERT_WINDOW_MS) return
        val qty = t.ownExitQty().takeIf { it > 0.0 } ?: return
        val cur = if (Spaces.baseOf(t.ns) == "de") "EUR" else "USD"
        try {
            LabNotifier.t212Sell(ctx, t.ns, t.symbol, qty, cur,
                reason = t.exitReasonNote.orEmpty(), tradeKey = t.paperTradeId,
                origin = t.exitOrigin.orEmpty(),
                paid = t.liveEntryPrice, realExit = t.liveExitPrice)
        } catch (e: Exception) { Log.w(TAG, "settled exit alert failed for ${t.symbol}", e) }
        try {
            com.signalscope.app.data.ManualWatchStore.recordRealExit(
                ctx, t.ns, t.paperTradeId, t.liveExitPrice, qty)
        } catch (e: Exception) { Log.w(TAG, "watch book exit repair failed for ${t.symbol}", e) }
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
        // ONE ROAD TO REAL MONEY — the algo's own arming, and nothing above it.
        // The legacy bare path is retired for entries: it had no allowlist at all,
        // so a single Settings switch armed every name at once. Exits are never
        // gated (see mirrorExit), so nothing it opened can be stranded.
        if (!Spaces.isBucket(ns)) return LiveCfg(
            enabled = false,
            allows = { false },
            perTrade = 0.0)
        val b = com.signalscope.app.data.BucketStore.get(ctx, ns)
        val allow = (b?.liveAllowlist ?: emptyList()).map { it.uppercase() }.toSet()
        // Budget is spread over the SEATS, not the whole 106-name universe — a
        // 3b algo only ever holds holdN of them at once, so dividing by the
        // universe would size every order ~20× too small to be tradeable.
        val seats = (b?.holdN ?: 0).coerceAtLeast(1)
        val per = if (b != null && b.liveBudget > 0.0) b.liveBudget / seats else 0.0
        return LiveCfg(
            // Budget joins the gate for the same reason it does on crypto: an algo
            // armed with nothing allocated should place nothing, not fall through
            // to a minimum order size decided somewhere else.
            enabled = (b?.live == true) && (b.liveBudget > 0.0) && config.hasT212Credentials,
            allows = { allow.isNotEmpty() && it.uppercase() in allow },
            perTrade = per)
    }

    fun mirrorEntry(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade,
                    space: String = "US", ns: String = "") {
        // Each decline is RECORDED, not just logged. A paper position with no
        // broker order behind it is indistinguishable between these cases from
        // the card, and they need opposite responses — lift a switch, arm an
        // algo, widen an allowlist, or simply wait for the next bar.
        val config = ConfigManager(ctx)
        val lc = liveCfgFor(ctx, config, ns)
        if (!lc.enabled) {
            // NAMES THE ACTUAL REASON. The old message could blame a Settings
            // switch that the algo path never consulted, sending the user to a
            // control that would not have changed anything.
            com.signalscope.app.data.LiveSkipStore.note(ctx, ns, paperTradeId, trade.symbol,
                if (!config.hasT212Credentials) "no Trading 212 credentials"
                else if (!Spaces.isBucket(ns)) "this space has no algo — real orders come from an algo only"
                else "this algo is not armed for real money, or its budget is 0")
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
        // The amount is a budget in the account's currency; the price is in the
        // instrument's. Converted here only to estimate the share count honestly —
        // the row keeps the euro figure. See [FxRates].
        val qty = BigDecimal(FxRates.orderCash(ctx, amount, ns) / price)
            .setScale(5, RoundingMode.DOWN).toDouble()
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
                    // €200 A SEAT MEANS €200. The budget is in the account's
                    // currency and the ladder sizes in the instrument's, so a US
                    // order used to go out as $200 — about €176. Converted here, at
                    // the moment of the order, and nowhere else. See [FxRates].
                    val orderCash = FxRates.orderCash(ctx, total, head.ns)
                    val r = PatientExecutor.buy(config, ticker, head.yahooSym,
                        head.space, orderCash, head.price)
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
                        // KEEP IT, don't bury it. An entry the strategy asked for and
                        // the market would not give is not a decision that was
                        // reversed — it is one that has not happened yet. Marking it
                        // ERROR threw away the intent, so a signal blocked by a
                        // closed market was simply lost, silently, forever.
                        if (config.t212RetryUnfilledEntries) {
                            group.forEach { g ->
                                T212LiveStore.get(ctx, g.paperTradeId, g.ns)?.let { row ->
                                    T212LiveStore.upsert(ctx, row.copy(
                                        status = "RETRY", qty = 0.0, error = why,
                                        retryCount = 0,
                                        // A shut market cannot improve in a minute;
                                        // a ladder that just conceded its whole range
                                        // needs the price to move, not to be asked
                                        // again immediately.
                                        retryAfter = System.currentTimeMillis() +
                                            (if (marketWasShut) 15 * 60_000L else 5 * 60_000L)))
                                }
                                try { LabNotifier.t212EntryQueued(ctx, g.ns, g.sym, why) }
                                catch (_: Exception) {}
                            }
                            Log.i(TAG, "queued ${head.sym} for retry: $why")
                            return@execute
                        }
                        if (!marketWasShut) {
                            val algoNames = group.mapNotNull {
                                com.signalscope.app.data.BucketStore.get(ctx, it.ns)?.name
                            }.distinct().joinToString(" + ").ifBlank { "An algo" }
                            // The total is the BUDGET, in the account's currency —
                            // labelling it with the instrument's currency called
                            // €200 "$200" on every US failure.
                            LabNotifier.ladderUnfilled(ctx, algoNames, head.sym, total,
                                config.accountCurrency, why)
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
                        // SAY WHEN IT WORKED. Crypto has notified on real fills since
                        // it went live; this path only ever spoke on failure, so an
                        // order that spent real money in the background was silent
                        // while one that failed shouted. That teaches the user the app
                        // only speaks when something is wrong.
                        if (share > 0.0) {
                            try {
                                LabNotifier.t212Buy(ctx, g.ns, g.sym, share,
                                    share * (if (r.refPrice > 0.0) r.refPrice else g.price),
                                    if (g.space.equals("DE", true)) "EUR" else "USD")
                            } catch (_: Exception) {}
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

    /**
     * Re-attempt SELLS the paper book has already booked but the broker never
     * executed.
     *
     * THE STATE THIS REPAIRS: paper closes a trade, the ladder fails — a rate
     * limit, a shut market, a rejected order — and the live row stays OPEN. The
     * strategy has moved on and will never raise that exit again, because an exit
     * only fires once. So the position sits held, out of the book, managed by
     * nothing. That is the worst state in the whole system: real money exposed
     * while every screen says it is not.
     *
     * DETECTED, not remembered: a live row still OPEN whose paper trade is CLOSED
     * is exactly that mismatch, and it needs no flag to notice — which means it
     * also repairs positions stranded before this existed.
     *
     * UNCONDITIONAL, unlike the entry retry. That one is opt-in because chasing a
     * buy adds risk; this one REMOVES it, and the app's own rule everywhere else
     * is that an exit is never withheld — it fires on stale data, past the freeze,
     * and here past the user's preference too. Nobody wants to keep a position
     * because a retry setting was off.
     */
    /** How long a row may sit in EXITING before it is treated as abandoned.
     *
     *  EXITING means "a sell is in flight". That is true for seconds or minutes,
     *  never for hours — a patient ladder is bounded and reverts the row to OPEN
     *  whenever it fails. So a row still EXITING long afterwards is not a sale in
     *  progress, it is a sale whose owner died: the process was killed mid-ladder,
     *  and nothing inherited the row.
     *
     *  Generous, because the cost of adopting one too early is a duplicate sell
     *  attempt while a real one is still working. */
    private const val EXITING_ABANDONED_MS = 2 * 60 * 60 * 1000L

    fun retryPendingExits(ctx: Context) {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return
        val now = System.currentTimeMillis()
        // ── ADOPT ABANDONED EXITS FIRST ──────────────────────────────────────
        //
        // EXITING HAD NO OWNER. This sweep looked only at OPEN rows, and both exit
        // paths move a row to EXITING before selling — so a row that entered that
        // state and never came back was invisible to the one thing that could have
        // rescued it. Nothing retried it, nothing closed it, and the audit reported
        // it as a stuck position once an hour, for ever.
        //
        // Observed: a Continental row went EXITING on 8 September after its ladder
        // refused an after-hours sell, and was still there ten days later sending
        // an hourly ledger-mismatch alert about 0.698 shares the account no longer
        // held. The alert was correct that something was wrong; what was wrong was
        // that nothing could ever put it right.
        //
        // Returned to OPEN rather than closed: OPEN means "the shares are still
        // ours as far as we know", which is exactly the honest position when a
        // sale's outcome was never observed. The loop below then re-sells it if
        // paper has closed it, and the broker reconcile closes it if the shares
        // have in fact gone.
        // ── ONLY ROWS THAT COULD BE AT TRADING 212 IN THE FIRST PLACE ────────
        //
        // This sweep had no space filter, and the store is not clean: eight
        // ZERODHA positions written on 8 September are sitting in it OPEN, from a
        // writer bug whose fix did not retire the rows it had already made. The
        // audit already guards against exactly this and says why — "Zerodha
        // holdings can never appear in a Trading 212 account" — and this sweep
        // needed the same guard and did not have it.
        //
        // Without it the consequences are not cosmetic. The existing branch would
        // try to SELL an Indian stock through Trading 212 as though it were a US
        // listing; the reconciliation branch added above would compare it to a
        // Trading 212 portfolio that of course holds none of it and quietly close
        // eight real holdings. A filter that is merely tidy in one place is
        // load-bearing in another.
        fun t212Row(ns: String) = T212LiveStore.isT212Ns(ns)

        T212LiveStore.load(ctx).trades
            .filter { t212Row(it.ns) &&
                it.status == "EXITING" && now - maxOf(it.sellAt, it.buyAt) > EXITING_ABANDONED_MS }
            .forEach { row ->
                Log.w(TAG, "[${row.ns}] ${row.symbol} abandoned in EXITING for " +
                    "${(now - maxOf(row.sellAt, row.buyAt)) / 60_000} min — back to OPEN so it can be resolved")
                T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let {
                    T212LiveStore.upsert(ctx, it.copy(status = "OPEN",
                        error = "the previous exit never reported — picked up again"))
                }
            }
        val open = T212LiveStore.load(ctx).trades.filter {
            t212Row(it.ns) && it.status == "OPEN" && it.qty > 0.0 && it.retryAfter <= now
        }
        if (open.isEmpty()) return

        // WHAT THE BROKER ACTUALLY HOLDS, read once for the whole sweep.
        //
        // Null means UNREADABLE, which is emphatically not "you hold nothing" —
        // collapsing those two would close every row in the book on a single
        // rate-limited reply. Nothing below acts on a null.
        val heldQty: Map<String, Double>? = try {
            (BrokerHoldings.t212(ctx) as? BrokerHoldings.Result.Ok)
                ?.holdings?.associate { it.symbol.uppercase() to it.qty }
        } catch (e: Exception) { null }

        for (row in open) {
            // Is the paper side finished with it?
            val paperClosed = try {
                com.signalscope.app.data.StrategyLabStore.load(ctx, row.ns).trades
                    .firstOrNull { it.id == row.paperTradeId }?.status == "CLOSED"
            } catch (e: Exception) { false }
            if (!paperClosed) continue

            // ── ALREADY GONE? THEN THE ROW IS THE ONLY THING LEFT TO CLOSE ────
            //
            // A row can outlive its shares: sold by hand, sold by a sibling algo,
            // or sold by an exit whose reply was never seen. Re-selling then asks
            // the broker for stock that is not there, which fails for ever and
            // leaves the audit reporting a shortfall nothing can resolve — an
            // hourly alert about a position that stopped existing days ago.
            //
            // Closing it is bookkeeping, not a trade: nothing is bought or sold,
            // and the exposure it claimed was already untrue.
            if (heldQty != null && (heldQty[row.symbol.uppercase()] ?: 0.0) <= 0.0) {
                Log.w(TAG, "[${row.ns}] ${row.symbol} claimed ${row.qty} but the broker holds " +
                    "none — closing the row rather than re-selling stock that is gone")
                T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let {
                    T212LiveStore.upsert(ctx, it.copy(
                        status = "CLOSED",
                        sellAt = if (it.sellAt > 0L) it.sellAt else System.currentTimeMillis(),
                        error = "closed by reconciliation — the broker no longer held it, " +
                                "so the exit had already happened by some route"))
                }
                continue
            }

            Log.w(TAG, "[${row.ns}] ${row.symbol} still held but paper has closed it — re-selling")
            val out = stopOutNow(ctx, row.ns, row.symbol, "exit retry (paper already closed)",
                origin = "retry of an exit the broker had not completed")
            if (!out.ok) {
                val shut = out.error.contains("is closed", true) ||
                           out.error.contains("after hours", true)
                T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let {
                    T212LiveStore.upsert(ctx, it.copy(
                        retryAfter = System.currentTimeMillis() +
                            (if (shut) 15 * 60_000L else 3 * 60_000L),
                        error = out.error))
                }
                Log.w(TAG, "[${row.ns}] ${row.symbol} exit retry failed: ${out.error}")
            }
        }
    }

    /** How many times one queued entry may be re-attempted before it is given up.
     *
     *  COUNTS REAL ATTEMPTS ONLY. Waiting for an exchange to open, or for the
     *  fine-bar trend to turn, spends none of these — nothing was tried, so
     *  nothing was learned, and charging for it is what let a budget meant to
     *  bound a chase expire overnight instead. */
    private const val MAX_ENTRY_RETRIES = 24

    /** How long after the bell before a parked entry is looked at.
     *
     *  Long enough for the first 15-minute bar to have CLOSED, because the
     *  momentum gate has nothing to read until it has. Waking at the bell itself
     *  would only mean re-checking every few minutes against a bar still being
     *  formed, and the opening print is the least representative price of the day
     *  to be measuring a trend from. */
    private const val OPEN_SETTLE_MS = 16 * 60_000L

    /** How long to wait before asking again when the exchange has not yet
     *  published its next session — see the note at the parking branch. */
    private const val SESSION_POLL_MS = 90 * 60_000L

    /** One fine bar. The momentum gate cannot learn anything new until the next
     *  15-minute candle has closed, so re-asking sooner spends a fetch to be told
     *  the same thing. */
    private const val FINE_BAR_MS = 15 * 60_000L

    /** A gate flag changes only at the daily evaluation, so a held-back retry
     *  looks again hourly rather than every pass. */
    private const val GATE_FLAG_RECHECK_MS = 60 * 60_000L

    /** Has this algo's 3b gate flagged [symbol] exit-only? False when there is no
     *  armed gate, no seat for the name, or the gate cannot be read — a failed
     *  read must not quietly stop entries the strategy still wants. */
    internal fun gateFlagged(ctx: Context, ns: String, symbol: String): Boolean = try {
        val g = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
        g.enabled && g.slots.any { it.flagged && it.symbol.equals(symbol, ignoreCase = true) }
    } catch (e: Exception) { false }

    /** How far past its close a 15-minute bar stops being evidence about now.
     *  Two bars' worth: enough slack for the feed to publish late, far short of
     *  the overnight gap that would let last night's final bar answer a question
     *  about this morning. */
    private const val FINE_BAR_STALE_SEC = 30 * 60L

    /**
     * HOW LONG A QUEUED ENTRY MAY LIVE BEFORE IT IS ABANDONED.
     *
     * Parking fixed a budget that expired overnight; it also removed the only
     * bound on a queued entry's LIFE, because the attempt counter is the thing
     * that used to end one and waiting deliberately spends none. Without this a
     * decision can sit re-checking itself for weeks and then fill, on a morning
     * that has nothing to do with the one it was made for.
     *
     * Two sessions. Long enough to survive a weekend or a public holiday, which
     * is the case parking exists to serve; short enough that what fills is still
     * recognisably the trade that was chosen.
     *
     * NOW COUNTED IN WEEKDAY TIME, in [TradingClock]. As a flat 48 hours it did
     * not in fact survive a weekend: a Friday-evening signal expired on Sunday.
     */

    /**
     * The daily range, cached for the day.
     *
     * It is a fourteen-DAY average and cannot meaningfully change between two
     * checks a quarter of an hour apart, but it was being re-fetched on every
     * one of them — for every waiting name, on the morning the feed is busiest.
     * That traffic is what made the failed-quote path likely, and the failed
     * quote is what let a gap through. Cheap to fix at the source.
     */
    private val atrCache = HashMap<String, Pair<String, Double>>()

    @Synchronized
    private fun cachedAtr(ys: String, space: String, ref: Double): Double {
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        atrCache[ys]?.let { if (it.first == day) return it.second }
        val v = try { PatientExecutor.dailyAtr(ys, space, ref) } catch (e: Exception) { 0.0 }
        if (v > 0.0) atrCache[ys] = day to v
        return v
    }

    /** How long a dispatched retry is held off the due list while its ladder runs.
     *
     *  Must outlast BOTH a full ladder (about four minutes) and the gap between
     *  paper ticks — a lease that expires before the next sweep protects nothing
     *  on the pass that matters, which is the one arriving while the ladder is
     *  still working. Short enough that a process killed mid-order is picked back
     *  up the same session rather than abandoned, and the attempt cap still bounds
     *  the total either way. */
    private const val RETRY_CLAIM_MS = 25 * 60_000L

    /** How far above the signal price a chased entry may still be bought, as a
     *  FRACTION OF THE STOP DISTANCE. At 0.25 with a 4% stop, a queued buy is
     *  abandoned once price is more than 1% above the signal: past that the trade
     *  has given away a quarter of its risk budget before it even opens, and a
     *  signal-anchored stop cannot win it back. */
    private const val CHASE_CEILING_FRAC = 0.25

    data class StopOut(
        val ok: Boolean, val realPct: Double = 0.0, val exitPrice: Double = 0.0,
        val paidPrice: Double = 0.0, val qty: Double = 0.0, val error: String = ""
    )

    /**
     * Sell a live position NOW because the REAL stop fired — the config's stop
     * percentage measured from the price actually paid rather than from the signal
     * price the paper stop uses.
     *
     * Separate from [mirrorExit] because the trigger is different in kind: that one
     * mirrors a decision the engine made, this one acts on a decision the engine
     * cannot make, since the engine has never seen the fill price. It sells the
     * whole live quantity and returns the realised REAL return so the caller can
     * compound the seat by money that actually moved.
     *
     * @param origin which subsystem decided to sell, in words a reader would use.
     *   Four different ones call this and they mean genuinely different things —
     *   a hand-bought position's minder is not the same event as an algo's own
     *   fill-anchored stop — so the alert and the ledger both record which.
     */
    fun stopOutNow(ctx: Context, ns: String, symbol: String, reason: String,
                   origin: String = "", paperTradeId: String? = null): StopOut {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return StopOut(false, error = "no T212 credentials")
        // [paperTradeId] pins the exact position when the caller knows it — an algo
        // can hold the same name twice, and a hand action is about one of them.
        val row = T212LiveStore.load(ctx).trades.firstOrNull {
            it.ns == ns && it.symbol.equals(symbol, true) && it.status == "OPEN" && it.qty > 0.0 &&
                (paperTradeId == null || it.paperTradeId == paperTradeId)
        } ?: return StopOut(false, error = "no open live position")
        val paid = row.liveEntryPrice
        if (paid <= 0.0) return StopOut(false, error = "real fill price not settled — refusing to act on a guess")

        val space = if (Spaces.baseOf(ns) == "de") "DE" else "US"
        val ys = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(row.symbol, space)
        T212LiveStore.upsert(ctx, row.copy(status = "EXITING"))
        return try {
            val r = PatientExecutor.sell(config, row.t212Ticker, ys, space, row.qty, paid,
                targetQty = row.qty)
            if (!r.ok || r.filled <= 0.0) {
                val why = r.error ?: r.note.ifBlank { "exit ladder filled nothing" }
                T212LiveStore.upsert(ctx, row.copy(status = "OPEN", error = why))
                Log.w(TAG, "[$ns] REAL STOP ${row.symbol} could not sell: $why")
                return StopOut(false, error = why)
            }
            // sellAt STAMPED HERE, not left at 0. It was never written on this
            // path, so a position sold by the real stop had no record of WHEN —
            // which the watchlist book then copied as an exit time of zero, and
            // which the settled-alert window below needs in order to tell this
            // morning's sale from one closed months ago.
            T212LiveStore.upsert(ctx, row.copy(status = "CLOSED",
                sellOrderId = r.orderIds.firstOrNull() ?: 0L, sellOrderIds = r.orderIds,
                refExitPrice = r.refPrice, sweptQty = r.sweptQty, error = "",
                sellAt = System.currentTimeMillis(),
                exitOrigin = origin.ifBlank { null },
                exitReasonNote = reason.ifBlank { null }))
            // Learn the REAL exit price the same way the entry one is learned —
            // matched back from the broker's executed orders, never assumed. It
            // usually has nothing yet: T212 publishes an execution to the history
            // endpoint minutes after the order returns, so this call is worth
            // making but must not be relied on. Its own alerting is suppressed —
            // the notice for THIS sale is sent below, once, by the code that knows
            // what was being sold and why.
            try { reconcileFills(ctx, notifySettled = false) } catch (_: Exception) {}
            val after = T212LiveStore.get(ctx, row.paperTradeId, ns)
            // THE BROKER'S FILL OR NOTHING. `refPrice` is where the ladder RESTED,
            // not where it traded, and the difference is the entire quantity this
            // function exists to measure. It stays available for the seat maths
            // below, which needs some number, but it must never travel into an
            // alert as though it were money — when the fill is unknown the alert
            // says so and waits for [reconcileFills] to send the real one.
            val realPx = after?.liveExitPrice ?: 0.0
            val exitPx = realPx.takeIf { it > 0.0 } ?: r.refPrice
            val pct = if (paid > 0.0 && exitPx > 0.0) (exitPx / paid - 1.0) * 100.0 else 0.0
            try {
                LabNotifier.t212Sell(ctx, ns, row.symbol, r.filled,
                    if (space == "DE") "EUR" else "USD", reason,
                    tradeKey = row.paperTradeId, origin = origin,
                    paid = paid, realExit = realPx)
            } catch (_: Exception) {}
            Log.i(TAG, "[$ns] REAL STOP ${row.symbol} sold ${r.filled} @ $exitPx " +
                (if (realPx > 0.0) "(real fill)" else "(ladder ref — real fill not reported yet)") +
                " (paid $paid) → ${"%.2f".format(pct)}%")
            StopOut(true, pct, exitPx, paid, r.filled)
        } catch (e: Exception) {
            T212LiveStore.upsert(ctx, row.copy(status = "OPEN", error = e.message ?: "stop-out failed"))
            Log.e(TAG, "[$ns] REAL STOP ${row.symbol} threw", e)
            StopOut(false, error = e.message ?: "stop-out failed")
        }
    }

    /**
     * Re-attempt entries that were wanted but not filled.
     *
     * THE GUARD THAT MAKES THIS SAFE, and why it is not "retry until filled":
     *
     * Every attempt first re-checks that the entry signal STILL STANDS on the
     * newest closed bar. The moment it does not, the queued order is dropped.
     *
     * Without that check, retrying is adverse selection with extra steps. An order
     * fills easily when the market is willing and needs chasing when it is running
     * away — so "keep trying until it fills" completes exactly the orders that got
     * worse and abandons none of them. Worse, every exit rule in the config
     * measures from the SIGNAL price, not from the fill: the further above it the
     * eventual fill lands, the tighter the real stop becomes. A 4% stop filled 3%
     * high is a 1% stop. Chasing hardest precisely when price is escaping is how
     * that happens, and the backtest that justified the strategy assumed 0.20%
     * slippage, not an unbounded one.
     *
     * So: retry freely while the strategy would still enter here, stop the instant
     * it would not. That keeps the reason for the order alive rather than just the
     * order — and a signal that survives an hour of retries is a signal that is
     * genuinely still valid, which is the only case worth filling.
     *
     * Runs on the paper tick. No-op unless the user opted in.
     */
    /**
     * @param force   ignore the retry preference and the not-due-yet clock. Used
     *                only by a deliberate tap, never by the background sweep.
     * @param onlyId  restrict to one queued entry, so a tap on one row cannot
     *                quietly set off every other order the account has waiting.
     */
    /**
     * STOP TRYING UNTIL THE MARKET ACTUALLY OPENS.
     *
     * The opposite of the button beside it, and it exists because enabling
     * extended-hours entries made the quiet sessions expensive. Each pass that
     * clears the gates dispatches a ladder: six orders over about five minutes,
     * one of twenty-four attempts spent, and in a thin after-hours book it very
     * often buys nothing at all. Left alone overnight that can burn a queued
     * entry's whole attempt budget before the session that was actually worth
     * trading in ever arrives.
     *
     * THE ATTEMPT BUDGET IS NOT REFUNDED and the wait costs none of it — parking
     * spends nothing, exactly as waiting for a closed exchange always has. What
     * this changes is only WHEN the next look happens.
     *
     * THE REGULAR OPEN, NOT THE EXTENDED ONE. Somebody pressing this is saying
     * the thin sessions are not worth trading, so parking them until the
     * pre-market began would miss the point entirely.
     *
     * "Try it now" remains the way out: it clears the park and goes immediately,
     * so this is a pause rather than a commitment.
     */
    fun parkQueuedForOpen(ctx: Context, ns: String, paperTradeId: String): String {
        val row = T212LiveStore.get(ctx, paperTradeId, ns)
            ?: return "That entry is no longer in the queue."
        if (row.status != "RETRY")
            return "${row.symbol} is not waiting any more — it is ${row.status.lowercase()}."
        val space = if (Spaces.baseOf(row.ns) == "de") "DE" else "US"
        val ys = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(row.symbol, space)
        val session = try {
            com.signalscope.app.network.YahooFinanceClient
                .fetchTradingWindow(ys, includeExtended = false)
        } catch (e: Exception) { null }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        // A START ALREADY IN THE PAST MEANS "NOT PUBLISHED YET", not "this
        // morning" — Yahoo keeps naming the session that just ended until it
        // rolls forward some hours later. Adding a day to invent tomorrow is
        // wrong across exactly the weekend this will meet first, so ask again
        // later instead.
        val wake = if (session != null && session.start > nowSec)
            session.start * 1000L + OPEN_SETTLE_MS
        else nowMs + SESSION_POLL_MS
        T212LiveStore.upsert(ctx, row.copy(parkedForOpen = true, retryAfter = wake,
            error = "held until the market opens — you paused the retries"))
        Log.w(TAG, "[${row.ns}] ${row.symbol} parked until the open by request")
        return if (session != null && session.start > nowSec)
            "${row.symbol} will wait for the open. Nothing more will be tried until then, " +
            "and waiting costs none of its attempts."
        else
            "${row.symbol} will wait for the open. The next session time is not published " +
            "yet, so it will check again shortly and settle on the real one — nothing will " +
            "be tried in the meantime."
    }

    /**
     * TRY THIS QUEUED ENTRY RIGHT NOW, because somebody asked.
     *
     * The background sweep decides when to look again; this is the override for a
     * moment the sweep cannot know about — the user watching an extended-hours
     * book fill out, or simply wanting to know whether the broker will take the
     * order at all rather than waiting a quarter of an hour to find out.
     *
     * EVERY REAL GUARD STILL APPLIES. It goes through the same sweep, so the
     * attempt cap, the age cap, the still-valid-signal check, the session lookup
     * and the ladder's own caps are all exactly as they are unattended. The only
     * two things it skips are the clock and the preference — neither of which is
     * a safety rule.
     */
    fun retryQueuedNow(ctx: Context, ns: String, paperTradeId: String): String {
        val row = T212LiveStore.get(ctx, paperTradeId, ns)
            ?: return "That entry is no longer in the queue."
        if (row.status != "RETRY")
            return "${row.symbol} is not waiting any more — it is ${row.status.lowercase()}."
        // Clear the park so the sweep does not simply put it back to sleep on the
        // same reasoning that parked it in the first place.
        T212LiveStore.upsert(ctx, row.copy(retryAfter = 0L, parkedForOpen = false))
        // bypassTiming: the waiting gates are overridden, the price gates are not.
        //
        // A NOTE BACK FROM THE PASS BEATS RE-READING THE ROW. The row is shared
        // state and the background sweep writes it too, so reading it afterwards
        // can hand back a sentence some other pass wrote a second ago — which is
        // exactly how a running ladder came to be reported as "no attempt spent".
        val triesBefore = row.retryCount
        val passNote = retryPendingEntries(ctx, force = true, onlyId = paperTradeId,
            bypassTiming = true)
        if (passNote != null) return passNote
        val after = T212LiveStore.get(ctx, paperTradeId, ns)
        return when {
            after == null -> "${row.symbol}: the queued entry is gone."
            after.status == "OPEN" -> "${row.symbol} filled."
            after.status == "ERROR" -> "${row.symbol} was given up on: ${after.error}"
            // ── THE LADDER HAS STARTED, AND THAT IS ALL WE KNOW ────────────
            //
            // IT DOES NOT RUN HERE. The pass hands it to a single-threaded
            // executor and returns at once, so by the time this reads the row
            // the ladder has typically not placed its first order — and the
            // error still sitting there belongs to the PREVIOUS attempt.
            //
            // That is exactly what happened on PANW twice: six real orders
            // walking up the book while the screen quoted a stale message and
            // said the stock "was not bought". The attempt counter is the one
            // honest signal available at this instant — it is incremented as the
            // ladder is dispatched — so it decides what this says, and the
            // sentence promises nothing it cannot see.
            after.retryCount > triesBefore ->
                "${row.symbol}: the ladder is running now. It walks up to six price " +
                "steps over about five minutes, resting one order at a time and " +
                "cancelling each before the next. You will get a notification if it " +
                "fills; either way this row will show the result when it finishes."
            after.parkedForOpen -> "${row.symbol} is still parked — the venue is shut, " +
                "including its extended sessions."
            // No ladder was dispatched, so a gate stopped it before any order —
            // and the stored reason really is this attempt's.
            else -> "${row.symbol} was not attempted" +
                (if (after.error.isNotBlank()) ": ${after.error}" else "; it stays queued.")
        }
    }

    /**
     * SHARES THE LADDER BOUGHT AND THEN FORGOT ABOUT.
     *
     * A queued entry is abandoned when nothing appears to have filled. That
     * judgement comes from re-reading the portfolio at the end of the ladder, and
     * a fill that has not yet surfaced in that read counts as no fill — so the row
     * is written off with a quantity of zero while the account holds the stock.
     *
     * The result is the worst kind of position: real money, no record, and
     * therefore no stop, no exit rule and nothing watching it. It was found on
     * this account by the position audit — six orders placed for a €200 entry,
     * the row written off as unfilled, and €123 of the stock sitting in the
     * portfolio a week later with no owner.
     *
     * THE AUDIT COULD ALREADY SEE THIS AND NOTHING ACTED ON WHAT IT SAW. This is
     * that missing step: the row is reopened for the shares that genuinely
     * exist, and from there the orphan manager gives it the same exits any other
     * unclaimed live position gets.
     *
     * ── WHY IT CANNOT RUN AWAY WITH SOMEBODY ELSE'S STOCK ──────────────────
     * The account also holds positions the user bought themselves, and claiming
     * one of those would put an automatic stop under stock the app was never
     * asked to manage. Four guards, and all four must pass:
     *
     *   • only a row that ORDERED this name, recently, and recorded no fill
     *   • only the quantity nothing else already claims — other live rows and
     *     the watchlist book are both subtracted first
     *   • never more VALUE than the entry actually asked for, so a large
     *     pre-existing holding can never be swallowed by a small order
     *   • holdings unreadable means do nothing at all, as everywhere else
     */
    private const val ADOPT_WINDOW_MS = 7 * 24 * 60 * 60_000L

    fun adoptStrandedFills(ctx: Context): Int {
        val config = ConfigManager(ctx)
        if (!config.hasT212Credentials) return 0
        val now = System.currentTimeMillis()
        val all = try { T212LiveStore.load(ctx).trades } catch (e: Exception) { return 0 }
        val candidates = all.filter {
            T212LiveStore.isT212Ns(it.ns) && it.status == "ERROR" &&
                it.qty <= 0.0 && it.amount > 0.0 && it.buyAt > now - ADOPT_WINDOW_MS
        }
        if (candidates.isEmpty()) return 0

        // What every OTHER record already claims, so nothing is counted twice.
        val claimed = HashMap<String, Double>()
        for (t in all) {
            if (t.status == "OPEN" || t.status == "EXITING" || t.status == "PENDING")
                claimed[t.symbol.uppercase()] = (claimed[t.symbol.uppercase()] ?: 0.0) + t.qty
        }
        for (b in listOf("de", "us")) {
            try {
                com.signalscope.app.data.ManualWatchStore.load(ctx, b).entries
                    .filter { it.status == "OPEN" || it.status == "PENDING" }
                    .forEach {
                        val k = it.symbol.uppercase()
                        claimed[k] = (claimed[k] ?: 0.0) + it.filledQty
                    }
            } catch (e: Exception) {
                Log.w(TAG, "[$b] book unreadable — no adoption this pass"); return 0
            }
        }

        var adopted = 0
        for (base in listOf("de", "us")) {
            val mine = candidates.filter { Spaces.baseOf(it.ns) == base }
            if (mine.isEmpty()) continue
            val held = when (val r = BrokerHoldings.forSpace(ctx, base)) {
                is BrokerHoldings.Result.Ok -> r.holdings.filter { it.qty > 0.0 }
                    .associateBy { it.symbol.uppercase() }
                // UNREADABLE IS NOT EMPTY, here as everywhere. Adopting on a
                // failed read would be inventing a position from silence.
                is BrokerHoldings.Result.Unreadable -> {
                    Log.i(TAG, "[$base] holdings unreadable (${r.why}) — no adoption this pass")
                    continue
                }
            }
            for (row in mine) {
                val sym = row.symbol.uppercase()
                val h = held[sym] ?: continue
                val free = h.qty - (claimed[sym] ?: 0.0)
                if (free <= 0.0) continue
                val price = if (h.avgCost > 0.0) h.avgCost
                            else if (row.refEntryPrice > 0.0) row.refEntryPrice else h.price
                if (price <= 0.0) continue
                // NEVER MORE THAN THE ENTRY ASKED FOR. A little headroom for the
                // ladder's own ceiling, and nothing beyond it.
                val cap = (row.amount * 1.05) / price
                if (free > cap) {
                    Log.w(TAG, "[${row.ns}] $sym holds ${"%.4f".format(free)} unclaimed but the " +
                        "entry only asked for ${"%.4f".format(cap)} — left alone, it is not ours")
                    continue
                }
                val take = free
                T212LiveStore.upsert(ctx, row.copy(
                    status = "OPEN", qty = take,
                    liveEntryPrice = if (row.liveEntryPrice > 0.0) row.liveEntryPrice else price,
                    error = "",
                    adoptedNote = "the ladder filled this and recorded nothing — " +
                        "reopened from the broker's own position so it has a manager"))
                claimed[sym] = (claimed[sym] ?: 0.0) + take
                adopted++
                Log.w(TAG, "[${row.ns}] ADOPTED ${"%.4f".format(take)} $sym at " +
                    "${"%.4f".format(price)} — was written off as unfilled, the broker holds it")
                try {
                    LabNotifier.t212Buy(ctx, row.ns, sym, take, take * price,
                        if (base == "de") "EUR" else "USD")
                } catch (_: Exception) {}
            }
        }
        if (adopted > 0) Log.w(TAG, "adopted $adopted stranded fill(s)")
        return adopted
    }

    /**
     * ONLY ONE RETRY PASS MAY RUN AT A TIME, and this is not a tidiness rule.
     *
     * Adding a "Try it now" button put a second caller on a routine that had only
     * ever had one, and the two collided on the first real name: the tap ran the
     * ladder and placed orders while the background sweep, working the same row a
     * second later, hit the trend gate and wrote "waiting for the 15-minute trend
     * — no attempt spent" over the top of it. The user was shown that sentence
     * while limit orders walked up the book at the broker.
     *
     * Two paths writing one row is bad; two paths running LADDERS on one row is
     * worse, and nothing in the older design prevented it because nothing could
     * previously ask.
     */
    private val sweepBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun retryPendingEntries(ctx: Context, force: Boolean = false, onlyId: String? = null,
                            bypassTiming: Boolean = false): String? {
        if (!sweepBusy.compareAndSet(false, true)) {
            Log.i(TAG, "retry pass already running — skipped")
            // A tap deserves an answer; the timer does not.
            return if (force)
                "A pass is already running — a ladder can take a minute. Give it a moment."
            else null
        }
        try {
            return retryPendingEntriesLocked(ctx, force, onlyId, bypassTiming)
        } finally { sweepBusy.set(false) }
    }

    private fun retryPendingEntriesLocked(ctx: Context, force: Boolean, onlyId: String?,
                                          bypassTiming: Boolean): String? {
        val config = ConfigManager(ctx)
        // THE PREFERENCE GOVERNS THE SWEEP, NOT THE USER. Turning automatic
        // retries off is a statement about what should happen unattended; it is
        // not a refusal to act when somebody presses the button themselves.
        if (!force && !config.t212RetryUnfilledEntries) return null
        if (!config.hasT212Credentials) return "Trading 212 is not connected."
        val now = System.currentTimeMillis()
        val due = T212LiveStore.load(ctx).trades.filter {
            // Same guard as the exit sweep. Inert today only because the stray
            // Zerodha rows happen to be OPEN rather than RETRY — which is luck,
            // not design, and luck is not a filter.
            T212LiveStore.isT212Ns(it.ns) &&
                it.status == "RETRY" && (force || it.retryAfter <= now) &&
                (onlyId == null || it.paperTradeId == onlyId)
        }
        if (due.isEmpty())
            return if (onlyId != null) "That entry is no longer queued." else null
        // Names newly parked on this pass, so the digest can name them once rather
        // than sending one card per symbol per evening.
        val newlyParked = ArrayList<T212LiveStore.LiveTrade>()
        for (row in due) {
            // Give up on count before spending a fetch on it.
            if (row.retryCount >= MAX_ENTRY_RETRIES) {
                T212LiveStore.upsert(ctx, row.copy(status = "ERROR",
                    error = "gave up after ${row.retryCount} attempts — " + row.error))
                Log.w(TAG, "retry exhausted for ${row.symbol} (${row.ns})")
                continue
            }
            // ── AND GIVE UP ON AGE, which nothing else now does ─────────────────
            //
            // Waiting spends no attempt, deliberately — which means the attempt
            // counter, the only thing that ever ended a queued entry's life, can
            // no longer end one that is merely waiting. A name that opens soft
            // every morning would re-check itself every quarter hour indefinitely
            // and could still fill weeks later, on a morning with no relationship
            // to the decision that queued it.
            //
            // Checked before the exchange lookup so a stale row costs no fetch.
            //
            // WEEKDAY HOURS ONLY — see [TradingClock]. On the wall clock a Friday
            // evening signal died on Sunday night, before Monday's open.
            val queuedAt = row.buyAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            if (TradingClock.queueExpired(queuedAt, System.currentTimeMillis(), row.ns)) {
                val hrs = TradingClock.weekdayMillisBetween(queuedAt, System.currentTimeMillis(),
                    TradingClock.zoneFor(row.ns)) / 3_600_000L
                T212LiveStore.upsert(ctx, row.copy(status = "ERROR", parkedForOpen = false,
                    error = "waited ${hrs}h of weekday time without a tradeable moment — " +
                        "too old to still be the trade that was chosen, not bought"))
                Log.w(TAG, "[${row.ns}] ${row.symbol} queued entry expired after ${hrs}h of weekday time")
                continue
            }
            val space = if (Spaces.baseOf(row.ns) == "de") "DE" else "US"
            val ys = com.signalscope.app.network.YahooFinanceClient.toYahooSymbol(row.symbol, space)

            // ── IS THE MARKET EVEN OPEN? ────────────────────────────────────────
            //
            // ASKED FIRST, AND IT DOES NOT COST AN ATTEMPT. This is the whole of
            // the evening-signal bug. A 120-minute bar on a market that shuts at
            // 17:30 can only complete after the close, so every entry decided on
            // the day's last bar was born into a shut market — and each 15-minute
            // poke afterwards spent one of 24 attempts on a question the exchange
            // could not answer. Twenty-four of them ran out around midnight, nine
            // hours before Frankfurt opened, so the evening slot could not reach a
            // tradeable moment even in principle. Five signals, five deaths, zero
            // of them about the trade.
            //
            // A closed exchange is not evidence that an entry is unwilling. It is
            // no evidence at all, so it buys no information and is charged for
            // none: park until the open and leave the budget for real attempts.
            // RETRIES WAIT FOR THE REGULAR SESSION, US INCLUDED.
            //
            // US retries used to count the pre- and after-market as open. The
            // FIRST attempt still may (the ladder's own gate allows it, so an
            // evening signal is not sent to sleep untried) — but a retry exists
            // because that attempt did not fill, and the thin extended book is
            // where it failed. Re-running the ladder there every quarter hour was
            // pure churn: SMCI on 2 Oct walked six limit orders up and back out
            // every 15–20 minutes from 05:30 New York time, filled nothing, and
            // spent the broker's rate limit doing it.
            val session = try {
                com.signalscope.app.network.YahooFinanceClient
                    .fetchTradingWindow(ys, includeExtended = false)
            } catch (e: Exception) { null }
            val nowSec = System.currentTimeMillis() / 1000
            // UNKNOWN IS NOT CLOSED. When the lookup fails, fall through and let
            // the ladder's own market check decide — the same policy that check
            // already applies, and the safe one: refusing on a failed lookup would
            // turn a Yahoo outage into a silent trading halt.
            // A tap skips the park for the same reason it skips the trend gate —
            // it is a timing decision the user has just made themselves. The
            // broker still gets the final word: if the venue really is shut, the
            // ladder's own check refuses and the reason comes back verbatim,
            // which is a far better answer than silently sleeping.
            if (!bypassTiming && session != null && !session.isOpenAt(nowSec)) {
                // The window Yahoo reports is the CURRENT one, so straight after a
                // close it still names the session that just ended and only rolls
                // forward some hours later. A start already in the past therefore
                // means "not published yet", not "this morning" — ask again later
                // rather than inventing tomorrow by adding a day, which is wrong
                // across exactly the weekend this will meet first.
                val wake = if (session.start > nowSec) session.start * 1000L + OPEN_SETTLE_MS
                           else System.currentTimeMillis() + SESSION_POLL_MS
                T212LiveStore.upsert(ctx, row.copy(
                    status = "RETRY", parkedForOpen = true,
                    error = "waiting for the market to open — no attempt spent",
                    retryAfter = wake))
                if (!row.parkedForOpen) newlyParked.add(row)
                Log.i(TAG, "[${row.ns}] ${row.symbol} parked for the open " +
                    "(wake ${java.util.Date(wake)}), attempt budget untouched")
                continue
            }

            // ── IS THE STRATEGY STILL IN THIS TRADE? ────────────────────────────
            //
            // The test used to be "is the entry still on the NEWEST bar", which
            // ended every queued buy the moment the algo's next bar was judged —
            // whether or not the strategy had left the trade. For a German signal
            // on the evening bar that left about an hour the next morning, and
            // three looks at the 15-minute candle, before a position the strategy
            // was still happily holding was written off.
            //
            // So the question is now the one that matters: does paper still hold
            // it? While it does, the real order is still the same trade and is
            // worth completing. The moment paper exits — or the position is reset,
            // or sold by hand — the trade no longer exists and neither does the
            // reason to buy it. The passes judge bars BEFORE retries run, so an exit
            // on this very pass is already in the ledger when this reads it.
            //
            // What the old rule protected against is still covered, by the chase
            // ceiling below: a fill more than a quarter of the stop distance above
            // the signal is refused, so a late fill can narrow a 4% stop to 3% at
            // worst — never quietly to 1%.
            //
            // Kept AHEAD of the price and momentum gates. Those can hold an entry
            // waiting for hours, and a trade paper has already closed must not sit
            // behind them being re-examined every quarter hour.
            val paperHolds: Boolean? = try {
                paperStillHolds(com.signalscope.app.data.StrategyLabStore.load(ctx, row.ns).trades,
                    row.paperTradeId)
            } catch (e: Exception) {
                Log.w(TAG, "paper ledger unreadable for ${row.symbol}", e); null
            }
            if (paperHolds == null) {
                // NOT KNOWING IS NOT AN EXIT. Wait, and spend nothing on it.
                T212LiveStore.upsert(ctx, row.copy(
                    status = "RETRY", parkedForOpen = false,
                    error = "the strategy's book could not be read — waiting, no attempt spent",
                    retryAfter = System.currentTimeMillis() + FINE_BAR_MS))
                continue
            }
            if (!paperHolds) {
                T212LiveStore.upsert(ctx, row.copy(status = "ERROR", parkedForOpen = false,
                    error = "the strategy is no longer in this trade on paper (it exited, or the " +
                        "position was reset or sold by hand) — not bought"))
                Log.i(TAG, "retry dropped for ${row.symbol}: paper no longer holds ${row.paperTradeId}")
                continue
            }
            // ── NOT WHILE THE GATE SAYS "EXIT ONLY" ─────────────────────────────
            //
            // A 3b gate flags a held name when its daily MACD slope turns down: the
            // position rides to its own exit, and nothing new is opened in it. A
            // retry is opening something new — real money into a name the strategy
            // has already decided to wind down. Held back, at no cost in attempts;
            // if the slope recovers the flag clears and the retry resumes, and if
            // it does not, the queue's own age limit ends it.
            if (gateFlagged(ctx, row.ns, row.symbol)) {
                T212LiveStore.upsert(ctx, row.copy(
                    status = "RETRY", parkedForOpen = false,
                    error = "held back: the 3b gate has flagged this name exit-only (daily MACD " +
                        "falling) — no new money goes in while it is; no attempt spent",
                    retryAfter = System.currentTimeMillis() + GATE_FLAG_RECHECK_MS))
                Log.i(TAG, "[${row.ns}] ${row.symbol} retry held — gate flagged exit-only")
                continue
            }

            // ── THE CHASE CEILING, MEASURED AGAINST A LIVE PRICE ────────────────
            //
            // A still-valid signal is not on its own a licence to buy at any price.
            // Filling above the signal gives away that much of the trade up front,
            // and a stop measured from the signal never gives it back.
            //
            // IT USED TO READ THE LAST COMPLETED BAR, AND THAT IS BLIND AT EXACTLY
            // THE MOMENT THIS MATTERS. Waking at the open on a 120-minute frame,
            // no bar of the new day has closed — the newest completed one is still
            // last night's signal bar. So the test compared the signal price with
            // itself, saw no movement, and waved through a name that had gapped
            // six percent overnight. The blindness lasted until the first bar of
            // the day closed, two hours after the open, which is the entire window
            // a parked entry now wakes into. GXI was caught only because it drifted
            // late enough to be judged after that; it was saved by being slow.
            //
            // TWO LIMITS, WHICHEVER BINDS FIRST. The stop fraction scales with the
            // config's own risk appetite — a 4% stop tolerates more drift than a 1%
            // one, and should. Half an ATR scales with how much the name actually
            // moves. They answer different questions, both are grounds to refuse,
            // and taking the tighter is the conservative reading of a price that
            // has already left where the strategy chose it.
            // ── DON'T BUY INTO A FALLING OPEN ───────────────────────────────────
            //
            // The ceiling above refuses a name that has run too far UP. This is the
            // other half: a name opening downward is not a bargain, it is a move in
            // progress, and the patient ladder has no view on which. Waiting for the
            // most recent quarter hour to have closed higher than it began costs
            // nothing when the open is strong and avoids catching the knife when it
            // is not.
            //
            // FIFTEEN MINUTES BECAUSE NOTHING SHORTER HAS CLOSED. At the open the
            // day's first bar on any frame this app trades is still forming, so
            // "buy as soon as possible" and "confirm the direction first" cannot
            // both be satisfied before 09:15 — this takes the earliest completed
            // evidence there is and re-asks every quarter hour until it turns.
            // Waiting spends no attempt, for the same reason a shut market spends
            // none: nothing was tried.
            // CHEAPEST GATE FIRST. This was below the price checks, so a name
            // about to be refused for falling still paid for a daily-range fetch
            // and a live quote first — on the busiest minutes of the day, for
            // every waiting name, every quarter hour. The traffic was not free:
            // it made the failed quote likely, and a failed quote used to be
            // read as permission.
            // A TAP OVERRIDES THE WAITING, NOT THE LIMITS.
            //
            // This gate and the session park are about TIMING — they hold a valid
            // trade until a better moment. Pressing "Try it now" IS the user
            // choosing the moment, so continuing to wait for the app's own idea of
            // one makes the button a no-op and reads as broken. That is exactly
            // what it did on PANW: the order never went in, and the only sign was
            // a log line nobody sees.
            //
            // The PRICE gates below are not skipped and must never be. They are
            // not about when to buy, they are about what this entry is allowed to
            // pay — which is part of the trade's definition, not of its timing.
            if (!bypassTiming && !risingOnFineBars(row.symbol, space)) {
                T212LiveStore.upsert(ctx, row.copy(
                    status = "RETRY", parkedForOpen = false,
                    error = "waiting for the 15-minute trend to turn up — no attempt spent",
                    retryAfter = System.currentTimeMillis() + FINE_BAR_MS))
                Log.i(TAG, "[${row.ns}] ${row.symbol} held: last 15-minute bar not rising")
                continue
            }

            // ── THE PRICE GATES, NOW THAT THE TRADE IS OTHERWISE READY ──────────
            val cfg = com.signalscope.app.data.StrategyLabStore.activeConfig(ctx, row.ns)
            val atr = if (row.paperEntryPrice > 0.0) cachedAtr(ys, space, row.paperEntryPrice) else 0.0
            val ceiling = entryCeiling(row.paperEntryPrice, cfg.exit.stopLossPct,
                cfg.exit.stopLossEnabled, atr)
            val floor = entryFloor(row.paperEntryPrice, cfg.exit.stopLossPct,
                cfg.exit.stopLossEnabled, atr)
            // The price the LADDER would trade against, from the ladder's own
            // source — a gate that approves on one quote while the order fills off
            // another is not a gate.
            //
            // AND IT MUST BE ABLE TO SAY IT DOES NOT KNOW. The old call fell back
            // to the last completed bar, which at an open IS the signal price, so
            // a failed fetch compared the signal with itself and approved. Waiting
            // costs nothing here — no attempt is spent — so an unknown price waits
            // rather than proceeding on a number that is not a quote.
            val live = if (ceiling > 0.0 || floor > 0.0)
                PatientExecutor.currentRefOrNull(config, row.t212Ticker, ys, space) else null
            if ((ceiling > 0.0 || floor > 0.0) && live == null) {
                T212LiveStore.upsert(ctx, row.copy(
                    status = "RETRY", parkedForOpen = false,
                    error = "no live price to check the limits against — waiting, no attempt spent",
                    retryAfter = System.currentTimeMillis() + FINE_BAR_MS))
                Log.w(TAG, "[${row.ns}] ${row.symbol} held: live price unavailable, not proceeding blind")
                continue
            }
            if (live != null && ceiling > 0.0 && live > ceiling) {
                T212LiveStore.upsert(ctx, row.copy(status = "ERROR", parkedForOpen = false,
                    error = "price ran ${"%.2f".format((live / row.paperEntryPrice - 1) * 100)}% " +
                        "above the signal before this could fill (limit " +
                        "${"%.2f".format(ceiling)}) — beyond the chase ceiling, not bought"))
                Log.i(TAG, "[${row.ns}] ${row.symbol} dropped: live $live above ceiling $ceiling")
                continue
            }
            // The floor. Below where this trade's own stop would sit, the strategy
            // would already be out of it — so there is no version of this entry
            // left to take, however valid the setup still reads.
            if (live != null && floor > 0.0 && live <= floor) {
                T212LiveStore.upsert(ctx, row.copy(status = "ERROR", parkedForOpen = false,
                    error = "price fell ${"%.2f".format((1 - live / row.paperEntryPrice) * 100)}% " +
                        "below the signal, through where this trade's stop would sit " +
                        "(${"%.2f".format(floor)}) — the strategy would already be out, not bought"))
                Log.i(TAG, "[${row.ns}] ${row.symbol} dropped: live $live below floor $floor")
                continue
            }
            // CLAIM THE ROW BEFORE DISPATCHING IT.
            //
            // The ladder runs asynchronously and can take minutes. Bumping only the
            // attempt count left the row still RETRY with its due-time already in
            // the past, so the NEXT paper tick selected the very same row and
            // launched a second ladder against it — two real buy orders for one
            // decision, which is the failure this whole retry path exists to avoid
            // creating.
            //
            // Pushing the due-time out is the claim. Deliberately not a status
            // change to something in-flight: if the process dies mid-ladder a
            // status claim would strand the row for good, whereas a time claim
            // simply becomes due again and retries — still capped by the attempt
            // count, so it cannot loop forever either.
            T212LiveStore.upsert(ctx, row.copy(
                retryCount = row.retryCount + 1, parkedForOpen = false,
                retryAfter = System.currentTimeMillis() + RETRY_CLAIM_MS))
            ladder.execute {
                try {
                    // The ceiling travels INTO the ladder, not just in front of it.
                    // Clearing the gate on the opening quote does not stop a chase
                    // walking half an ATR upward while it runs — back through the
                    // very limit that approved it a minute earlier.
                    val r = PatientExecutor.buy(config, row.t212Ticker, ys, space,
                        FxRates.orderCash(ctx, row.amount, row.ns),
                        if (row.paperEntryPrice > 0.0) row.paperEntryPrice else 0.0,
                        maxPrice = ceiling)
                    val cur = T212LiveStore.get(ctx, row.paperTradeId, row.ns) ?: return@execute
                    if (r.ok && r.filled > 0.0) {
                        T212LiveStore.upsert(ctx, cur.copy(status = "OPEN", qty = r.filled,
                            error = "", buyOrderId = r.orderIds.firstOrNull() ?: 0L,
                            buyOrderIds = r.orderIds, refEntryPrice = r.refPrice))
                        try {
                            LabNotifier.t212Buy(ctx, row.ns, row.symbol, r.filled,
                                r.filled * (if (r.refPrice > 0.0) r.refPrice else row.paperEntryPrice),
                                if (space == "DE") "EUR" else "USD")
                        } catch (_: Exception) {}
                        Log.i(TAG, "retry FILLED ${row.symbol} on attempt ${cur.retryCount}")
                    } else {
                        val why = r.error ?: r.note.ifBlank { "ladder filled nothing" }
                        val shut = why.contains("is closed", true) || why.contains("after hours", true)
                        T212LiveStore.upsert(ctx, cur.copy(status = "RETRY", qty = 0.0, error = why,
                            retryAfter = System.currentTimeMillis() +
                                (if (shut) 15 * 60_000L else 5 * 60_000L)))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "retry threw for ${row.symbol}", e)
                    T212LiveStore.get(ctx, row.paperTradeId, row.ns)?.let {
                        T212LiveStore.upsert(ctx, it.copy(status = "RETRY",
                            error = e.message ?: "retry failed",
                            retryAfter = System.currentTimeMillis() + 10 * 60_000L))
                    }
                }
            }
        }
        if (newlyParked.isNotEmpty()) announceParked(ctx, newlyParked)
        // Null means "the pass ran normally" — the caller then reads the row for
        // the detail, which is safe once only one pass can be in flight.
        return null
    }

    /**
     * Tell the user what is queued for tomorrow morning.
     *
     * A decision made after the close used to be completely silent: the entry was
     * kept, retried into a shut market all evening, and expired overnight without
     * anything ever being said. The first and only sign was its absence from the
     * book the next day.
     *
     * ONE CARD PER SPACE, LISTING THE NAMES — not one per symbol. An evening that
     * parks four names should not produce four notifications; the useful question
     * is "what is lined up for the morning", which is a list, and a stable id per
     * space means a second name parked ten minutes later REPLACES the card rather
     * than stacking beside it.
     *
     * It deliberately does not promise a purchase. Each of these still has to pass
     * the setup check, the chase ceiling and the momentum gate at the open, and
     * saying otherwise would turn a heads-up into a claim the app cannot keep.
     */
    private fun announceParked(ctx: Context, parked: List<T212LiveStore.LiveTrade>) {
        parked.groupBy { Spaces.baseOf(it.ns) }.forEach { (base, rows) ->
            try {
                val names = rows.map { it.symbol.uppercase() }.distinct().sorted()
                LabNotifier.entriesParkedForOpen(ctx, base, names)
            } catch (e: Exception) { Log.w(TAG, "parked digest failed for $base", e) }
        }
    }

    /**
     * The most this entry may pay per share, measured from the SIGNAL price.
     *
     * ANCHORED TO THE SIGNAL, WHICH IS THE ENTIRE POINT. The ladder has its own
     * cap of "fresh quote plus half an ATR", and that one is anchored to wherever
     * the market currently is — correct for chasing a price that moves during a
     * session, and worthless against a gap, because the anchor gaps with it. A
     * name that opens six percent above its signal is bought six percent high by
     * a cap behaving exactly as designed. Only a limit measured from where the
     * strategy actually made its decision can refuse that.
     *
     * TWO LIMITS, WHICHEVER IS TIGHTER:
     *
     *   • a share of the STOP distance — scales with the config's own risk
     *     appetite, since a 4% stop can absorb more drift than a 1% one, and
     *     because every exit measures from the signal, drift consumed on entry is
     *     never given back;
     *   • half an ATR — scales with how far the name actually travels in a day,
     *     so a placid stock is held to a tighter line than a volatile one.
     *
     * They answer different questions and either is sufficient grounds to refuse,
     * so the smaller wins. On a 100.00 signal with a 4% stop and a 1.50 ATR that
     * is 100.75: the stop fraction would allow 101.00, the ATR half allows 100.75,
     * and the trade must clear both.
     *
     * @return 0.0 when no limit can be established — no signal price, no stop and
     *   no ATR. Callers treat 0.0 as "unlimited" and fall back to the ladder's own
     *   cap, which is the pre-existing behaviour rather than a new risk.
     */
    /**
     * Does the strategy still hold the paper trade a queued real order belongs to?
     *
     * Matched on the trade's own id (symbol plus entry bar), never on the symbol
     * alone: after an exit and a fresh entry on the same name, the new trade has
     * its own order and the old queued one must not ride on it.
     */
    internal fun paperStillHolds(trades: List<com.signalscope.app.data.StrategyLabStore.LedgerTrade>,
                                 paperTradeId: String): Boolean =
        trades.any { it.id == paperTradeId && it.status == "OPEN" }

    internal fun entryCeiling(signalPrice: Double, stopPct: Double,
                              stopEnabled: Boolean, atr: Double): Double {
        if (signalPrice <= 0.0) return 0.0
        val byStop = if (stopEnabled && stopPct > 0.0)
            signalPrice * (1 + (stopPct * CHASE_CEILING_FRAC) / 100.0) else Double.MAX_VALUE
        val byAtr = if (atr > 0.0) signalPrice + atr / 2.0 else Double.MAX_VALUE
        val c = minOf(byStop, byAtr)
        return if (c == Double.MAX_VALUE) 0.0 else c
    }

    /**
     * THE OTHER END, and it is not the ceiling mirrored.
     *
     * A fill BELOW the signal is a better price, so refusing one symmetrically
     * would throw away good entries for no reason. There is exactly one level
     * where a lower price stops being a discount and becomes a different trade:
     * where this position's own STOP would sit.
     *
     * Below that, the strategy would already have exited — it would never be
     * holding this name at this price. Buying there hands you a position whose
     * every rule is anchored to a number the market left behind: the exit
     * measures from the signal, so the trade opens showing a profit that never
     * happened, and the stop it is supposed to obey sits ABOVE what you paid,
     * protecting nothing.
     *
     * Without a stop configured there is no such level, so two daily ranges is
     * used instead — far enough not to catch ordinary drift, close enough that
     * a week of decline does not end in a purchase.
     *
     * Returns 0.0 when neither is available, meaning no floor.
     */
    internal fun entryFloor(signalPrice: Double, stopPct: Double,
                            stopEnabled: Boolean, atr: Double): Double {
        if (signalPrice <= 0.0) return 0.0
        if (stopEnabled && stopPct > 0.0) return signalPrice * (1 - stopPct / 100.0)
        if (atr > 0.0) return (signalPrice - 2.0 * atr).coerceAtLeast(0.0)
        return 0.0
    }

    /**
     * Has price turned UP on the finest frame this app fetches?
     *
     * The newest COMPLETED 15-minute bar must have closed above where it opened.
     * One bar rather than a slope across several, because at an open there is only
     * ever one to have: the second does not exist until 09:30, and a rule that
     * cannot be evaluated when it is needed is not a rule. Close-versus-open over
     * the last quarter hour is a direct reading of which way the name is moving
     * right now, which is the question being asked.
     *
     * A FAILED FETCH IS NOT A FALLING MARKET. Returning false on an outage would
     * hold every queued entry indefinitely and look identical to a soft open, so
     * an unusable answer defers to the gates that already ran — the setup is
     * still valid and the price is under its ceiling, which is enough to proceed.
     */
    private fun risingOnFineBars(symbol: String, space: String): Boolean {
        return try {
            // Exchange tag, not the two-letter space: the fetcher builds the Yahoo
            // suffix from it, and "US" is not one it knows.
            val exch = if (space.equals("DE", true)) "DE" else "NASDAQ"
            // EXTENDED BARS FOR US NAMES, because the venue trades then and the
            // entry gate above now allows it. Germany has no such session, so
            // asking for one there changes nothing.
            val res = com.signalscope.app.network.YahooFinanceClient
                .fetchCandles15m(symbol.trim(), exch, includePrePost = !space.equals("DE", true))
            val cs = (res as? com.signalscope.app.network.YahooFinanceClient.CandleResult.Success)
                ?.candles ?: return true
            // A bar Yahoo is still filling in has no close worth reading; require
            // the window to have elapsed before trusting the last one.
            val nowSec = System.currentTimeMillis() / 1000
            // SAME FEED DELAY, ONE FRAME DOWN. A German 15-minute candle "closed"
            // by the clock holds only its first minute or so of prices; judging
            // it green or red then is judging a candle that has barely started.
            // Waking at the open this was worse still: the first candle of the
            // day was read with almost nothing in it. See [FeedDelay].
            val lag = if (space.equals("DE", true)) FeedDelay.XETRA_SEC else 0L
            val done = cs.filter { FeedDelay.isComplete(it.timestamp, 15 * 60L, nowSec, 0L, lag) }
            val last = done.lastOrNull() ?: return false   // nothing closed yet — wait
            // AND IT MUST BE FROM TODAY'S SESSION, NOT LAST NIGHT'S.
            //
            // The same stale-bar trap this whole change exists to close, one frame
            // down. Waking a few minutes after the bell, today's first 15-minute
            // bar may not have been published yet — and the newest COMPLETED bar in
            // the series is then yesterday's 17:15 one. Reading that would answer
            // "is the name rising this morning?" with how it finished last night,
            // which is precisely the substitution that let a gap through upstairs.
            //
            // Anything older than a couple of bars is not evidence about now, so
            // wait for a real one rather than accept a stale one.
            if (nowSec - (last.timestamp + 15 * 60 + lag) > FINE_BAR_STALE_SEC) {
                Log.i(TAG, "$symbol fine-bar trend: newest closed bar is stale — waiting")
                return false
            }
            if (last.open <= 0.0 || last.close <= 0.0) return true
            last.close > last.open
        } catch (e: Exception) {
            Log.w(TAG, "fine-bar trend check failed for $symbol", e); true
        }
    }

    // NOTE: exits are deliberately NOT gated by LIVE_FROZEN — an exit only acts
    // on an already-open live position (risk-reducing), and with entries frozen
    // no new positions can appear. Blocking exits would strand a legacy position.
    fun mirrorExit(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade,
                   space: String = "US", ns: String = "") {
        val config = ConfigManager(ctx)
        // NO GATE ON THE WAY OUT — deliberately, and this line used to be one.
        //
        // A switch that blocks selling does not prevent risk, it traps it: the
        // position is already open and real, and refusing to mirror its exit
        // leaves it running with no manager while the paper side records it as
        // closed. Whatever the entry rules are, closing must always be possible.
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
                    sweptQty = r.sweptQty, error = "",
                    exitReasonNote = trade.exitReason.ifBlank { null }))
                // A real sale is the half the user most needs told about — it is the
                // moment money comes back and the position stops being at risk.
                //
                // NO PRICE IS CLAIMED HERE. Nothing on this path has seen a fill:
                // the ladder reports where it RESTED, and the previous version
                // multiplied that by the quantity and published it as the cash
                // received — or, when the ladder had no reference either, as a
                // confident "for 0.00 EUR". The real figure follows from
                // [reconcileFills] on the next pass and replaces this card.
                try {
                    LabNotifier.t212Sell(ctx, ns, live.symbol, r.filled,
                        if (space.equals("DE", true)) "EUR" else "USD", trade.exitReason,
                        tradeKey = paperTradeId, paid = live.liveEntryPrice)
                } catch (_: Exception) {}
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
