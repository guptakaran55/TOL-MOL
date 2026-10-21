package com.signalscope.app.trading

import android.content.Context
import android.util.Log
import com.signalscope.app.data.BinanceLiveStore
import com.signalscope.app.data.ConfigManager
import com.signalscope.app.network.BinanceClient
import java.math.BigDecimal

/**
 * Mirrors Binance Strategy Lab PAPER decisions into live Binance Spot orders.
 *
 * Important boundary: this class does not generate signals. It only consumes a
 * paper Trade that StrategyLabRunner has already accepted as NEW past the
 * watermark. Paper continues regardless; live results are persisted separately.
 */
object BinanceLiveMirror {
    private const val TAG = "BinanceLiveMirror"

    /**
     * Per-namespace live config. The bare "binance" ns uses the global
     * ConfigManager (the existing single live path, unchanged). A crypto BUCKET
     * uses ITS OWN live toggle / allowlist / budget, so N strategies fund and
     * gate real orders independently. Allowlist semantics differ deliberately:
     * bare-path empty = "all enrolled" (legacy); a bucket's empty allowlist =
     * "NONE" (real money is strictly opt-in per bucket).
     */
    private class LiveCfg(
        val enabled: Boolean,
        val allows: (String) -> Boolean,
        val quoteSize: (String) -> Double
    )

    private fun liveCfgFor(ctx: Context, config: ConfigManager, ns: String): LiveCfg {
        // ONE ROAD TO REAL MONEY, and it runs through the algo.
        //
        // The legacy bare crypto space is retired for ENTRIES. It used to be armed
        // by a switch in Settings, which was the whole problem: that switch looked
        // like a master control and had not been in the circuit since algos gained
        // their own, so it read OFF while real orders were being placed. A control
        // that lies about whether money can move is worse than no control.
        //
        // Exits are NOT routed through here and are deliberately never gated —
        // see mirrorExit. Retiring an entry path must never strand a position that
        // path already opened.
        if (!Spaces.isBucket(ns)) return LiveCfg(
            enabled = false,
            allows = { false },
            quoteSize = { 0.0 })
        val b = com.signalscope.app.data.BucketStore.get(ctx, ns)
        val allow = (b?.liveAllowlist ?: emptyList()).map { it.uppercase() }.toSet()
        // ── ONE ORDER'S SIZE = BUDGET ÷ SEATS, not ÷ symbols ────────────────
        //
        // Crypto divided by the SYMBOL COUNT while stocks and the gate both divide
        // by the SEAT COUNT. With ranking off the two agree, so the difference hid
        // — until the universe was trimmed from two coins to one and the size per
        // order silently doubled, from 200 to 500, with nothing changed by hand.
        // The same split everywhere means the number can be predicted from the
        // card rather than discovered from a fill.
        //
        // Seats also govern how much is at risk AT ONCE: one seat means one order
        // may commit the whole budget, which is correct and worth knowing.
        //
        // WITHOUT THE GATE, ONE SEAT PER COIN. A Manual algo has no gate, but its
        // never-armed gate state still says "rank, hold 5" — so eight coins each
        // ordered a fifth of the budget, 1.6 times the money allocated.
        val gate = com.signalscope.app.data.MacdGateStore.load(ctx, ns)
        val seats = if (b == null) 1
            else if (!gate.enabled) b.symbols.size.coerceAtLeast(1)
            else com.signalscope.app.data.MacdGateStore.effectiveHoldN(gate, b.symbols.size.coerceAtLeast(1))
        val per = if (b != null && b.liveBudget > 0.0)
            b.liveBudget / seats.coerceAtLeast(1) else 0.0
        return LiveCfg(
            // BUDGET IS PART OF THE GATE, not just the size. Without this an algo
            // armed with a zero budget still bought, because the order size is
            // floored at the exchange minimum further down — so "no money
            // allocated" quietly became "spend the smallest amount possible".
            enabled = (b?.live == true) && (b.liveBudget > 0.0) && config.hasBinanceCredentials,
            allows = { allow.isNotEmpty() && it.uppercase() in allow },
            quoteSize = { per })
    }

    fun mirrorEntry(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade, ns: String = "binance") {
        val config = ConfigManager(ctx)
        val lc = liveCfgFor(ctx, config, ns)
        if (!lc.enabled) return
        val sym = trade.symbol.uppercase()
        // Real money is opt-IN per symbol. A paper entry on a researched-but-not
        // funded coin must never reach the exchange. Skipped silently (no error
        // record) — this is normal, expected operation, not a failure.
        if (!lc.allows(sym)) {
            Log.i(TAG, "[$ns] live mirror SKIPPED $sym — not in live allowlist (paper unaffected)")
            return
        }
        if (!config.hasBinanceCredentials) {
            BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Binance credentials missing", ns)
            return
        }
        val quoteAsset = BinanceClient.quoteAssetOf(sym)
        if (quoteAsset == null) {
            BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs,
                "Live mirror supports ${BinanceClient.SUPPORTED_QUOTES.joinToString("/")} spot pairs only", ns)
            return
        }
        if (BinanceLiveStore.get(ctx, paperTradeId, ns) != null) return

        val quoteSize = lc.quoteSize(sym).coerceIn(5.0, 10_000.0)
        val account = when (val r = BinanceClient.fetchAccount(config)) {
            is BinanceClient.BinanceResult.Success -> r.data
            is BinanceClient.BinanceResult.Failure -> {
                BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, r.message, ns)
                return
            }
            is BinanceClient.BinanceResult.NotConfigured -> {
                BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Binance credentials missing", ns)
                return
            }
        }
        if (!account.canTrade) {
            BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Binance account/API key cannot trade", ns)
            return
        }
        val quoteFree = account.balances[quoteAsset]?.free?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (quoteFree < BigDecimal.valueOf(quoteSize)) {
            BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Insufficient $quoteAsset free balance", ns)
            return
        }

        val clientOrderId = clientOrderId(paperTradeId, "B")
        when (val r = BinanceClient.placeMarketBuy(config, sym, quoteSize, clientOrderId)) {
            is BinanceClient.BinanceResult.Success ->
                recordFilledBuy(ctx, paperTradeId, sym, trade, quoteSize, r.data, ns)
            is BinanceClient.BinanceResult.Failure ->
                reconcileBuy(ctx, config, paperTradeId, sym, trade, quoteSize, clientOrderId, r.message, ns)
            is BinanceClient.BinanceResult.NotConfigured ->
                BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Binance credentials missing", ns)
        }
    }

    /** Persist a confirmed buy fill: OPEN if any quantity filled, else ERROR.
     *  executedQty is stored NET of the exchange commission when the fee was
     *  taken in the base asset (the normal spot case) — i.e. the coins you
     *  actually hold. That makes the exit's "wanted" equal the free balance,
     *  and the average price then reflects the true all-in cost per coin. */
    private fun recordFilledBuy(
        ctx: Context, paperTradeId: String, sym: String,
        trade: StrategyLabEngine.Trade, quoteSize: Double,
        order: BinanceClient.OrderResult, ns: String = "binance"
    ) {
        val baseFee = order.commissionIn(BinanceClient.baseAssetOf(sym))
        val netQty = order.executedQtyDecimal.subtract(baseFee).max(BigDecimal.ZERO)
        val status = if (netQty > BigDecimal.ZERO) "OPEN" else "ERROR"
        val spent = order.cummulativeQuoteDecimal.toDouble()
        BinanceLiveStore.upsert(
            ctx,
            BinanceLiveStore.LiveTrade(
                paperTradeId = paperTradeId,
                symbol = sym,
                status = status,
                entryTs = trade.entryTs,
                paperEntryPrice = trade.entryPrice,
                quoteOrderQty = quoteSize,
                buyOrderId = order.orderId,
                buyClientOrderId = order.clientOrderId,
                buyAt = order.transactTime,
                executedQty = netQty.stripTrailingZeros().toPlainString(),
                buyQuoteQty = order.cummulativeQuoteQty,
                // all-in cost per coin actually held (fees priced in)
                liveAvgBuyPrice = if (netQty > BigDecimal.ZERO) spent / netQty.toDouble() else 0.0,
                error = if (status == "ERROR") "Buy filled zero quantity" else ""
            ), ns
        )
        if (status == "OPEN") LabNotifier.liveBuy(ctx, sym, spent,
            netQty.stripTrailingZeros().toPlainString())
    }

    /**
     * A buy POST failed. Query the order by its deterministic clientOrderId:
     *   filled / partially filled  -> record OPEN (so the exit can flatten it)
     *   confirmed non-existent      -> genuine failure -> ERROR
     *   can't determine yet         -> UNKNOWN, keeping the clientOrderId so a
     *                                  later reconcile() pass can resolve it.
     */
    private fun reconcileBuy(
        ctx: Context, config: ConfigManager, paperTradeId: String, sym: String,
        trade: StrategyLabEngine.Trade, quoteSize: Double, clientOrderId: String, failMsg: String,
        ns: String = "binance"
    ) {
        when (val q = BinanceClient.fetchOrderStatus(config, sym, clientOrderId)) {
            is BinanceClient.BinanceResult.Success -> {
                val order = q.data
                when {
                    order == null ->
                        BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, failMsg, ns)
                    order.executedQtyDecimal > BigDecimal.ZERO -> {
                        Log.w(TAG, "Recovered live buy $sym after lost response: $failMsg")
                        recordFilledBuy(ctx, paperTradeId, sym, trade, quoteSize, order, ns)
                    }
                    else -> markUnknownBuy(ctx, paperTradeId, sym, trade, quoteSize, clientOrderId,
                        "$failMsg (order accepted, awaiting fill)", ns)
                }
            }
            is BinanceClient.BinanceResult.Failure ->
                markUnknownBuy(ctx, paperTradeId, sym, trade, quoteSize, clientOrderId,
                    "$failMsg; confirm failed: ${q.message}", ns)
            is BinanceClient.BinanceResult.NotConfigured ->
                BinanceLiveStore.markError(ctx, paperTradeId, sym, trade.entryTs, "Binance credentials missing", ns)
        }
    }

    private fun markUnknownBuy(
        ctx: Context, paperTradeId: String, sym: String,
        trade: StrategyLabEngine.Trade, quoteSize: Double, clientOrderId: String, msg: String,
        ns: String = "binance"
    ) {
        Log.w(TAG, "Live buy $sym UNKNOWN — will reconcile later: $msg")
        BinanceLiveStore.upsert(
            ctx,
            BinanceLiveStore.LiveTrade(
                paperTradeId = paperTradeId,
                symbol = sym,
                status = "UNKNOWN",
                entryTs = trade.entryTs,
                paperEntryPrice = trade.entryPrice,
                quoteOrderQty = quoteSize,
                buyClientOrderId = clientOrderId,
                error = msg
            ), ns
        )
    }

    /**
     * Re-check every live buy left in UNKNOWN (its response was lost AND we
     * couldn't confirm at the time). Resolves each to OPEN (it filled) or ERROR
     * (never placed); leaves it UNKNOWN if Binance still can't be reached.
     * Safe to call repeatedly — call on app resume and before each live tick.
     */
    fun reconcile(ctx: Context, ns: String = "binance") {
        val config = ConfigManager(ctx)
        if (!config.hasBinanceCredentials) return
        val unknowns = BinanceLiveStore.load(ctx, ns).trades.filter { it.status == "UNKNOWN" }
        for (u in unknowns) {
            val coid = u.buyClientOrderId.ifBlank { clientOrderId(u.paperTradeId, "B") }
            when (val q = BinanceClient.fetchOrderStatus(config, u.symbol, coid)) {
                is BinanceClient.BinanceResult.Success -> {
                    val order = q.data
                    when {
                        order == null ->
                            BinanceLiveStore.upsert(ctx, u.copy(status = "ERROR",
                                error = "Reconciled: order not found on Binance (never placed)"), ns)
                        order.executedQtyDecimal > BigDecimal.ZERO -> {
                            Log.w(TAG, "Reconcile recovered live buy ${u.symbol} -> OPEN")
                            BinanceLiveStore.upsert(ctx, u.copy(
                                status = "OPEN",
                                buyOrderId = order.orderId,
                                buyClientOrderId = order.clientOrderId,
                                buyAt = order.transactTime,
                                executedQty = order.executedQty,
                                buyQuoteQty = order.cummulativeQuoteQty,
                                liveAvgBuyPrice = order.averagePrice,
                                error = ""
                            ), ns)
                        }
                        // else: exists but still unfilled — leave UNKNOWN for next pass
                    }
                }
                else -> { /* still can't reach Binance — leave UNKNOWN */ }
            }
        }
        sweepOrphanExits(ctx, ns)
    }

    /**
     * ORPHAN EXIT SWEEP — the pause/resume safety net. If the app was down
     * (dead battery, reinstall, Doze) when a paper exit's bar closed, the
     * newest-bar-only rule skips the live sell on catch-up: right for LATE
     * ENTRIES (never buy stale signals), wrong for exits — an unmanaged real
     * position is open risk. So: any live OPEN position whose paper trade has
     * already CLOSED is sold now, at current market. paperExitPrice stays the
     * simulated bar close, so the ledger's paper-vs-live deviation lines show
     * exactly what the downtime cost. Idempotent: once sold (CLOSED/PARTIAL),
     * openFor() no longer matches. Paper trades unknown to the ledger are left
     * alone (e.g. after "Reset paper") — those need a human decision.
     */
    private fun sweepOrphanExits(ctx: Context, ns: String = "binance") {
        val opens = BinanceLiveStore.load(ctx, ns).trades.filter { it.status == "OPEN" }
        if (opens.isEmpty()) return
        val paper = com.signalscope.app.data.StrategyLabStore.load(ctx, ns)
            .trades.associateBy { it.id }
        for (lt in opens) {
            val pt = paper[lt.paperTradeId] ?: continue
            if (pt.status != "CLOSED") continue
            Log.w(TAG, "Orphan sweep: paper ${lt.paperTradeId} closed while app was down — selling live position late")
            mirrorExit(ctx, lt.paperTradeId, StrategyLabEngine.Trade(
                symbol = lt.symbol, side = "LONG", status = "CLOSED",
                entryTs = pt.entryTs, entryPrice = pt.entryPrice, entryReason = pt.entryReason,
                exitTs = pt.exitTs, exitPrice = pt.exitPrice, exitReason = pt.exitReason,
                pctReturn = pt.pctReturn, pnlNotional = pt.pnlNotional, barsHeld = pt.barsHeld
            ), ns)
        }
    }

    /** How far through the configured stop a position must have gapped before
     *  the emergency exit fires without waiting for a bar close. 2.0 = twice
     *  the stop (e.g. −8% on a −4% stop). Pre-decided policy: exit at market
     *  immediately, no discretion — every further minute is un-modeled risk. */
    private const val EMERGENCY_GAP_MULT = 2.0

    /**
     * PRE-DECIDED GAP POLICY: a live OPEN position found ≥ EMERGENCY_GAP_MULT ×
     * stop underwater (weekend gap / app downtime slipped past the bar-based
     * stop) is sold immediately at market. The normal bar-close exit remains
     * the primary path; this is the "found it already deep underwater" backstop.
     */
    fun emergencyStopCheck(ctx: Context, symbol: String, currentPrice: Double, stopLossPct: Double, ns: String = "binance") {
        if (stopLossPct <= 0.0 || currentPrice <= 0.0) return
        val sym = symbol.uppercase()
        val threshold = -EMERGENCY_GAP_MULT * stopLossPct
        val opens = BinanceLiveStore.load(ctx, ns).trades.filter { it.status == "OPEN" && it.symbol == sym }
        for (lt in opens) {
            val basis = if (lt.liveAvgBuyPrice > 0.0) lt.liveAvgBuyPrice else lt.paperEntryPrice
            if (basis <= 0.0) continue
            val lossPct = (currentPrice / basis - 1.0) * 100.0
            if (lossPct > threshold) continue
            Log.w(TAG, "EMERGENCY STOP $sym: ${"%.2f".format(lossPct)}% ≤ ${"%.2f".format(threshold)}% — selling at market now")
            mirrorExit(ctx, lt.paperTradeId, StrategyLabEngine.Trade(
                symbol = sym, side = "LONG", status = "CLOSED",
                entryTs = lt.entryTs, entryPrice = lt.paperEntryPrice, entryReason = "",
                exitTs = System.currentTimeMillis() / 1000, exitPrice = currentPrice,
                exitReason = "EMERGENCY: found ${"%.1f".format(lossPct)}% under entry — gapped through the ${"%.1f".format(stopLossPct)}% stop",
                pctReturn = lossPct, pnlNotional = 0.0, barsHeld = 0
            ), ns)
        }
    }

    /**
     * ORPHAN SWEEP — close live positions whose PAPER trade is already closed.
     *
     * Why this must exist: StrategyLabRunner mirrors an exit only when the
     * paper exit lands on the FRESHEST bar (`exitTs == freshBar`). If the tick
     * that observes the exit runs even one bar late — phone asleep, MIUI kill,
     * Doze, no network — freshBar has already advanced, the sell never fires,
     * and because paper is now FLAT it will never fire again. The result is a
     * real position on the exchange that nothing will ever close.
     *
     * Observed live: paper BTCUSDC flat (trade closed 22 Jul 08:00) while
     * Binance still held 0.0046 BTC (~$300) with the mirror showing it OPEN.
     *
     * This sweep is the safety net: every tick, any live-OPEN record whose
     * paper counterpart is CLOSED (or has vanished from the ledger) is sold at
     * market now. Deliberately NOT gated by freshness or the allowlist —
     * closing a real position is always risk-reducing, and mirrorExit is
     * idempotent (it no-ops when there is no open live record).
     */
    /**
     * CONVERGENCE LOOP — desired-state reconciliation, thermostat-style.
     *
     * Every tick: DESIRED = symbols the paper strategy currently holds open;
     * ACTUAL = open live records (+ real exchange balances for the audit).
     * Anything ACTUAL that DESIRED no longer explains is sold now (risk
     * reduction is never gated); anything on the exchange that neither the
     * ledger nor the strategy explains is FLAGGED as unmanaged, never sold —
     * it may be a deliberate manual holding. Entries stay event-driven behind
     * their strict gates (allowlist, freshness, newest-bar) — asymmetric by
     * design: closing risk automatic, opening risk paranoid.
     */
    fun converge(ctx: Context, ns: String = "binance") {
        val config = ConfigManager(ctx)
        if (!config.hasBinanceCredentials) return
        // The audit is GLOBAL across every crypto ledger (bare + buckets) so one
        // strategy's legit position is never flagged as "unmanaged" by another —
        // the exchange balance is shared, the ledgers are not. The orphan sweep
        // is per-ns (each ledger vs its own paper).
        try { auditUnmanaged(ctx, config) } catch (e: Exception) { Log.w(TAG, "balance audit failed", e) }
        sweepOrphans(ctx, ns)
    }

    /** All crypto live-ledger namespaces: bare "binance" + any binance buckets. */
    private fun cryptoLedgerNamespaces(ctx: Context): List<String> =
        (listOf("binance") + com.signalscope.app.data.BucketStore.list(ctx, "binance").map { it.ns }).distinct()

    /** Base-asset quantity held OPEN by every crypto ledger EXCEPT [ns]. Used to
     *  reserve other buckets' coins so a sell never dips into their positions. */
    private fun reservedByOtherLedgers(ctx: Context, ns: String, baseAsset: String): BigDecimal {
        var total = BigDecimal.ZERO
        for (other in cryptoLedgerNamespaces(ctx)) {
            if (other == ns) continue
            for (t in BinanceLiveStore.load(ctx, other).trades) {
                if (t.status != "OPEN") continue
                if (BinanceClient.baseAssetOf(t.symbol) != baseAsset) continue
                total += t.executedQty.toBigDecimalOrNull() ?: BigDecimal.ZERO
            }
        }
        return total
    }

    /** Compare real exchange balances against what ALL ledgers + strategies
     *  explain; store the unexplained remainder for the UI banner. Never sells. */
    private fun auditUnmanaged(ctx: Context, config: ConfigManager) {
        val account = (BinanceClient.fetchAccount(config) as? BinanceClient.BinanceResult.Success)?.data ?: return
        // Aggregate OPEN quantity per base asset across EVERY crypto ledger.
        val allOpen = cryptoLedgerNamespaces(ctx).flatMap { BinanceLiveStore.load(ctx, it).trades }
        val live = BinanceLiveStore.load(ctx)   // bare ledger, for valuation fallback below
        // base-asset qty explained by OPEN live records across ALL crypto ledgers
        val explained = HashMap<String, BigDecimal>()
        for (t in allOpen) {
            if (t.status != "OPEN") continue
            val base = BinanceClient.baseAssetOf(t.symbol) ?: continue
            explained[base] = (explained[base] ?: BigDecimal.ZERO) +
                (t.executedQty.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }
        val quotes = BinanceClient.SUPPORTED_QUOTES.toSet()
        val lines = ArrayList<String>()
        for ((asset, bal) in account.balances) {
            if (asset.uppercase() in quotes) continue           // cash is not a position
            val free = bal.free.toBigDecimalOrNull() ?: continue
            val known = explained[asset.uppercase()] ?: BigDecimal.ZERO
            val excess = free - known
            // Ignore dust: fee residue etc. Threshold is deliberately coarse —
            // this banner is for "a position nobody is managing", not for cents.
            if (excess > BigDecimal("0.0000001") ) {
                val approx = excess.toDouble() * ( // rough valuation via avg buy price of any record, else skip value
                    live.trades.lastOrNull { BinanceClient.baseAssetOf(it.symbol) == asset.uppercase() }
                        ?.liveAvgBuyPrice ?: 0.0)
                if (approx >= 5.0) lines.add(
                    "$asset: ${excess.stripTrailingZeros().toPlainString()} (~${"%.0f".format(approx)} USDC) held but not tracked")
            }
        }
        BinanceLiveStore.setUnmanaged(ctx, lines)
    }

    fun sweepOrphans(ctx: Context, ns: String = "binance") {
        val config = ConfigManager(ctx)
        if (!config.hasBinanceCredentials) return
        val openLive = BinanceLiveStore.load(ctx, ns).trades.filter { it.status == "OPEN" }
        if (openLive.isEmpty()) return
        val paperState = com.signalscope.app.data.StrategyLabStore.load(ctx, ns)
        val paper = paperState.trades.associateBy { it.id }
        // Symbols the strategy currently WANTS exposure to (any open paper trade).
        val paperOpenSymbols = paperState.trades
            .filter { it.status == "OPEN" }.map { it.symbol.uppercase() }.toSet()
        for (lt in openLive) {
            val p = paper[lt.paperTradeId]
            // Paper still holding it → the normal exit path will handle it.
            if (p != null && p.status == "OPEN") continue
            // SYMBOL-LEVEL GUARD (do not remove).
            // Matching on trade id alone is not enough: "Reset paper" wipes the
            // paper ledger but NOT this one, so every live record loses its
            // counterpart (p == null) and a naive sweep liquidates positions the
            // strategy still wants. Observed live: ETH was sold while paper held
            // an open ETH trade under a NEW id. If the strategy is long this
            // symbol under ANY open trade, leave the position alone — the live
            // record simply re-links to the current trade on its next exit.
            if (lt.symbol.uppercase() in paperOpenSymbols) {
                Log.i(TAG, "sweep SKIP ${lt.symbol}: paper holds an open trade under a " +
                    "different id (likely a paper reset) — not liquidating")
                continue
            }
            Log.w(TAG, "ORPHAN: paper ${if (p == null) "record gone" else "CLOSED"} but live OPEN for " +
                "${lt.symbol} (${lt.paperTradeId}) — selling now")
            val synthetic = StrategyLabEngine.Trade(
                symbol = lt.symbol,
                status = "CLOSED",
                entryTs = lt.entryTs,
                entryPrice = lt.paperEntryPrice,
                entryReason = "",
                exitTs = p?.exitTs,
                exitPrice = p?.exitPrice,
                exitReason = "orphan-sweep",
                pctReturn = p?.pctReturn ?: 0.0,
                pnlNotional = 0.0,
                barsHeld = 0
            )
            try { mirrorExit(ctx, lt.paperTradeId, synthetic, ns) }
            catch (e: Exception) { Log.e(TAG, "orphan sweep sell failed for ${lt.symbol}", e) }
        }
    }

    /**
     * INVARIANT — DO NOT add a binanceLiveAllows() check here.
     *
     * The allowlist gates ENTRIES only. An exit must always be able to close a
     * position that is genuinely open on the exchange, even if the symbol was
     * since removed from the allowlist — otherwise real money would be stranded
     * in a position the app refuses to sell. The real gate is the open live
     * record lookup below: no record → nothing to sell → return.
     */
    fun mirrorExit(ctx: Context, paperTradeId: String, trade: StrategyLabEngine.Trade, ns: String = "binance") {
        val config = ConfigManager(ctx)
        if (!config.hasBinanceCredentials) return
        val sym = trade.symbol.uppercase()
        val live = BinanceLiveStore.openFor(ctx, paperTradeId, ns)
        if (live == null) {
            Log.i(TAG, "No open live mirror for $paperTradeId; skipping live sell")
            return
        }
        if (!config.hasBinanceCredentials) {
            BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR", error = "Binance credentials missing before sell"), ns)
            return
        }

        val baseAsset = BinanceClient.baseAssetOf(sym) ?: sym.removeSuffix("USDT")
        val account = when (val r = BinanceClient.fetchAccount(config)) {
            is BinanceClient.BinanceResult.Success -> r.data
            is BinanceClient.BinanceResult.Failure -> {
                BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR", error = r.message), ns)
                return
            }
            is BinanceClient.BinanceResult.NotConfigured -> {
                BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR", error = "Binance credentials missing before sell"), ns)
                return
            }
        }
        val wanted = live.executedQty.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val free = account.balances[baseAsset]?.free?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        // SHARED-BALANCE RESERVATION (multi-bucket safety):
        // the exchange holds ONE balance per coin, but each bucket has its own
        // ledger. Subtract what OTHER crypto ledgers still have OPEN in this coin
        // so this sell can only ever touch THIS bucket's share — never liquidate
        // another strategy's position. (No-op for the bare single path, where no
        // other crypto ledgers hold the coin.)
        val reservedByOthers = reservedByOtherLedgers(ctx, ns, baseAsset)
        val sellable = (free - reservedByOthers).max(BigDecimal.ZERO)
        val qty = minOf(wanted, sellable)
        if (qty <= BigDecimal.ZERO) {
            BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR",
                error = "No free $baseAsset balance to sell for this bucket " +
                    "(free $free, reserved by other strategies $reservedByOthers)"), ns)
            return
        }

        val clientOrderId = clientOrderId(paperTradeId, "S")
        val estimatedPrice = trade.exitPrice
            ?.takeIf { it > 0.0 }
            ?.let { BigDecimal.valueOf(it) }
            ?: live.liveAvgBuyPrice.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) }
        when (val r = BinanceClient.placeMarketSell(
            config,
            sym,
            qty.stripTrailingZeros().toPlainString(),
            clientOrderId,
            estimatedPrice
        )) {
            is BinanceClient.BinanceResult.Success -> {
                val order = r.data
                val buyQuote = live.buyQuoteQty.toDoubleOrNull() ?: 0.0
                // NET proceeds: Binance reports the gross quote amount and takes
                // the sell commission in the quote asset — subtract it so the
                // ledger shows the USDC that actually landed in the wallet.
                val quoteFee = order.commissionIn(BinanceClient.quoteAssetOf(sym))
                val netSellQuote = order.cummulativeQuoteDecimal.subtract(quoteFee).max(BigDecimal.ZERO)
                val sellQuote = netSellQuote.toDouble()
                val boughtQty = live.executedQty.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val soldQty = order.executedQtyDecimal
                // Fee (taken in the base asset, ~0.1%) + lot-step floor dust mean
                // EVERY sell moves slightly less than the recorded gross buy. That
                // is a real cost of a FULLY closed trade, so charge the full buy
                // cost (understating profit by the fee is honest). Only a genuine
                // shortfall — coins locked / moved / manually spent — is PARTIAL:
                // then the cost basis is prorated to the fraction actually sold
                // and the residual is flagged so it can't silently look finished.
                val soldFrac = if (boughtQty > BigDecimal.ZERO)
                    soldQty.toDouble() / boughtQty.toDouble() else 1.0
                val fullyClosed = soldFrac >= 0.995
                val costBasis = if (fullyClosed) buyQuote else buyQuote * soldFrac
                val residual = boughtQty.subtract(soldQty).max(BigDecimal.ZERO)
                    .stripTrailingZeros().toPlainString()
                BinanceLiveStore.upsert(
                    ctx,
                    live.copy(
                        status = if (fullyClosed) "CLOSED" else "PARTIAL",
                        exitTs = trade.exitTs,
                        paperExitPrice = trade.exitPrice,
                        sellOrderId = order.orderId,
                        sellClientOrderId = order.clientOrderId,
                        sellAt = order.transactTime,
                        sellExecutedQty = order.executedQty,
                        sellQuoteQty = netSellQuote.stripTrailingZeros().toPlainString(),
                        // net proceeds per coin sold (sell fee priced in)
                        liveAvgSellPrice = if (soldQty > BigDecimal.ZERO)
                            sellQuote / soldQty.toDouble() else order.averagePrice,
                        livePnlQuote = sellQuote - costBasis,
                        error = if (fullyClosed) "" else
                            "PARTIAL close: sold ${soldQty.stripTrailingZeros().toPlainString()} of " +
                                "${boughtQty.stripTrailingZeros().toPlainString()} $baseAsset — ~$residual $baseAsset " +
                                "left in wallet; P&L prorated to the sold fraction. Sell the rest manually if intended."
                    ), ns
                )
                LabNotifier.liveSell(ctx, sym, sellQuote, sellQuote - costBasis, trade.exitReason)
            }
            is BinanceClient.BinanceResult.Failure ->
                BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR", error = r.message), ns)
            is BinanceClient.BinanceResult.NotConfigured ->
                BinanceLiveStore.upsert(ctx, live.copy(status = "ERROR", error = "Binance credentials missing before sell"), ns)
        }
    }

    /**
     * The USDT quote amount to spend on one live BUY for [sym].
     *
     * FIXED  → the flat per-trade size.
     * BUDGET → the symbol's current reinvest balance (budget/N seed, compounded
     *          by realized PnL) if the reinvest layer is initialized; otherwise
     *          a live budget ÷ enrolled-count split. Falls back to the fixed
     *          size if no usable budget is configured, so a live entry never
     *          silently sizes to zero.
     */
    private fun perTradeQuoteSize(ctx: Context, config: ConfigManager, sym: String): Double {
        val fixed = config.binanceLiveQuoteOrderSize
        if (config.binanceLiveSizingMode != "BUDGET") return fixed

        // Prefer the per-symbol compounded capital the reinvest engine tracks.
        val rs = com.signalscope.app.data.ReinvestStore.load(ctx, "binance")
        if (rs.initialized) {
            val bal = rs.balances[sym.uppercase()]
            if (bal != null && bal > 0.0) return bal
        }
        // Otherwise split the raw budget equally across the currently enrolled basket.
        val budget = config.binanceLiveBudget
        if (budget > 0.0) {
            val enrolled = com.signalscope.app.data.StrategyLabStore
                .load(ctx, "binance").enrolled.size.coerceAtLeast(1)
            return budget / enrolled
        }
        return fixed
    }

    private fun clientOrderId(paperTradeId: String, side: String): String {
        val h = Integer.toHexString(paperTradeId.hashCode())
        return "ss_${side}_${h}".take(36)
    }
}
